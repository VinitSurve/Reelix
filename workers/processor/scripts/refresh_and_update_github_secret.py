#!/usr/bin/env python3
"""One-step script to refresh Google Drive OAuth token and update GitHub Actions repository secrets automatically.

Reads the desktop OAuth client credentials, runs the local OAuth consent flow in the browser,
verifies the token against Google Drive API, and uploads the fresh GDRIVE_REFRESH_TOKEN directly
to GitHub repository secrets (VinitSurve/Reelix) via the GitHub REST API.
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Optional

from google_auth_oauthlib.flow import InstalledAppFlow
from nacl import encoding, public
import requests

SCOPES = ["https://www.googleapis.com/auth/drive.file"]
CLIENT_SECRET_FILE = Path("/Users/vinit/Vinit/Reelix/client_secret_692598999383-8se4rmj10q26th351q6uv7bchd4qlae1.apps.googleusercontent.com.json")
REPO = "VinitSurve/Reelix"


def get_github_token() -> Optional[str]:
    """Retrieves GitHub token from git credential helper or environment."""
    if os.getenv("GITHUB_TOKEN"):
        return os.environ["GITHUB_TOKEN"]
    try:
        proc = subprocess.run(
            ["git", "credential", "fill"],
            input="protocol=https\nhost=github.com\n\n",
            capture_output=True,
            text=True,
            check=True,
        )
        for line in proc.stdout.splitlines():
            if line.startswith("password="):
                return line.split("=", 1)[1].strip()
    except Exception as e:
        print(f"Warning: Could not fetch GitHub token via git credential: {e}")
    return None


def encrypt_secret(public_key_b64: str, secret_value: str) -> str:
    """Encrypts secret using repository public key with libsodium sealed box."""
    public_key = public.PublicKey(public_key_b64.encode("utf-8"), encoding.Base64Encoder)
    sealed_box = public.SealedBox(public_key)
    encrypted = sealed_box.encrypt(secret_value.encode("utf-8"))
    return base64.b64encode(encrypted).decode("utf-8")


def update_github_secret(github_token: str, secret_name: str, secret_value: str) -> bool:
    """Fetches repo public key and updates secret via GitHub REST API."""
    headers = {
        "Authorization": f"Bearer {github_token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    # 1. Get repository public key
    pk_url = f"https://api.github.com/repos/{REPO}/actions/secrets/public-key"
    res = requests.get(pk_url, headers=headers)
    if res.status_code != 200:
        print(f"Error fetching repo public key: HTTP {res.status_code}: {res.text}")
        return False
    pk_data = res.json()
    key_id = pk_data["key_id"]
    public_key = pk_data["key"]

    # 2. Encrypt secret
    encrypted_value = encrypt_secret(public_key, secret_value)

    # 3. Put secret
    secret_url = f"https://api.github.com/repos/{REPO}/actions/secrets/{secret_name}"
    put_data = {
        "encrypted_value": encrypted_value,
        "key_id": key_id,
    }
    put_res = requests.put(secret_url, headers=headers, json=put_data)
    if put_res.status_code in (201, 204):
        print(f"✓ Successfully updated GitHub secret '{secret_name}' in {REPO} [HTTP {put_res.status_code}]")
        return True
    else:
        print(f"Error updating secret '{secret_name}': HTTP {put_res.status_code}: {put_res.text}")
        return False


def main():
    print("=================================================================")
    print("  Reelix Google Drive Token Refresh & Automatic GitHub Update")
    print("=================================================================")

    if not CLIENT_SECRET_FILE.exists():
        print(f"Error: Client secret file not found: {CLIENT_SECRET_FILE}")
        sys.exit(1)

    with open(CLIENT_SECRET_FILE, "r") as f:
        client_data = json.load(f)

    app_type = list(client_data.keys())[0]
    client_id = client_data[app_type]["client_id"]
    client_secret = client_data[app_type]["client_secret"]

    print(f"Loaded client credentials (Client ID: {client_id[:12]}...)")
    print("Launching browser for Google OAuth authorization...")

    flow = InstalledAppFlow.from_client_secrets_file(str(CLIENT_SECRET_FILE), scopes=SCOPES)
    try:
        creds = flow.run_local_server(port=0, prompt="consent", access_type="offline")
    except Exception as e:
        print(f"Browser launch failed: {e}. Falling back to console flow...")
        creds = flow.run_console()

    if not creds.refresh_token:
        print("ERROR: Google did not return a refresh_token.")
        print("Ensure prompt='consent' and access_type='offline' are specified.")
        sys.exit(1)

    print("\n✓ Google Drive OAuth 2.0 Refresh Token obtained successfully!")

    # Verify against Google Drive API
    from googleapiclient.discovery import build
    service = build("drive", "v3", credentials=creds, cache_discovery=False)
    about = service.about().get(fields="user(displayName, emailAddress)").execute()
    user_info = about.get("user", {})
    print(f"✓ Verified Google Drive access for: {user_info.get('displayName')} ({user_info.get('emailAddress')})")

    # Update GitHub secret
    gh_token = get_github_token()
    if not gh_token:
        print("\nCould not automatically get GitHub token.")
        print("Please update secret manually in GitHub settings:")
        print(f"GDRIVE_REFRESH_TOKEN = {creds.refresh_token}")
        return

    print("\nUpdating GDRIVE_REFRESH_TOKEN in GitHub Actions repository secrets...")
    updated = update_github_secret(gh_token, "GDRIVE_REFRESH_TOKEN", creds.refresh_token)
    if updated:
        print("\n=================================================================")
        print("  DONE! Reelix worker authentication is fully restored!")
        print("=================================================================")
    else:
        print("Could not update GitHub secret automatically. Please set it manually.")


if __name__ == "__main__":
    main()
