# Reelix — Personal Instagram Knowledge Vault & Content Intelligence (Android)

Reelix is a single-user, local-first Android knowledge vault for capturing, organizing, enriching, transcribing, and searching informative Instagram Reels, posts, and shared media files.

**100% free to operate**: no subscriptions, no payment cards, no billing accounts, no third-party cloud lock-in, and zero scraping of unofficial Instagram private APIs.

---

## What's New in Phase 3.3: Final Reliability Corrections, Gemini 3.6 Free-Tier Default & Persistent Upload Recovery

Phase 3.3 finalizes cloud processing reliability, API correctness, and concurrency guarantees:

- **Gemini Free-Tier Default (`gemini-3.6-flash`):**
  Reelix strictly allows **only two Google AI Studio models**:
  - `gemini-3.6-flash` (Default)
  - `gemini-3.8-flash` (Allowed Alternative)
  Google AI Studio's Standard Free Tier provides free input and output tokens for `gemini-3.6-flash`. Reelix defaults to `gemini-3.6-flash` to ensure 100% zero-cost operation without requiring a credit card or Google Cloud billing account. `gemini-3.8-flash` remains available as an explicitly selectable permitted alternative. Automatic fallback to other models is strictly forbidden and rejected at configuration, workflow, and manifest validation.
- **Durable App-Private Resumable Upload Sessions:**
  Android-backed `SharedPreferencesResumableUploadStore` persists resumable Google Drive upload session URIs in app-private storage across process restarts:
  1. Sessions survive app termination and background process kills.
  2. Sessions are keyed by stable SHA-256 identity (`canonicalPath + length + lastModified`), preventing collisions across files with identical names.
  3. Session URIs are securely stored and never emitted to logs.
  4. Automatically resumes byte streams via `308 Resume Incomplete` queries and clears expired (`404/410`) sessions.
- **Single-Writer Concurrency Architecture:**
  Google Drive API v3 does not offer native atomic CAS or server-side file locking. Concurrency is guaranteed via:
  1. *Workflow Serialization:* GitHub Actions enforces `concurrency: group: reelix-processing, cancel-in-progress: false`, ensuring exactly one cloud worker runs at any time.
  2. *Local Execution Safeguard:* Local CLI worker runs require explicit `--confirm-local` flag or `ALLOW_LOCAL_WORKER=1` environment variable to prevent accidental concurrent writes against active cloud queues.
  3. *TOCTOU Guardrails:* Workers verify remote lease ownership, settled statuses (`COMPLETED`, `FAILED`, `PARTIAL`), and version monotonicity before committing stage updates.
- **URL-Only / Manual-Input-Required Processing:**
  When a link is shared from Instagram without public media or caption text:
  1. Reelix detects URL-only jobs before calling Gemini, avoiding empty-text API failures and infinite retry loops.
  2. The job is marked `PARTIAL` with a clear explanation (`URL-only item without media or metadata; share reel video or provide notes`).
  3. The item remains offline-searchable in Room FTS4 and displays a "Manual input needed" badge with guidance to share the video directly via Android share sheet or add personal notes.
  4. If user notes or post captions are present, Gemini structured analysis proceeds normally.

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                            REELIX CLOUD ARCHITECTURE                         │
│                           Phase 3.3 Verified Pipeline                        │
└──────────────────────────────────────────────────────────────────────────────┘
   [ Instagram / Share Sheet ]
                │
                ▼
   [ Android Reelix App ]
    - Receives URLs & Shared Media (video/audio)
    - Saves locally to Room SQLite + App-Private Storage
    - Persistent Resumable Streaming Upload (SharedPreferencesResumableUploadStore)
                │
                ▼
   [ Persistent Cloud Storage: Google Drive (Personal My Drive under drive.file) ]
    ├── Reelix_Vault/ (Auto-created in 'root' via API)
    │   ├── jobs/<item_id>.json   (Leased manifests with Read-After-Write verification)
    │   ├── media/<item_id>_*     (Raw shared media files)
    │   └── audio/<item_id>.wav   (Extracted 16kHz mono WAV files)
                │
                ▼
   [ GitHub Actions Runner (Scheduled Cron / Manual Dispatch) ]
    - Serialized concurrency: group: reelix-processing, cancel-in-progress: false
    - Application lease claim + Read-After-Write verification
    - FFmpeg: Extracts 16kHz 16-bit mono WAV audio
    - Whisper: CPU speech transcription (OpenAI Whisper base model with weight caching)
    - Google AI Studio Gemini API: Structured knowledge extraction (gemini-3.6-flash default)
    - Writes verified results back to jobs/<item_id>.json in Google Drive
                │
                ▼
   [ Android Reelix Sync ]
    - WorkManager CloudSyncWorker / Settings "Sync results now"
    - Downloads transcripts & structured Gemini intelligence
    - Honest state transition: COMPLETED vs PARTIAL vs FAILED
    - Stores into Room database & populates normalized AI tags
    - Re-indexes SQLite FTS4 for instant offline search (even for partial transcripts/metadata)
