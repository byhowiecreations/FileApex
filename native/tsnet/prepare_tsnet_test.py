#!/usr/bin/env python3
import pathlib
import tempfile
import unittest

import prepare_tsnet


def fake_module(root: pathlib.Path) -> pathlib.Path:
    module = root / "cache" / "tailscale.com@v0"
    (module / "tsnet").mkdir(parents=True)
    (module / "ipn" / "ipnlocal").mkdir(parents=True)
    (module / "tsnet" / "tsnet.go").write_text('switch {\ncase "ios", "darwin":\n}\n')
    (module / "ipn" / "ipnlocal" / "local.go").write_text(
        "netns.SetDisableBindConnToInterface(b.logf, nm.HasCap(tailcfg.CapabilityDebugDisableBindConnToInterface))\n"
    )
    return module


class PatchedCopyTest(unittest.TestCase):
    def test_patches_a_copy_and_leaves_the_module_cache_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            module = fake_module(root)
            before = {p: p.read_text() for p in module.rglob("*.go")}

            copy = prepare_tsnet.patched_copy(module, root / "patched")

            for path, text in before.items():
                self.assertEqual(text, path.read_text(), f"source module changed: {path}")
            self.assertIn('"android"', (copy / "tsnet" / "tsnet.go").read_text())
            self.assertIn('runtime.GOOS == "darwin"', (copy / "ipn" / "ipnlocal" / "local.go").read_text())

    def test_recopies_when_the_module_source_moves(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            first = fake_module(root / "a")
            second = fake_module(root / "b")
            patched = root / "patched"
            prepare_tsnet.patched_copy(first, patched)
            copy = prepare_tsnet.patched_copy(second, patched)
            self.assertIn('"android"', (copy / "tsnet" / "tsnet.go").read_text())
            self.assertEqual(str(second), (patched / ".source").read_text().strip())

    def test_modfile_replaces_tailscale_with_the_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            module = fake_module(root)
            copy = prepare_tsnet.patched_copy(module, root / "patched")
            go_mod = root / "go.mod"
            go_mod.write_text("module fileapex.dev/tsnetbridge\n\ngo 1.27.1\n\nrequire tailscale.com v1.102.5\n")
            (root / "go.sum").write_text("tailscale.com v1.102.5 h1:example=\n")

            modfile = prepare_tsnet.write_modfile(go_mod, copy, root / "patched")

            text = modfile.read_text()
            self.assertIn(f"replace tailscale.com => {copy.resolve()}", text)
            self.assertTrue((modfile.parent / "tsnet.sum").exists())
            self.assertNotIn("replace", go_mod.read_text())


if __name__ == "__main__":
    unittest.main()
