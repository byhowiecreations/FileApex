#!/usr/bin/env python3
"""Fail the tsnet build when go.mod is behind the latest stable Tailscale module.

Also lets Android start when the process path is hidden, and prints the linker
flags that stamp the embedded client as that stable version.
"""
import pathlib
import re
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
GO_MOD = ROOT / "go.mod"


def stable_versions(listing: str) -> list[str]:
    found = [token for token in listing.split() if re.fullmatch(r"v\d+\.\d+\.\d+", token)]
    return sorted(set(found), key=lambda version: tuple(int(part) for part in version[1:].split(".")))


def pinned_version() -> str:
    match = re.search(r"(?m)^require tailscale\.com (v\d+\.\d+\.\d+)\s*$", GO_MOD.read_text())
    if not match:
        sys.exit("go.mod has no stable tailscale.com require")
    return match.group(1)


def latest_stable() -> str:
    with urllib.request.urlopen("https://proxy.golang.org/tailscale.com/@v/list", timeout=30) as response:
        listing = response.read().decode()
    versions = stable_versions(listing)
    if not versions:
        sys.exit("proxy.golang.org returned no stable tailscale.com versions")
    return versions[-1]


def module_dir(go: str) -> pathlib.Path:
    listed = subprocess.run(
        [go, "list", "-m", "-f", "{{.Dir}}", "tailscale.com"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return pathlib.Path(listed.stdout.strip())


def replace_once(source: pathlib.Path, current: str, updated: str, missing: str) -> None:
    text = source.read_text()
    if updated in text:
        return
    if current not in text:
        sys.exit(missing)
    source.chmod(source.stat().st_mode | 0o200)
    source.write_text(text.replace(current, updated, 1))


def patch_android_executable(module: pathlib.Path) -> None:
    replace_once(
        module / "tsnet" / "tsnet.go",
        'case "ios", "darwin":',
        'case "ios", "darwin", "android":',
        "tsnet start() no longer matches the Android executable fallback",
    )


def patch_darwin_unbind(module: pathlib.Path) -> None:
    # A netmap clears this flag unless the tailnet has a debug capability.
    # On Darwin that puts IP_BOUND_IF back on the UDP socket, and Local Network
    # privacy then drops packets to a same-LAN peer. Public DERP is unaffected.
    replace_once(
        module / "ipn" / "ipnlocal" / "local.go",
        "netns.SetDisableBindConnToInterface(b.logf, nm.HasCap(nodecap.DebugDisableBindConnToInterface))",
        'netns.SetDisableBindConnToInterface(b.logf, runtime.GOOS == "darwin" || nm.HasCap(nodecap.DebugDisableBindConnToInterface))',
        "local.go no longer sets disableBindConnToInterface from the netmap",
    )


def main() -> None:
    go = sys.argv[1] if len(sys.argv) > 1 else "go"
    pinned = pinned_version()
    latest = latest_stable()
    if pinned != latest:
        sys.exit(
            f"go.mod pins tailscale.com {pinned}; latest stable is {latest}. "
            "Update the module before building the node."
        )
    module = module_dir(go)
    patch_android_executable(module)
    patch_darwin_unbind(module)
    stamp = pinned[1:]
    print(
        f"-X tailscale.com/version.longStamp={stamp} "
        f"-X tailscale.com/version.shortStamp={stamp}"
    )


if __name__ == "__main__":
    main()
