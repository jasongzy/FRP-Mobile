import copy
import importlib.util
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("release", os.path.join(os.path.dirname(__file__), "release.py"))
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)


class VersionTest(unittest.TestCase):
    def test_stable_versions_and_dependency_update(self):
        stable = {"tag_name": "v0.72.0", "draft": False, "prerelease": False}
        self.assertEqual(RELEASE.release_version(stable), "0.72.0")
        for changes in (
            {"draft": True},
            {"prerelease": True},
            {"tag_name": "v0.72.0-rc.1"},
            {"tag_name": "v1; echo x"},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                RELEASE.release_version(stable | changes)
        self.assertGreater(RELEASE.version_tuple("0.100.0"), RELEASE.version_tuple("0.99.9"))
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "gradle.properties")
            config = "VERSION_NAME=1.0.0\nVERSION_CODE=1\nFRP_VERSION=0.71.1\nGO_VERSION=1.27.3\n"
            RELEASE.write(path, config)
            with patch("subprocess.run") as run, patch(
                "subprocess.check_output", side_effect=["v0.72.0\n", "go1.28.1\n"]
            ):
                RELEASE.update_core(root, "0.72.0")
            self.assertEqual(run.call_count, 2)
            self.assertEqual(RELEASE.read(path), config.replace("0.71.1", "0.72.0").replace("1.27.3", "1.28.1"))
            self.assertEqual(RELEASE.release_tag(RELEASE.read(path)), "1.0.0-frp0.72.0")
            with patch("subprocess.run") as run, self.assertRaisesRegex(ValueError, "FRP_UPDATE_NOT_NEWER"):
                RELEASE.update_core(root, "0.71.1")
            run.assert_not_called()


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="frp-release-")
        self.addCleanup(self.temp.cleanup)
        self.remote = os.path.join(self.temp.name, "remote")
        self.writer = os.path.join(self.temp.name, "writer")
        subprocess.run(["git", "init", "--bare", "--initial-branch=main", self.remote], check=True, capture_output=True)
        self.clone_count = 0
        self.clone(self.writer)
        self.git(self.writer, "config", "user.name", "Test")
        self.git(self.writer, "config", "user.email", "test@example.invalid")
        os.mkdir(os.path.join(self.writer, "native"))
        self.config = (
            "VERSION_NAME=1.0.0\nVERSION_CODE=1\nMODULE_VERSION_NAME=v1.0\nMODULE_VERSION_CODE=1\n"
            "FRP_VERSION=0.71.1\nGO_VERSION=1.27.3\n"
        )
        self.write(self.writer, "gradle.properties", self.config)
        self.write(self.writer, "native/go.mod", "module test\nrequire github.com/fatedier/frp v0.71.1\n")
        self.write(self.writer, "native/go.sum", "")
        self.write(self.writer, "app.txt", "initial app\n")
        self.git(self.writer, "add", ".")
        self.git(self.writer, "commit", "-m", "initial")
        self.base = self.git(self.writer, "rev-parse", "HEAD")
        self.git(self.writer, "tag", "-a", "1.0.0", "-m", "app source")
        self.git(self.writer, "push", "origin", "main", "refs/tags/1.0.0")
        self.releases = {}
        self.latest = "0.71.1"
        self.fail = ""
        self.uploads = []
        self.real_run = subprocess.run
        self.real_git = RELEASE.git
        for context in (
            patch.dict(
                os.environ,
                {
                    "DEFAULT_BRANCH": "main",
                    "GITHUB_REPOSITORY": "test/app",
                    "APP_NAME": "test",
                    "APP_DISPLAY_NAME": "Test",
                },
            ),
            patch.object(RELEASE, "api", self.api),
            patch.object(RELEASE, "update_core", self.update_core),
            patch.object(RELEASE, "git", self.release_git),
            patch.object(RELEASE.time, "sleep"),
            patch.object(RELEASE.subprocess, "run", self.run_command),
        ):
            context.start()
            self.addCleanup(context.stop)

    def git(self, root: str, *args: str) -> str:
        return subprocess.check_output(
            ["git", *args], cwd=root, text=True, encoding="utf-8", stderr=subprocess.DEVNULL
        ).strip()

    def write(self, root: str, name: str, value: str):
        RELEASE.write(os.path.join(root, name), value)

    def clone(self, root: str = "") -> str:
        self.clone_count += 1
        root = root or os.path.join(self.temp.name, f"retry-{self.clone_count}")
        subprocess.run(["git", "clone", self.remote, root], check=True, capture_output=True)
        return root

    def fail_once(self, stage: str):
        if self.fail == stage:
            self.fail = ""
            raise subprocess.CalledProcessError(1, ["test-failure", stage])

    def release_git(self, root: str, *args: str) -> str:
        if args[0] == "push":
            self.fail_once("tag" if args[-1].startswith("refs/tags/") else "branch")
        return self.real_git(root, *args)

    def api(self, path: str, data: dict = None, paginate: bool = False, method: str = ""):
        self.fail_once("api")
        if path.startswith("repos/fatedier/frp/"):
            version = path.rsplit("/", 1)[-1].removeprefix("v") if "/tags/" in path else self.latest
            return {"tag_name": "v" + version, "draft": False, "prerelease": False}
        if paginate:
            return [copy.deepcopy(list(self.releases.values()))]
        if path.endswith("/generate-notes"):
            return {"body": "Release notes"}
        if data is not None and not method:
            self.assertNotIn(data["tag_name"], self.releases)
            result = data | {"id": len(self.releases) + 1, "prerelease": False}
            self.releases[data["tag_name"]] = result
            self.fail_once("create-ack")
            return copy.deepcopy(result)
        result = next(item for item in self.releases.values() if path.endswith("/" + str(item["id"])))
        if method:
            self.assertEqual(method, "PATCH")
            result.update(data)
            self.fail_once("publish-ack")
        return copy.deepcopy(result)

    def run_command(self, command: list[str], *args, **kwargs):
        if command[:3] == ["gh", "release", "upload"]:
            self.fail_once("upload")
            self.assertTrue(self.releases[command[3]]["draft"])
            self.uploads.append(command[3])
            return subprocess.CompletedProcess(command, 0)
        return self.real_run(command, *args, **kwargs)

    def update_core(self, root: str, version: str):
        config = RELEASE.read(os.path.join(root, "gradle.properties"))
        current = RELEASE.property_value(config, "FRP_VERSION")
        self.write(root, "gradle.properties", config.replace("FRP_VERSION=" + current, "FRP_VERSION=" + version))
        self.write(root, "native/go.mod", "module test\nrequire github.com/fatedier/frp v" + version + "\n")

    def build(self, root: str, plan: dict):
        RELEASE.integrate(root, plan)
        RELEASE.commit_release(root, plan)
        self.write(root, f"test-{plan['tag']}-release.apk", plan["commit"])
        self.write(root, f"test-{plan['tag']}-release.apk.sha256", "test checksum")

    def first_release(self):
        root = self.clone()
        plan = RELEASE.prepare(root, "1.0.0")
        self.assertFalse(plan["frp_changed"])
        self.build(root, plan)
        RELEASE.publish(root, plan)
        return plan

    def advance(self, version: str = "", ordinary: bool = False) -> str:
        self.git(self.writer, "pull", "--ff-only", "origin", "main")
        if version:
            config = RELEASE.read(os.path.join(self.writer, "gradle.properties"))
            config = RELEASE.replace_once(config, r"^VERSION_NAME=.+$", "VERSION_NAME=" + version)
            self.write(self.writer, "gradle.properties", config)
            self.write(self.writer, "app.txt", "app " + version + "\n")
        if ordinary:
            self.write(self.writer, "app.txt", "later unpublished change\n")
        self.git(self.writer, "commit", "-am", "app development")
        self.git(self.writer, "push", "origin", "main")
        if version:
            self.git(self.writer, "tag", version)
            self.git(self.writer, "push", "origin", "refs/tags/" + version)
        return self.git(self.writer, "rev-parse", "HEAD")

    def test_manual_release_freezes_inputs_and_recovers_each_publication_failure(self):
        self.latest = "0.72.0"
        root = self.clone()
        self.fail = "create-ack"
        with self.assertRaises(subprocess.CalledProcessError):
            RELEASE.prepare(root, "1.0.0")
        self.latest = "0.73.0"
        plan = RELEASE.prepare(root, "1.0.0")
        self.assertEqual((plan["frp"], plan["code"]), ("0.72.0", 1))
        self.assertEqual(self.git(root, "rev-parse", "HEAD"), self.base)
        self.assertEqual(RELEASE.remote_tag_commit(root, plan["tag"]), "")
        for stage in ("branch", "tag", "upload", "publish-ack"):
            with self.subTest(stage=stage):
                root = self.clone()
                plan = RELEASE.prepare(root, "1.0.0")
                self.assertTrue(plan["frp_changed"])
                self.build(root, plan)
                self.fail = stage
                with self.assertRaises(subprocess.CalledProcessError):
                    RELEASE.publish(root, plan)
                if stage == "tag":
                    expected = plan["commit"]
                    branch_head = self.advance(ordinary=True)
                if stage in ("upload", "publish-ack"):
                    self.assertEqual(plan["commit"], expected)
                    self.assertEqual(self.git(root, "ls-remote", "origin", "refs/heads/main").split()[0], branch_head)
        root = self.clone()
        final = RELEASE.prepare(root, "1.0.0")
        self.assertTrue(final["published"])
        self.assertEqual(final["commit"], expected)
        self.assertEqual(self.git(root, "rev-parse", "HEAD"), branch_head)
        self.assertEqual(RELEASE.remote_tag_commit(root, "1.0.0"), self.base)
        self.assertEqual(RELEASE.remote_tag_commit(root, final["tag"]), expected)
        self.assertEqual(self.releases[final["tag"]]["target_commitish"], expected)
        self.assertEqual(self.git(root, "show", expected + ":app.txt"), "initial app")
        self.assertEqual(len(self.releases), 1)
        self.assertEqual(len(self.uploads), 1)

    def test_upstream_retry_merges_branch_progress_and_bumps_once(self):
        self.first_release()
        self.latest = "0.72.0"
        root = self.clone()
        plan = RELEASE.prepare(root)
        self.assertEqual((plan["version"], plan["code"]), ("1.0.1", 2))
        self.build(root, plan)
        expected = plan["commit"]
        self.advance(ordinary=True)
        self.fail = "tag"
        with self.assertRaises(subprocess.CalledProcessError):
            RELEASE.publish(root, plan)
        self.latest = "0.73.0"
        root = self.clone()
        retry = RELEASE.prepare(root)
        self.assertEqual((retry["commit"], retry["frp"], retry["code"]), (expected, "0.72.0", 2))
        self.assertTrue(retry["frp_changed"])
        self.build(root, retry)
        RELEASE.publish(root, retry)
        self.assertEqual(
            self.git(root, "log", "--format=%s", "origin/main").count("🐳 chore: bump version to 1.0.1"), 1
        )
        self.assertEqual(self.git(root, "show", "origin/main:app.txt"), "later unpublished change")
        self.assertEqual(self.git(root, "show", expected + ":app.txt"), "initial app")
        self.assertIn(
            "MODULE_VERSION_NAME=v1.0\nMODULE_VERSION_CODE=1", self.git(root, "show", expected + ":gradle.properties")
        )

    def test_queued_manual_and_upstream_releases_keep_versions_and_tags_distinct(self):
        self.first_release()
        self.latest = "0.72.0"
        automatic_root = self.clone()
        automatic = RELEASE.prepare(automatic_root)
        self.build(automatic_root, automatic)
        manual_source = self.advance("1.1.0")
        manual_root = self.clone()
        with self.assertRaisesRegex(ValueError, "PENDING_RELEASE"):
            RELEASE.prepare(manual_root, "1.1.0")
        RELEASE.publish(automatic_root, automatic)
        self.assertEqual(self.git(manual_root, "ls-remote", "origin", "refs/heads/main").split()[0], manual_source)
        self.latest = "0.73.0"
        manual = RELEASE.prepare(manual_root, "1.1.0")
        self.assertEqual((manual["version"], manual["code"], manual["source"]), ("1.1.0", 3, manual_source))
        self.latest = "0.74.0"
        queued_root = self.clone()
        queued = RELEASE.prepare(queued_root)
        self.assertEqual((queued["tag"], queued["frp"], queued["code"]), (manual["tag"], "0.73.0", 3))
        self.build(queued_root, queued)
        RELEASE.publish(queued_root, queued)
        self.assertEqual(RELEASE.remote_tag_commit(queued_root, "1.1.0"), manual_source)
        self.assertEqual(self.git(queued_root, "show", queued["commit"] + ":app.txt"), "app 1.1.0")
        next_root = self.clone()
        following = RELEASE.prepare(next_root)
        self.assertEqual((following["version"], following["code"], following["frp"]), ("1.1.1", 4, "0.74.0"))
        self.build(next_root, following)
        RELEASE.publish(next_root, following)
        self.assertEqual(RELEASE.prepare(self.clone()), {})
        codes = [RELEASE.read_plan(item)["code"] for item in self.releases.values()]
        self.assertEqual(codes, [1, 2, 3, 4])

    def test_upstream_waits_for_new_app_tag_and_manual_release_uses_its_source(self):
        self.first_release()
        self.latest = "0.72.0"
        manual_source = self.advance("1.1.0")
        self.assertEqual(RELEASE.prepare(self.clone()), {})
        self.advance(ordinary=True)
        root = self.clone()
        plan = RELEASE.prepare(root, "1.1.0")
        self.build(root, plan)
        RELEASE.publish(root, plan)
        self.assertEqual(plan["source"], manual_source)
        self.assertEqual(self.git(root, "show", plan["commit"] + ":app.txt"), "app 1.1.0")
        self.assertEqual(self.git(root, "show", "origin/main:app.txt"), "later unpublished change")

    def test_conflicts_and_api_failures_preserve_remote_state(self):
        root = self.clone()
        self.fail = "api"
        with self.assertRaises(subprocess.CalledProcessError):
            RELEASE.prepare(root, "1.0.0")
        self.assertEqual(self.releases, {})
        for tag in ("1.0.0-frp0.71.1", "1.0.0-rc.1"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "INVALID_VERSION"):
                RELEASE.prepare(root, tag)
        self.latest = "0.72.0"
        plan = RELEASE.prepare(root, "1.0.0")
        self.build(root, plan)
        RELEASE.publish(root, plan)
        marker = "FRP-Mobile-Release: " + plan["tag"]
        self.git(root, "commit", "--allow-empty", "-m", f"quoted markers\n\nNotes: {marker}\n{marker}1")
        self.assertEqual(RELEASE.find_commit(root, plan), plan["commit"])
        subprocess.run(
            ["git", "--git-dir", self.remote, "update-ref", "refs/tags/" + plan["tag"], self.base], check=True
        )
        with self.assertRaisesRegex(ValueError, "RELEASE_TAG_CONFLICT"):
            RELEASE.prepare(self.clone(), "1.0.0")
        self.assertEqual(RELEASE.remote_tag_commit(root, plan["tag"]), self.base)

    def test_retry_in_same_checkout_pushes_a_local_tag_left_by_failed_push(self):
        self.latest = "0.72.0"
        root = self.clone()
        plan = RELEASE.prepare(root, "1.0.0")
        self.build(root, plan)
        self.fail = "tag"
        with self.assertRaises(subprocess.CalledProcessError):
            RELEASE.publish(root, plan)
        self.assertEqual(RELEASE.tag_commit(root, plan["tag"]), plan["commit"])
        self.assertEqual(RELEASE.remote_tag_commit(root, plan["tag"]), "")
        RELEASE.publish(root, plan)
        self.assertEqual(RELEASE.remote_tag_commit(root, plan["tag"]), plan["commit"])
        self.assertFalse(self.releases[plan["tag"]]["draft"])


if __name__ == "__main__":
    unittest.main()
