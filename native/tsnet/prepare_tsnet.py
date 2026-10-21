#!/usr/bin/env python3
"""Warn when go.mod is not on the stable release Tailscale publishes.

The pin is deliberate: this script patches Tailscale's source, so a bump needs those patches
rechecked and tailnet transfers retested. Set FILEAPEX_REQUIRE_LATEST_TSNET=1 to fail instead.

The patches go on a copy of the module under build/tsnet-patched, never on the shared Go
module cache: the cache is read-only and shared with every other Go build on the machine.
An alternate go.mod (tsnet.mod, with its tsnet.sum beside it) replaces tailscale.com with
that copy; the build scripts point the Go tool at it with -modfile. gomobile accepts no
alternate go.mod, so the Android script instead binds from a copy of this module whose
go.mod carries the same replace.

Also lets Android start when the process path is hidden, and prints the linker
flags that stamp the embedded client as that stable version.
"""
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
GO_MOD = ROOT / "go.mod"
PATCHED_ROOT = ROOT / "build" / "tsnet-patched"
PATCHED_MODULE = PATCHED_ROOT / "tailscale.com"
MODFILE = PATCHED_ROOT / "tsnet.mod"


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


def patched_copy(module: pathlib.Path, patched_root: pathlib.Path = PATCHED_ROOT) -> pathlib.Path:
    """Copy the module out of the cache, patch the copy, and return it. The cache is left alone."""
    copy = patched_root / "tailscale.com"
    marker = patched_root / ".source"
    origin = str(module)
    if copy.exists() and marker.exists() and marker.read_text().strip() == origin:
        try:
            patch_android_executable(copy)
            patch_darwin_unbind(copy)
            return copy
        except SystemExit:
            pass  # Stale or half-written copy: make it again below.
    if patched_root.exists():
        # Module cache trees are read-only, directories included; make the old copy removable.
        for path in sorted(patched_root.rglob("*"), reverse=True):
            if not path.is_symlink():
                path.chmod(path.stat().st_mode | 0o200)
        patched_root.chmod(patched_root.stat().st_mode | 0o200)
        shutil.rmtree(patched_root)
    patched_root.mkdir(parents=True)
    shutil.copytree(module, copy, symlinks=True)
    # Module cache files are read-only; the copy is ours to edit and rebuild from.
    for path in copy.rglob("*"):
        if not path.is_symlink():
            path.chmod(path.stat().st_mode | 0o200)
    patch_android_executable(copy)
    patch_darwin_unbind(copy)
    marker.write_text(origin + "\n")
    return copy


def write_modfile(go_mod: pathlib.Path, copy: pathlib.Path, patched_root: pathlib.Path = PATCHED_ROOT) -> pathlib.Path:
    """Write the alternate go.mod/go.sum that build against the patched copy."""
    modfile = patched_root / "tsnet.mod"
    text = go_mod.read_text()
    if "replace tailscale.com" not in text:
        # An absolute path: the Go tool reads a replace in an alternate go.mod relative to
        # the main module directory, not to the alternate file's folder.
        text = text.rstrip() + f"\n\nreplace tailscale.com => {copy.resolve()}\n"
    modfile.write_text(text)
    go_sum = go_mod.with_name("go.sum")
    if go_sum.exists():
        shutil.copyfile(go_sum, patched_root / "tsnet.sum")
    return modfile


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
    copy = patched_copy(module_dir(go))
    write_modfile(GO_MOD, copy)
    stamp = pinned[1:]
    print(
        f"-X tailscale.com/version.longStamp={stamp} "
        f"-X tailscale.com/version.shortStamp={stamp}"
    )


if __name__ == "__main__":
    main()
