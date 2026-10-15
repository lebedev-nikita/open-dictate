#!/usr/bin/env python3
"""Validate an installed-app OAuth client and include only its client configuration."""

import argparse
import json
import os
from pathlib import Path
import plistlib
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("info_plist", nargs="?")
    parser.add_argument("--required", action="store_true")
    args = parser.parse_args()
    exported = os.environ.get("GOOGLE_DRIVE_OAUTH_CLIENT_JSON", "")
    source = os.environ.get("GOOGLE_DRIVE_OAUTH_CLIENT_FILE", "")
    if exported and source:
        raise ValueError("Supply only one Google Drive OAuth configuration source.")
    if not exported and not source:
        if args.required:
            raise ValueError("Google Drive Desktop OAuth configuration is required for a release.")
        return
    try:
        document = json.loads(Path(source).read_text() if source else exported)
        installed = document["installed"]
        client_id, client_secret = installed["client_id"], installed["client_secret"]
        if not isinstance(client_id, str) or not isinstance(client_secret, str):
            raise ValueError()
        client_id, client_secret = client_id.strip(), client_secret.strip()
        if not client_id.endswith(".apps.googleusercontent.com") or any(c.isspace() for c in client_id) or not client_secret:
            raise ValueError()
    except (OSError, UnicodeError, ValueError, KeyError, TypeError):
        # Never include client configuration or provider responses in diagnostics.
        raise ValueError("Invalid Google Drive Desktop OAuth JSON; download the installed-app client configuration.") from None
    if args.info_plist:
        path = Path(args.info_plist)
        info = plistlib.loads(path.read_bytes())
        info["OpenDictateGoogleDriveClient"] = {"client_id": client_id, "client_secret": client_secret}
        path.write_bytes(plistlib.dumps(info, sort_keys=False))


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
