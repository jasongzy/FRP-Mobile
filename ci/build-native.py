import os
import shutil
import subprocess
import sys

from tqdm import tqdm

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGETS = {
    "arm64-v8a": ("arm64", "aarch64-linux-android"),
    "armeabi-v7a": ("arm", "armv7a-linux-androideabi"),
    "x86_64": ("amd64", "x86_64-linux-android"),
}


def properties(path: str) -> dict[str, str]:
    with open(path, encoding="utf-8-sig") as file:
        return dict(line.strip().split("=", 1) for line in file if "=" in line and not line.startswith("#"))


def copy_source(upstream: str, source: str):
    shutil.copytree(upstream, source)
    for directory, _, files in os.walk(source):
        os.chmod(directory, 0o755)
        for name in files:
            os.chmod(os.path.join(directory, name), 0o644)


def apply_patch(source: str, patch: str):
    directory = os.path.relpath(source, ROOT).replace(os.sep, "/")
    command = ["git", "apply", "--whitespace=error-all", "--directory=" + directory]
    check = subprocess.run(command + ["--check", patch], cwd=ROOT, capture_output=True, text=True)
    if check.returncode == 0:
        subprocess.run(command + [patch], cwd=ROOT, check=True)
    elif subprocess.run(command + ["--reverse", "--check", patch], cwd=ROOT, capture_output=True).returncode != 0:
        raise RuntimeError(
            f"FRP_PATCH_MISMATCH: {os.path.basename(patch)} does not match {source}.\n"
            f"Review the upstream changes and update the patch before building.\n{check.stderr}"
        )
    print(f"Verified FRP patch: {os.path.basename(patch)}", flush=True)


if __name__ == "__main__":
    config = properties(os.path.join(ROOT, "gradle.properties"))
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sdk = properties(os.path.join(ROOT, "local.properties"))["sdk.dir"].replace("\\:", ":").replace("\\\\", "\\")
    ndk = os.path.join(sdk, "ndk", "30.0.16248370")
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    clang_dir = os.path.join(ndk, "toolchains", "llvm", "prebuilt", host, "bin")
    go = shutil.which("go")
    assert go, "Install the Go version specified in gradle.properties"
    native = os.path.join(ROOT, "native")
    actual_go = subprocess.check_output([go, "env", "GOVERSION"], cwd=native, text=True).strip()
    assert actual_go == "go" + config["GO_VERSION"], "GO_VERSION and the active Go toolchain must match"
    subprocess.run([go, "mod", "download"], cwd=native, check=True)
    version = subprocess.check_output(
        [go, "list", "-m", "-f", "{{.Version}}", "github.com/fatedier/frp"], cwd=native, text=True
    ).strip()
    assert version == "v" + config["FRP_VERSION"], "FRP_VERSION and go.mod must match"
    tools_dir = os.path.join(native, ".tools")
    source = os.path.join(tools_dir, "frp-" + version)
    if not os.path.isdir(source):
        upstream = subprocess.check_output(
            [go, "list", "-m", "-f", "{{.Dir}}", "github.com/fatedier/frp"], cwd=native, text=True
        ).strip()
        copy_source(upstream, source)
    apply_patch(source, os.path.join(ROOT, "ci", "patches", "android-hostname.patch"))
    web = os.path.join(source, "web")
    if not all(os.path.isdir(os.path.join(web, name, "dist")) for name in ("frpc", "frps")):
        npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
        assert npm, "Node.js and npm are required to build the official dashboards"
        subprocess.run([npm, "ci", "--ignore-scripts"], cwd=web, check=True)
        for name in ("frpc", "frps"):
            subprocess.run([npm, "run", "build", "--workspace", name], cwd=web, check=True)
    modfile = os.path.join(tools_dir, "build.mod")
    shutil.copyfile(os.path.join(native, "go.mod"), modfile)
    shutil.copyfile(os.path.join(native, "go.sum"), os.path.join(tools_dir, "build.sum"))
    subprocess.run(
        [go, "mod", "edit", "-modfile=" + modfile, "-replace=github.com/fatedier/frp=./.tools/frp-" + version],
        cwd=native,
        check=True,
    )
    fields = subprocess.check_output(
        [go, "run", "-mod=readonly", "-modfile=" + modfile, "./cmd/config-fields", source], cwd=native
    )
    assets = os.path.join(ROOT, "app", "src", "main", "assets")
    os.makedirs(assets, exist_ok=True)
    with open(os.path.join(assets, "frp-fields.json"), "wb") as file:
        file.write(fields)
    for abi, (arch, target) in tqdm(TARGETS.items(), desc="Android cores", dynamic_ncols=True):
        compiler = os.path.join(clang_dir, target + "24-clang" + (".cmd" if os.name == "nt" else ""))
        assert os.path.isfile(compiler), compiler
        env = dict(os.environ, GOOS="android", GOARCH=arch, CGO_ENABLED="1", CC=compiler, GOARM="7")
        output = os.path.join(ROOT, "app", "src", "main", "jniLibs", abi)
        os.makedirs(output, exist_ok=True)
        for name, package in (
            ("frpc", "github.com/fatedier/frp/cmd/frpc"),
            ("frps", "github.com/fatedier/frp/cmd/frps"),
            ("frpmobile", "."),
        ):
            subprocess.run(
                [
                    go,
                    "build",
                    "-mod=readonly",
                    "-modfile=" + modfile,
                    "-trimpath",
                    "-buildmode=pie",
                    "-ldflags=-s -w -X main.moduleVersionName="
                    + config["MODULE_VERSION_NAME"]
                    + " -X main.moduleVersionCode="
                    + config["MODULE_VERSION_CODE"]
                    + " -X main.frpVersion="
                    + config["FRP_VERSION"],
                    "-o",
                    os.path.join(output, "lib" + name + ".so"),
                    package,
                ],
                cwd=native,
                env=env,
                check=True,
            )
    subprocess.run(
        [
            sys.executable,
            os.path.join(ROOT, "ci", "verify-apk.py"),
            os.path.join(ROOT, "app", "src", "main", "jniLibs"),
        ],
        check=True,
    )
