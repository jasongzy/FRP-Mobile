import os
import runpy
import tempfile
import unittest

BUILD = runpy.run_path(os.path.join(os.path.dirname(__file__), "build-native.py"))


class PatchTest(unittest.TestCase):
    def test_repeated_build_and_upstream_conflict(self):
        with tempfile.TemporaryDirectory(dir=BUILD["ROOT"]) as directory:
            upstream = os.path.join(directory, "upstream")
            nested = os.path.join(upstream, "client")
            os.makedirs(nested)
            cached = os.path.join(nested, "client.go")
            with open(cached, "w", encoding="utf-8", newline="\n") as file:
                file.write("hostname := old()\n")
            os.chmod(cached, 0o444)
            os.chmod(nested, 0o555)
            os.chmod(upstream, 0o555)
            source_dir = os.path.join(directory, "source")
            try:
                BUILD["copy_source"](upstream, source_dir)
            finally:
                os.chmod(upstream, 0o755)
                os.chmod(nested, 0o755)
                os.chmod(cached, 0o644)
            source = os.path.join(source_dir, "client", "client.go")
            patch = os.path.join(directory, "hostname.patch")
            with open(patch, "w", encoding="utf-8", newline="\n") as file:
                file.write(
                    "--- a/client/client.go\n+++ b/client/client.go\n@@ -1 +1 @@\n"
                    "-hostname := old()\n+hostname := android()\n"
                )
            BUILD["apply_patch"](source_dir, patch)
            BUILD["apply_patch"](source_dir, patch)
            with open(source, encoding="utf-8") as file:
                self.assertEqual("hostname := android()\n", file.read())
            with open(source, "w", encoding="utf-8", newline="\n") as file:
                file.write("hostname := changed_upstream()\n")
            with self.assertRaisesRegex(RuntimeError, "FRP_PATCH_MISMATCH"):
                BUILD["apply_patch"](source_dir, patch)
            with open(source, encoding="utf-8") as file:
                self.assertEqual("hostname := changed_upstream()\n", file.read())


if __name__ == "__main__":
    unittest.main()
