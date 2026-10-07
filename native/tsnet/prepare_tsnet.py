#!/usr/bin/env python3
"""Warn when go.mod is not on the stable release Tailscale publishes.

The pin is deliberate: this script patches Tailscale's source, so a bump needs those patches
rechecked and tailnet transfers retested. Set FILEAPEX_REQUIRE_LATEST_TSNET=1 to fail instead.

Also lets Android start when the process path is hidden, and prints the linker
flags that stamp the embedded client as that stable version.
"""
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
GO_MOD = ROOT / "go.mod"


def pinned_version() -> str:
    match = re.search(r"(?m)^(?:require )?\s*tailscale\.com (v\d+\.\d+\.\d+)\s*$", GO_MOD.read_text())
    if not match:
        sys.exit("go.mod has no stable tailscale.com require")
    return match.group(1)


def latest_stable() -> str:
    # The Go proxy lists every tag, including ones ahead of the stable channel.
    with urllib.request.urlopen("https://pkgs.tailscale.com/stable/?mode=json", timeout=30) as response:
        version = json.load(response).get("TarballsVersion", "")
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        sys.exit("pkgs.tailscale.com returned no stable version")
    return "v" + version


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
    source = module / "ipn" / "ipnlocal" / "local.go"
    for capability in ("tailcfg.CapabilityDebugDisableBindConnToInterface", "nodecap.DebugDisableBindConnToInterface"):
        current = f"netns.SetDisableBindConnToInterface(b.logf, nm.HasCap({capability}))"
        text = source.read_text()
        if current in text or f'runtime.GOOS == "darwin" || nm.HasCap({capability})' in text:
            replace_once(
                source,
                current,
                f'netns.SetDisableBindConnToInterface(b.logf, runtime.GOOS == "darwin" || nm.HasCap({capability}))',
                "unreachable",
            )
            return
    sys.exit("local.go no longer sets disableBindConnToInterface from the netmap")


def main() -> None:
    go = sys.argv[1] if len(sys.argv) > 1 else "go"
    pinned = pinned_version()
    try:
        latest = latest_stable()
    except (OSError, ValueError) as error:
        print(f"Could not check for a newer tailscale.com ({error}); building {pinned}.", file=sys.stderr)
        latest = pinned
    if pinned != latest:
        message = (
            f"go.mod pins tailscale.com {pinned}; official stable is {latest}. "
            "Bump deliberately: update go.mod, rebuild so the source patches are rechecked, "
            "then retest tailnet transfers."
        )
        if os.environ.get("FILEAPEX_REQUIRE_LATEST_TSNET") == "1":
            sys.exit(message)
        print(f"WARNING: {message}", file=sys.stderr)
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
