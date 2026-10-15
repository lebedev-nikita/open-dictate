import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest


class GoogleDriveConfigurationTests(unittest.TestCase):
    client = {"client_id": "synthetic.apps.googleusercontent.com", "client_secret": "synthetic-client-secret"}

    def run_configure(self, directory, *, exported="", source="", required=False):
        info = directory / "Info.plist"
        info.write_bytes(plistlib.dumps({"CFBundleName": "OpenDictate"}))
        env = dict(os.environ, GOOGLE_DRIVE_OAUTH_CLIENT_JSON=exported, GOOGLE_DRIVE_OAUTH_CLIENT_FILE=source)
        command = [sys.executable, str(Path(__file__).with_name("configure-google-drive.py")), str(info)]
        if required:
            command.append("--required")
        result = subprocess.run(command, env=env, capture_output=True, text=True)
        self.assertNotIn(self.client["client_secret"], result.stdout + result.stderr)
        return result, plistlib.loads(info.read_bytes())

    def test_installed_client_file_is_packaged_without_unrelated_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source = directory / "client.json"
            source.write_text(json.dumps({"installed": dict(self.client, project_id="synthetic", refresh_token="must-not-be-packaged")}))
            result, info = self.run_configure(directory, source=str(source), required=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(info, {"CFBundleName": "OpenDictate", "OpenDictateGoogleDriveClient": self.client})

    def test_repository_secret_json_is_packaged(self):
        with tempfile.TemporaryDirectory() as temporary:
            result, info = self.run_configure(Path(temporary), exported=json.dumps({"installed": self.client}), required=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(info["OpenDictateGoogleDriveClient"], self.client)

    def test_missing_configuration_is_allowed_locally_but_blocks_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result, info = self.run_configure(directory)
            self.assertEqual(result.returncode, 0)
            self.assertNotIn("OpenDictateGoogleDriveClient", info)
            result, info = self.run_configure(directory, required=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("OpenDictateGoogleDriveClient", info)

    def test_invalid_and_web_clients_are_rejected_without_leaking_configuration(self):
        for document in ["not json", {"web": self.client}, {"installed": {"client_id": self.client["client_id"]}},
                         {"installed": dict(self.client, client_secret=" ")}, {"installed": dict(self.client, client_id="invalid")}]:
            with self.subTest(document_type=type(document).__name__), tempfile.TemporaryDirectory() as temporary:
                exported = document if isinstance(document, str) else json.dumps(document)
                result, info = self.run_configure(Path(temporary), exported=exported)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("OpenDictateGoogleDriveClient", info)

    def test_ambiguous_or_unreadable_sources_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for exported, source in [(json.dumps({"installed": self.client}), "also-specified.json"),
                                     ("", str(directory / "missing.json"))]:
                result, _ = self.run_configure(directory, exported=exported, source=source)
                self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
