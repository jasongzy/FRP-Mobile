import argparse
import json
import os
import re
import subprocess
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAN_FIELDS = ("source", "source_tag", "version", "code", "frp")
VERSION_FILES = ("gradle.properties", "native/go.mod", "native/go.sum")


def git(root: str, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8").strip()


def api(path: str, data: dict = None, paginate: bool = False, method: str = ""):
    command = ["gh", "api", path]
    if paginate:
        command += ["--paginate", "--slurp"]
    if data is not None:
        command += ["--input", "-"]
    if method:
        command += ["--method", method]
    result = subprocess.run(
        command,
        input=json.dumps(data) if data is not None else None,
        stdout=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        check=True,
    )
    return json.loads(result.stdout)


def endpoint(suffix: str = "") -> str:
    return f"repos/{os.environ['GITHUB_REPOSITORY']}/releases{suffix}"


def version_tuple(version: str) -> tuple[int, ...]:
    if not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", version):
        raise ValueError(f"INVALID_VERSION: {version!r}")
    return tuple(map(int, version.split(".")))


def read(path: str) -> str:
    with open(path, encoding="utf-8") as file:
        return file.read()


def write(path: str, text: str):
    with open(path, "w", encoding="utf-8", newline="\n") as file:
        file.write(text)


def replace_once(text: str, pattern: str, value: str) -> str:
    result, count = re.subn(pattern, lambda _: value, text, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"VERSION_FILE_MISMATCH: expected one match for {pattern!r}, found {count}")
    return result


def property_value(text: str, name: str) -> str:
    values = re.findall(rf"^{name}=(.+)$", text, re.MULTILINE)
    if len(values) != 1:
        raise ValueError(f"VERSION_FILE_MISMATCH: expected one {name}")
    return values[0]


def release_tag(config: str) -> str:
    app = property_value(config, "VERSION_NAME")
    frp = property_value(config, "FRP_VERSION")
    version_tuple(app)
    version_tuple(frp)
    return f"{app}-frp{frp}"


def release_version(release: dict) -> str:
    tag = release["tag_name"]
    if release["draft"] or release["prerelease"] or not tag.startswith("v"):
        raise ValueError("INVALID_FRP_RELEASE: expected a published stable vMAJOR.MINOR.PATCH release")
    version_tuple(tag[1:])
    return tag[1:]


def update_core(root: str, version: str):
    version_tuple(version)
    path = os.path.join(root, "gradle.properties")
    config = read(path)
    current = property_value(config, "FRP_VERSION")
    if version_tuple(version) <= version_tuple(current):
        raise ValueError(f"FRP_UPDATE_NOT_NEWER: {version} <= {current}")
    native = os.path.join(root, "native")
    subprocess.run(["go", "get", "github.com/fatedier/frp@v" + version], cwd=native, check=True)
    subprocess.run(["go", "mod", "tidy"], cwd=native, check=True)
    resolved = subprocess.check_output(
        ["go", "list", "-m", "-f", "{{.Version}}", "github.com/fatedier/frp"], cwd=native, text=True
    ).strip()
    if resolved != "v" + version:
        raise ValueError(f"FRP_VERSION_MISMATCH: requested v{version}, resolved {resolved}")
    toolchain = subprocess.check_output(["go", "env", "GOVERSION"], cwd=native, text=True).strip().removeprefix("go")
    version_tuple(toolchain)
    config = replace_once(config, r"^FRP_VERSION=.+$", "FRP_VERSION=" + version)
    config = replace_once(config, r"^GO_VERSION=.+$", "GO_VERSION=" + toolchain)
    write(path, config)


def plan_body(plan: dict) -> str:
    return "<!-- frp-mobile-release " + json.dumps({key: plan[key] for key in PLAN_FIELDS}) + " -->"


def read_plan(release: dict) -> dict:
    match = re.search(r"<!-- frp-mobile-release (\{[^\n]+\}) -->", release.get("body") or "")
    if not match:
        if "-frp" in release["tag_name"]:
            raise ValueError(f"RELEASE_PLAN_MISSING: {release['tag_name']}")
        return {}
    plan = json.loads(match[1])
    version_tuple(plan["version"])
    version_tuple(plan["frp"])
    if (
        not re.fullmatch(r"[0-9a-f]{40}", plan["source"])
        or plan["source_tag"] not in ("", plan["version"])
        or type(plan["code"]) is not int
        or not 0 < plan["code"] <= 2_100_000_000
        or release["tag_name"] != f"{plan['version']}-frp{plan['frp']}"
        or release["prerelease"]
    ):
        raise ValueError(f"INVALID_RELEASE_PLAN: {release['tag_name']}")
    return plan | {"id": release["id"], "tag": release["tag_name"], "published": not release["draft"]}


def fetch(root: str):
    branch = os.environ["DEFAULT_BRANCH"]
    git(root, "fetch", "--tags", "origin", f"refs/heads/{branch}:refs/remotes/origin/{branch}")


def ancestor(root: str, older: str, newer: str) -> bool:
    result = subprocess.run(["git", "merge-base", "--is-ancestor", older, newer], cwd=root)
    if result.returncode not in (0, 1):
        result.check_returncode()
    return result.returncode == 0


def tag_commit(root: str, tag: str) -> str:
    if not git(root, "for-each-ref", "--format=%(refname)", "refs/tags/" + tag):
        return ""
    return git(root, "rev-parse", f"refs/tags/{tag}^{{commit}}")


def remote_tag_commit(root: str, tag: str) -> str:
    ref = "refs/tags/" + tag
    output = git(root, "ls-remote", "origin", ref, ref + "^{}")
    refs = {name: commit for commit, name in (line.split() for line in output.splitlines())}
    return refs.get(ref + "^{}", refs.get(ref, ""))


def matches_config(config: str, plan: dict) -> bool:
    return release_tag(config) == plan["tag"] and int(property_value(config, "VERSION_CODE")) == plan["code"]


def verify_commit(root: str, plan: dict, commit: str):
    if plan["source_tag"] and tag_commit(root, plan["source_tag"]) != plan["source"]:
        raise ValueError("SOURCE_TAG_MOVED: the app tag no longer matches the release plan")
    changed = set(git(root, "diff", "--name-only", plan["source"], commit).splitlines())
    if (
        not ancestor(root, plan["source"], commit)
        or not changed.issubset(VERSION_FILES)
        or not matches_config(git(root, "show", f"{commit}:gradle.properties"), plan)
    ):
        raise ValueError(f"RELEASE_COMMIT_MISMATCH: {plan['tag']} does not match its source and versions")


def find_commit(root: str, plan: dict) -> str:
    marker = f"^FRP-Mobile-Release: {re.escape(plan['tag'])}$"
    candidates = set(git(root, "log", "--all", "--format=%H", "--extended-regexp", "--grep=" + marker).splitlines())
    tagged = tag_commit(root, plan["tag"])
    if tagged:
        candidates.add(tagged)
    if not candidates and matches_config(git(root, "show", f"{plan['source']}:gradle.properties"), plan):
        candidates.add(plan["source"])
    if len(candidates) > 1:
        raise ValueError(f"RELEASE_TAG_CONFLICT: multiple commits claim {plan['tag']}")
    commit = next(iter(candidates), "")
    if commit:
        verify_commit(root, plan, commit)
    if plan["published"] and not tagged:
        raise ValueError(f"RELEASE_TAG_MISSING: {plan['tag']}")
    return commit


def prepare(root: str, source_tag: str = "", frp_tag: str = "") -> dict:
    if source_tag:
        version_tuple(source_tag)
    if frp_tag:
        if not frp_tag.startswith("v"):
            raise ValueError("INVALID_FRP_RELEASE: expected vMAJOR.MINOR.PATCH")
        version_tuple(frp_tag[1:])
    fetch(root)
    releases = [release for page in api(endpoint("?per_page=100"), paginate=True) for release in page]
    plans = [plan for release in releases if (plan := read_plan(release))]
    matching = [plan for plan in plans if source_tag and plan["source_tag"] == source_tag]
    pending = [plan for plan in plans if not plan["published"]]
    if len(matching) > 1 or len(pending) > 1:
        raise ValueError("RELEASE_PLAN_CONFLICT: multiple release plans need attention")
    plan = next(iter(matching), None)
    if plan is None and pending:
        if source_tag:
            raise ValueError(
                f"PENDING_RELEASE: finish {pending[0]['tag']} or delete its draft before starting {source_tag}"
            )
        plan = pending[0]
    if plan is None:
        if not source_tag and not plans:
            print("Publish an app version tag before enabling upstream updates.")
            return {}
        source = (
            tag_commit(root, source_tag)
            if source_tag
            else git(root, "rev-parse", "origin/" + os.environ["DEFAULT_BRANCH"])
        )
        if not source:
            raise ValueError(f"SOURCE_TAG_MISSING: {source_tag}")
        config = git(root, "show", f"{source}:gradle.properties")
        version = property_value(config, "VERSION_NAME")
        code = int(property_value(config, "VERSION_CODE"))
        if not 0 < code <= 2_100_000_000:
            raise ValueError("INVALID_VERSION_CODE: the selected source has an invalid version code")
        if source_tag and source_tag != version:
            raise ValueError(f"SOURCE_TAG_MISMATCH: expected {version}, got {source_tag}")
        latest = max((version_tuple(item["version"]) for item in plans), default=(0, 0, 0))
        if version_tuple(version) < latest or (source_tag and version_tuple(version) == latest):
            raise ValueError("APP_VERSION_NOT_NEWER: use an app version after the latest published version")
        if not source_tag and version_tuple(version) > latest:
            print(f"Publish app tag {version} before following further upstream releases.")
            return {}
        upstream = release_version(api("repos/fatedier/frp/releases/" + ("tags/" + frp_tag if frp_tag else "latest")))
        current = property_value(config, "FRP_VERSION")
        if version_tuple(upstream) < version_tuple(current):
            raise ValueError("FRP_VERSION_DOWNGRADE: upstream release is older than the selected source")
        if not source_tag:
            bundled = max([version_tuple(current)] + [version_tuple(item["frp"]) for item in plans])
            if version_tuple(upstream) <= bundled:
                print("The latest stable frp version is already integrated.")
                return {}
            major, minor, patch = version_tuple(version)
            version = f"{major}.{minor}.{patch + 1}"
            code += 1
        code = max(code, max((item["code"] for item in plans), default=0) + 1)
        if not 0 < code <= 2_100_000_000:
            raise ValueError("INVALID_VERSION_CODE: cannot allocate another release")
        plan = {"source": source, "source_tag": source_tag, "version": version, "code": code, "frp": upstream}
        tag = f"{version}-frp{upstream}"
        if tag_commit(root, tag):
            raise ValueError(f"RELEASE_TAG_CONFLICT: {tag} already exists without a release plan")
        plan = read_plan(
            api(
                endpoint(),
                {
                    "tag_name": tag,
                    "target_commitish": source,
                    "draft": True,
                    "name": f"{os.environ['APP_DISPLAY_NAME']} {tag}",
                    "body": plan_body(plan),
                },
            )
        )
    plan["commit"] = find_commit(root, plan)
    plan["frp_changed"] = (
        property_value(git(root, "show", f"{plan['source']}:gradle.properties"), "FRP_VERSION") != plan["frp"]
    )
    if not plan["published"]:
        git(root, "checkout", "--detach", plan["commit"] or plan["source"])
        git(root, "config", "user.name", "github-actions[bot]")
        git(root, "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    return plan


def integrate(root: str, plan: dict):
    if plan["commit"]:
        return
    config = read(os.path.join(root, "gradle.properties"))
    if property_value(config, "FRP_VERSION") != plan["frp"]:
        update_core(root, plan["frp"])


def commit_release(root: str, plan: dict):
    if plan["commit"]:
        verify_commit(root, plan, git(root, "rev-parse", "HEAD"))
        return
    if git(root, "rev-parse", "HEAD") != plan["source"]:
        raise ValueError("RELEASE_SOURCE_MISMATCH: the checkout changed during integration")
    if not set(git(root, "diff", "--name-only", "HEAD").splitlines()).issubset(VERSION_FILES):
        raise ValueError("UNEXPECTED_RELEASE_CHANGES: only version and dependency files may change")
    path = os.path.join(root, "gradle.properties")
    config = read(path)
    updated = replace_once(config, r"^VERSION_NAME=.+$", "VERSION_NAME=" + plan["version"])
    updated = replace_once(updated, r"^VERSION_CODE=.+$", f"VERSION_CODE={plan['code']}")
    marker = f"FRP-Mobile-Release: {plan['tag']}"
    git(root, "add", "--", *VERSION_FILES)
    if git(root, "diff", "--cached", "--name-only"):
        message = f"🔧 build: update frp to v{plan['frp']}"
        git(root, "commit", "-m", message if config != updated else message + "\n\n" + marker)
    if config != updated:
        write(path, updated)
        git(root, "add", "gradle.properties")
        subject = (
            f"bump version to {plan['version']}"
            if property_value(config, "VERSION_NAME") != plan["version"]
            else f"bump version code to {plan['code']}"
        )
        git(root, "commit", "-m", "🐳 chore: " + subject + "\n\n" + marker)
    plan["commit"] = git(root, "rev-parse", "HEAD")
    verify_commit(root, plan, plan["commit"])


def publish(root: str, plan: dict):
    release = api(endpoint("/" + str(plan["id"])))
    current = read_plan(release)
    if any(current[key] != plan[key] for key in PLAN_FIELDS):
        raise ValueError("RELEASE_PLAN_CHANGED: refusing to publish different release inputs")
    fetch(root)
    head = plan["commit"]
    verify_commit(root, plan, head)
    tagged = remote_tag_commit(root, plan["tag"])
    if tagged and tagged != head:
        raise ValueError(f"RELEASE_TAG_CONFLICT: {plan['tag']} points to another commit")
    if current["published"]:
        if not tagged:
            raise ValueError(f"RELEASE_TAG_MISSING: {plan['tag']}")
        return
    apk = f"{os.environ['APP_NAME']}-{plan['tag']}-release.apk"
    for name in (apk, apk + ".sha256"):
        if not os.path.isfile(os.path.join(root, name)):
            raise ValueError(f"RELEASE_ASSET_MISSING: {name}")
    if not tagged:
        branch = os.environ["DEFAULT_BRANCH"]
        remote_head = git(root, "rev-parse", "origin/" + branch)
        remote_version = property_value(git(root, "show", f"{remote_head}:gradle.properties"), "VERSION_NAME")
        sync_branch = remote_head == plan["source"] or (
            not plan["source_tag"] and version_tuple(remote_version) <= version_tuple(plan["version"])
        )
        if not ancestor(root, head, remote_head) and sync_branch:
            if not ancestor(root, plan["source"], remote_head):
                raise ValueError("DEFAULT_BRANCH_DIVERGED: the source is no longer on the default branch")
            if ancestor(root, remote_head, head):
                branch_commit = head
            else:
                git(root, "checkout", "--detach", remote_head)
                git(root, "merge", "--no-edit", head)
                branch_commit = git(root, "rev-parse", "HEAD")
            git(root, "push", "origin", f"{branch_commit}:refs/heads/{branch}")
            git(root, "checkout", "--detach", head)
        local = tag_commit(root, plan["tag"])
        if local and local != head:
            raise ValueError(f"RELEASE_TAG_CONFLICT: local {plan['tag']} points to another commit")
        if not local:
            git(root, "tag", plan["tag"], head)
        time.sleep(30)
        git(root, "push", "origin", "refs/tags/" + plan["tag"])
        if remote_tag_commit(root, plan["tag"]) != head:
            raise ValueError(f"RELEASE_TAG_CONFLICT: remote {plan['tag']} does not match the APK commit")
    subprocess.run(["gh", "release", "upload", plan["tag"], apk, apk + ".sha256", "--clobber"], cwd=root, check=True)
    notes = api(endpoint("/generate-notes"), {"tag_name": plan["tag"], "target_commitish": head})
    api(
        endpoint("/" + str(plan["id"])),
        {
            "body": notes["body"] + "\n\n" + plan_body(plan),
            "target_commitish": head,
            "draft": False,
            "make_latest": "true",
        },
        method="PATCH",
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Prepare and publish pinned App/frp releases.")
    parser.add_argument("action", choices=("prepare", "integrate", "commit", "publish", "tag"))
    parser.add_argument(
        "--source-tag", default="", help="App version tag; empty follows upstream from the default branch"
    )
    parser.add_argument("--frp-tag", default="", help="Official stable frp tag; empty selects the latest release")
    args = parser.parse_args()
    if args.action == "tag":
        print(release_tag(read(os.path.join(ROOT, "gradle.properties"))))
    else:
        path = os.path.join(os.environ["RUNNER_TEMP"], "frp-mobile-release.json")
        if args.action == "prepare":
            plan = prepare(ROOT, args.source_tag, args.frp_tag)
            write(path, json.dumps(plan))
            build = bool(plan) and not plan["published"]
            print(f"Release: {plan.get('tag', 'none')}; build: {build}")
            if output := os.environ.get("GITHUB_OUTPUT"):
                with open(output, "a", encoding="utf-8") as file:
                    file.write(f"build={str(build).lower()}\n")
                    file.write(f"frp_changed={str(plan.get('frp_changed', False)).lower()}\n")
        else:
            plan = json.loads(read(path))
            if args.action == "integrate":
                integrate(ROOT, plan)
            elif args.action == "commit":
                commit_release(ROOT, plan)
                write(path, json.dumps(plan))
            else:
                publish(ROOT, plan)
