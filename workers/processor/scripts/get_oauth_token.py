#!/usr/bin/env python3
"""Helper script to generate a Google Drive OAuth 2.0 Refresh Token for personal My Drive.

Usage:
    python -m workers.processor.scripts.get_oauth_token
    or:
    python workers/processor/scripts/get_oauth_token.py

Requirements:
    pip install google-auth-oauthlib
"""

import sys

try:
    from google_auth_oauthlib.flow import InstalledAppFlow
except ImportError:
    print("Error: google-auth-oauthlib is required.")
    print("Install it with: pip install google-auth-oauthlib")
    sys.exit(1)

SCOPES = ["https://www.googleapis.com/auth/drive.file"]


def main():
    print("=================================================================")
    print("   Reelix Google Drive OAuth 2.0 Refresh Token Setup")
    print("=================================================================")
    print("This script helps you generate a refresh token for personal My Drive.")
    print("Make sure you have created an OAuth 2.0 Client ID (Desktop app) in")
    print("Google Cloud Console (https://console.cloud.google.com/).\n")

    client_id = input("Enter your OAuth Client ID: ").strip()
    client_secret = input("Enter your OAuth Client Secret: ").strip()

    if not client_id or not client_secret:
        print("Error: Both Client ID and Client Secret are required.")
        sys.exit(1)

    client_config = {
        "installed": {
            "client_id": client_id,
            "client_secret": client_secret,
            "auth_uri": "https://accounts.google.com/o/oauth2/auth",
            "token_uri": "https://oauth2.googleapis.com/token",
            "redirect_uris": ["urn:ietf:wg:oauth:2.0:oob", "http://localhost"],
        }
    }

    flow = InstalledAppFlow.from_client_config(client_config, scopes=SCOPES)
    # Run local server flow or console flow
    try:
        creds = flow.run_local_server(port=0, prompt="consent", access_type="offline")
    except Exception as e:
        print(f"Could not open local browser: {e}. Falling back to console flow...")
        creds = flow.run_console()

    print("\n=================================================================")
    print("   SUCCESS! Google Drive OAuth 2.0 Credentials Obtained")
    print("=================================================================")
    print(f"GDRIVE_CLIENT_ID:      {client_id}")
    print(f"GDRIVE_CLIENT_SECRET:  {client_secret}")
    print(f"GDRIVE_REFRESH_TOKEN:  {creds.refresh_token}")

    # Discover or create authorized root Reelix_Vault folder under drive.file scope
    vault_id = None
    try:
        from googleapiclient.discovery import build
        service = build("drive", "v3", credentials=creds, cache_discovery=False)
        q = "name = 'Reelix_Vault' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false"
        res = service.files().list(q=q, fields="files(id, name)").execute()
        files = res.get("files", [])
        if files:
            vault_id = files[0]["id"]
            print(f"GDRIVE_FOLDER_ID:      {vault_id} (found existing Reelix_Vault)")
        else:
            meta = {
                "name": "Reelix_Vault",
                "mimeType": "application/vnd.google-apps.folder",
                "parents": ["root"],
            }
            folder = service.files().create(body=meta, fields="id").execute()
            vault_id = folder["id"]
            print(f"GDRIVE_FOLDER_ID:      {vault_id} (created new Reelix_Vault under 'root')")
    except Exception as e:
        print(f"Note: Could not automatically create Reelix_Vault folder ({e}).")
        print("Reelix will automatically create it upon first worker run.")

    print("=================================================================")
    print("Add these 4 values to your GitHub Repository Secrets:")
    print(" - GDRIVE_CLIENT_ID")
    print(" - GDRIVE_CLIENT_SECRET")
    print(" - GDRIVE_REFRESH_TOKEN")
    if vault_id:
        print(f" - GDRIVE_FOLDER_ID (value: {vault_id})")
    else:
        print(" - GDRIVE_FOLDER_ID")


if __name__ == "__main__":
    main()

