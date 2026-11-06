#!/usr/bin/env python3
"""Writes the licence notices of every crate compiled into the browser client, read from the sources cargo already fetched.

Usage: licenses.py <output file>. Exits 1 and names the crate when one has no licence text, so a bundle is never published without notices.
"""
import json
import pathlib
import re
import subprocess
import sys

NAME = re.compile(r"^(LICEN[CS]E|COPYING|NOTICE|UNLICENSE)", re.IGNORECASE)

MIT_TEMPLATE = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to
whom the Software is furnished to do so, subject to the following conditions: The above copyright notice and this permission
notice shall be included in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT
WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""


def main(out):
    meta = json.loads(subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--filter-platform", "wasm32-unknown-unknown", "--locked"],
        check=True, capture_output=True, text=True).stdout)
    used = {node["id"] for node in meta["resolve"]["nodes"]}
    crates = sorted((p for p in meta["packages"] if p["id"] in used and p["name"] != "dropweb"), key=lambda p: (p["name"], p["version"]))
    missing = []
    standard = {}
    for p in crates:
        for t in pathlib.Path(p["manifest_path"]).parent.iterdir():
            key = {"LICENSE-APACHE": "Apache-2.0", "UNLICENSE": "Unlicense"}.get(t.name)
            if key and key not in standard:
                standard[key] = t.read_text(encoding="utf-8", errors="replace").strip()
    with open(out, "w", encoding="utf-8") as f:
        f.write("Third-party software in the FileApex download page (WebAssembly client)\n")
        f.write("Each crate keeps its own licence. Source: https://crates.io, versions as locked in native/dropweb/Cargo.lock.\n\n")
        for p in crates:
            root = pathlib.Path(p["manifest_path"]).parent
            texts = sorted(x for x in root.iterdir() if x.is_file() and NAME.match(x.name))
            expression = p.get("license") or ""
            known = [k for k in ("MIT", "Apache-2.0", "Unlicense") if k in expression]
            if not texts and not known:
                missing.append(f"{p['name']} {p['version']}")
            f.write("=" * 78 + "\n")
            f.write(f"{p['name']} {p['version']}\nLicence: {p.get('license') or p.get('license_file') or 'see below'}\n")
            if p.get("repository"):
                f.write(f"Source: {p['repository']}\n")
            for t in texts:
                f.write(f"\n--- {t.name} ---\n")
                f.write(t.read_text(encoding="utf-8", errors="replace").strip() + "\n")
            if not texts and known:
                authors = ", ".join(p.get("authors") or []) or "the authors named in the repository"
                f.write(f"\nThis crate publishes no licence file. Copyright: {authors}. The notice is in the repository above.\n")
                if "MIT" in known:
                    f.write(f"\nMIT licence, copyright (c) {authors}:\n{MIT_TEMPLATE}\n")
                if "Apache-2.0" in known and "Apache-2.0" in standard:
                    f.write("\nApache License 2.0: the full text is reproduced once below (Apache-2.0 standard text).\n")
                if "Unlicense" in known and "Unlicense" in standard:
                    f.write(f"\nThe Unlicense:\n{standard['Unlicense']}\n")
            f.write("\n")
        if "Apache-2.0" in standard:
            f.write("=" * 78 + "\nApache License 2.0 (standard text)\n\n" + standard["Apache-2.0"] + "\n")
    if missing:
        print("no licence text found for: " + ", ".join(missing), file=sys.stderr)
        return 1
    print(f"wrote {out} for {len(crates)} crates")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]) if len(sys.argv) == 2 else print(__doc__) or 2)
