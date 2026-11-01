# Reelix — Personal Instagram Knowledge Vault & Content Intelligence (Android)

Reelix is a single-user, local-first Android knowledge vault for capturing, organizing, enriching, transcribing, and searching informative Instagram Reels, posts, and shared media files.

**100% free to operate**: no subscriptions, no payment cards, no billing accounts, no third-party cloud lock-in, and zero scraping of unofficial Instagram private APIs.

---

## What's New in Phase 3.2: Drive API Correctness, Concurrency & Live Integration Readiness

Phase 3.2 solidifies cloud processing reliability, API correctness, and concurrency guarantees:

- **Strict Gemini Model Restriction:** Reelix strictly allows **only two Google AI Studio models**:
  - `gemini-3.8-flash` (Default)
  - `gemini-3.6-flash` (Allowed Alternative)
  Both models are available on Google AI Studio's **Free Tier** ($0 cost, no Google Cloud billing account, no credit card required). An allowlist enforces valid models across Python config, workflow inputs, and Android manifests; any invalid model fails fast before making an API call.
- **Google Drive API Concurrency Architecture:** Google Drive API v3 does not enforce HTTP `If-Match` or atomic Compare-And-Swap (CAS) on File resource updates. Reelix solves concurrency with a robust multi-layer design:
  1. *Workflow Serialization:* GitHub Actions enforces `concurrency: group: reelix-processing, cancel-in-progress: false`, ensuring only one runner executes at a time.
  2. *Active Lease Protection:* Any job in `PROCESSING` with a lease under 15 minutes is strictly protected from being claimed or overwritten by another worker.
  3. *Stale Lease Recovery:* Abandoned or stalled jobs (> 15 minutes) are safely reclaimed and retried.
  4. *Read-After-Write Verification:* Workers write their unique `runner_id` and lease timestamp, then immediately re-read the remote manifest. If a race collision occurs and another worker won the write, the losing worker detects the mismatch and immediately aborts without processing or corrupting data.
  5. *Lease Ownership Check on Updates:* Workers verify remote lease ownership before committing stage updates.
- **Root Vault Authorization under `drive.file` Scope:**
  The `drive.file` OAuth scope only grants access to files and folders created or opened by the app. A folder manually created by the user in the Google Drive web UI is inaccessible under `drive.file`. Reelix automatically searches for or creates its own `Reelix_Vault` folder under `'root'` (`parents: ['root']`) using the authorized API. Both the Android app and GitHub Actions share this authorized root folder.
- **Android Resumable Upload Recovery:**
  Implements the full Google Drive Resumable Upload recovery protocol:
  1. Persists upload session URIs across network interruptions and app restarts.
  2. Queries the server for uploaded byte range (`PUT` with `Content-Range: bytes */<length>`).
  3. Parses `308 Resume Incomplete` (`Range: bytes=0-<lastByte>`) and resumes streaming from `lastByte + 1`.
  4. Detects already-completed uploads (`200/201`), avoiding duplicate file creation.
  5. Handles expired sessions (`404/410`) by seamlessly restarting fresh sessions.
  6. Refreshes OAuth tokens on HTTP 401 without destroying in-progress upload sessions.
- **Honest Completion States:**
  - `COMPLETED`: Marked only when all required stages succeeded and produced non-empty results (non-empty summary, takeaways, and transcript). Empty Gemini responses or missing transcripts are never treated as successful.
  - `PARTIAL`: Preserves successful stage results (e.g. transcript) in SQLite, marks `NEEDS_ATTENTION`, and indexes partial results in Room FTS4 for offline searchability.
  - `FAILED`: Marked when all stages fail or retries are exhausted.

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                            REELIX CLOUD ARCHITECTURE                         │
│                           Phase 3.2 Verified Pipeline                        │
└──────────────────────────────────────────────────────────────────────────────┘
   [ Instagram / Share Sheet ]
                │
                ▼
   [ Android Reelix App ]
    - Receives URLs & Shared Media (video/audio)
    - Saves locally to Room SQLite + App-Private Storage
    - Resumable streaming upload to Google Drive queue (session recovery, 0 heap buffering)
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
    - Serialized concurrency (cancel-in-progress: false)
    - Application lease claim + Read-After-Write verification
    - FFmpeg: Extracts 16kHz 16-bit mono WAV audio
    - Whisper: CPU speech transcription (OpenAI Whisper base model with weight caching)
    - Google AI Studio Gemini API: Structured knowledge extraction (gemini-3.8-flash)
    - Writes verified results back to jobs/<item_id>.json in Google Drive
                │
                ▼
   [ Android Reelix Sync ]
    - WorkManager CloudSyncWorker / Settings "Sync results now"
    - Downloads transcripts & structured Gemini intelligence
    - Honest state transition: COMPLETED vs PARTIAL vs FAILED
    - Stores into Room database & populates normalized AI tags
    - Re-indexes SQLite FTS4 for instant offline search (even for partial transcripts)
```

---

## 100% Free-Tier Architecture Verification

Reelix strictly adheres to the zero-cost principle:
1. **Google Drive API (v3):** Standard Google accounts provide 15GB of persistent storage. Enabling Google Drive API in a personal GCP project requires **no billing account and no payment card**.
2. **Google AI Studio (Gemini 3.8 Flash & Gemini 3.6 Flash):** Available on Google AI Studio's **Free Tier** with 1M token context windows, rate limits sufficient for personal usage, and **$0 cost** with no billing account required.
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
3. Permitted models are `gemini-3.8-flash` (default) and `gemini-3.6-flash`.

### 4. Add GitHub Repository Secrets
In your GitHub repository (`https://github.com/VinitSurve/Reelix`), navigate to **Settings → Secrets and variables → Actions** and add:
- `GDRIVE_CLIENT_ID`: Your OAuth Client ID.
- `GDRIVE_CLIENT_SECRET`: Your OAuth Client Secret.
- `GDRIVE_REFRESH_TOKEN`: The generated refresh token.
- `GDRIVE_FOLDER_ID`: The authorized `Reelix_Vault` folder ID.
- `GEMINI_API_KEY`: Your free Gemini API key.
- `GEMINI_MODEL`: (Optional, defaults to `gemini-3.8-flash`; allowed alternative: `gemini-3.6-flash`).

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
Ran 36 tests in 0.362s
OK (skipped=5)
```
*(31 unit & mock integration tests passed; 5 live external tests explicitly skipped when live credentials are not set)*

### 2. Run Android Unit Tests
```bash
./gradlew :app:testDebugUnitTest
```
Output:
```text
BUILD SUCCESSFUL
46 tests completed, 0 failed, 0 skipped
```

### 3. Build Android Debug APK
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`