```

---

## 100% Free-Tier Architecture Verification

Reelix strictly adheres to the zero-cost principle:
1. **Google Drive API (v3):** Standard Google accounts provide 15GB of persistent storage. Enabling Google Drive API in a personal GCP project requires **no billing account and no payment card**.
2. **Google AI Studio (Gemini 3.6 Flash & Gemini 3.8 Flash):** `gemini-3.6-flash` is available on Google AI Studio's **Standard Free Tier** with free input and output tokens, 1M token context windows, rate limits sufficient for personal usage, and **$0 cost** with no billing account required.
3. **GitHub Actions:** GitHub provides free runner minutes monthly for workflows.
4. **Whisper:** Open-source OpenAI Whisper runs on GitHub Actions CPU runners with weight caching (`actions/cache@v4`).

**Total Operating Cost: $0.00 / month forever.**

---

## First-Time Setup Instructions

### 1. Set Up Google Cloud Project & OAuth 2.0
> **Why OAuth 2.0?** Service Accounts have 0 bytes of storage quota and cannot own files in personal Google Drive accounts (@gmail.com). OAuth 2.0 with the least-privilege `drive.file` scope allows Reelix to manage only its own vault files in your personal Drive.

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and create a project (no billing account needed).
2. Go to **APIs & Services → Library** and enable **Google Drive API**.
3. Go to **APIs & Services → OAuth consent screen**:
   - Choose **External** user type.
   - Enter App name (`Reelix`) and your email.
   - Under **Scopes**, add `https://www.googleapis.com/auth/drive.file`.
   - Under **Test users**, add your own Gmail address.
4. Go to **APIs & Services → Credentials**:
   - Click **Create Credentials → OAuth client ID**.
   - Application type: **Desktop app**.
   - Copy the **Client ID** and **Client Secret**.

### 2. Generate Refresh Token & Auto-Create Root Vault
Run the interactive setup helper:
```bash
python workers/processor/scripts/get_oauth_token.py
```
1. Enter your Client ID and Client Secret.
2. Authorize in the browser window with your personal Google account.
3. The script automatically obtains your refresh token and creates or discovers the authorized `Reelix_Vault` folder under `'root'`.
4. Copy the printed output:
   - `GDRIVE_CLIENT_ID`
   - `GDRIVE_CLIENT_SECRET`
   - `GDRIVE_REFRESH_TOKEN`
   - `GDRIVE_FOLDER_ID`

### 3. Get a Free Gemini API Key
1. Go to [Google AI Studio](https://aistudio.google.com/).
2. Click **Get API key** and create an API key (no credit card or billing account needed).
3. Permitted models are `gemini-3.6-flash` (default) and `gemini-3.8-flash`.

### 4. Add GitHub Repository Secrets
In your GitHub repository (`https://github.com/VinitSurve/Reelix`), navigate to **Settings → Secrets and variables → Actions** and add:
- `GDRIVE_CLIENT_ID`: Your OAuth Client ID.
- `GDRIVE_CLIENT_SECRET`: Your OAuth Client Secret.
- `GDRIVE_REFRESH_TOKEN`: The generated refresh token.
- `GDRIVE_FOLDER_ID`: The authorized `Reelix_Vault` folder ID.
- `GEMINI_API_KEY`: Your free Gemini API key.
- `GEMINI_MODEL`: (Optional, defaults to `gemini-3.6-flash`; allowed alternative: `gemini-3.8-flash`).

### 5. Configure Reelix Android App
1. Open Reelix on your device.
2. Go to **Settings → Cloud processing (GitHub Actions & Google Drive)**.
3. Toggle **Enable cloud offloading**.
4. Enter your **Google Drive Folder ID** and OAuth Refresh Token.
5. Tap **Save cloud config** (tokens are encrypted with AES-256-GCM in Android Keystore).
6. When media items are shared to Reelix, they will be uploaded with resumable session recovery and processed automatically by GitHub Actions!
7. Tap **Sync results now** anytime to retrieve transcripts and Gemini content intelligence.

---

## Build & Test Instructions

### 1. Run Cloud Worker Python Tests
```bash
python3 -m unittest discover -s workers/processor/tests -v
```
Output:
```text
Ran 48 tests in 0.373s
OK (skipped=5)
```
*(43 unit & mock integration tests passed; 5 live external tests explicitly skipped when live credentials are not set)*

### 2. Run Android Unit Tests
```bash
./gradlew :app:testDebugUnitTest
```
Output:
```text
BUILD SUCCESSFUL
48 tests completed, 0 failed, 0 skipped
```

### 3. Build Android Debug APK
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`
