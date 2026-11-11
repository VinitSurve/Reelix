# Reelix — Personal Instagram Knowledge Vault & Content Intelligence (Android)

Reelix is a single-user, local-first Android knowledge vault for capturing, organizing, enriching, transcribing, and searching informative Instagram Reels, posts, and shared media files.

Designed for personal operation using standard platform tiers (Google Drive, GitHub Actions, and Google AI Studio) without paid server infrastructure or scraping unofficial private APIs.

---

## Architecture Hardening: Distributed Reliability & Single Production Worker Engine

Phase 3 delivers production-grade distributed reliability, robust crash recovery, and content-addressable storage integrity:

- **Single Production Worker Guarantee:**
  - **GitHub Actions is the Sole Authorized Production Worker:** All production job processing runs in GitHub Actions, serialized via concurrency group `reelix-processing` (`cancel-in-progress: false`). This guarantees exactly one active worker executes at any point in time.
  - **Local Mac Production Job Claiming Disabled:** Running the worker locally against a live Google Drive production queue without GitHub Actions environment variables fails fast with exit code `1`. This eliminates split-brain manifest corruption and competing runner collisions.
- **Continuous Background Heartbeat Renewal:**
  - **Active Lease Extension:** A background daemon thread (`ContinuousHeartbeatManager`) continuously renews the 15-minute manifest lease every 30 seconds during long-running tasks (yt-dlp acquisition, FFmpeg extraction, Whisper transcription, visual OCR, Gemini analysis, and large file uploads).
  - **Cancellation Propagation:** If a lease renewal fails or the lease is preempted, a cancellation event is signaled immediately. Long-running subprocesses (FFmpeg, OCR) are terminated/killed, and any subsequent stage writes or completion marking are blocked.
- **SHA-256 Content-Addressable Artifact Integrity:**
  - **Streaming SHA-256 Verification:** Media and audio files compute streaming SHA-256 digests. Hashes are persisted in Google Drive metadata (`appProperties: {"reelix_sha256": ...}`) and recorded in manifest `ArtifactRef.content_hash`.
  - **Deduplication & Tamper Detection:** Before uploading, the worker verifies if an artifact with matching SHA-256 already exists in Drive and reuses it without re-uploading. Downloads verify file integrity against expected hashes; mismatched or corrupted downloads are immediately unlinked.
  - **Legacy Compatibility:** Pre-existing Drive artifacts uploaded without hash metadata are seamlessly verified and upgraded with `reelix_sha256` on first encounter.
- **9-Point Idempotent Crash Recovery:**
  - Complete stage-level durable recovery tested across all failure injection points:
    1. Post-acquisition crash -> reuses downloaded media without re-downloading.
    2. Post-inspection crash -> preserves duration and resolution metadata.
    3. Post-audio-extraction crash -> reuses existing WAV artifact without re-running FFmpeg.
    4. Post-transcription crash -> reuses stored speech transcript without re-running Whisper.
    5. Post-OCR crash -> reuses on-screen visual text without re-extracting frames.
    6. Post-Gemini crash -> reuses structured AI knowledge.
    7. Post-upload crash -> preserves artifact references without duplicate uploads.
    8. Pre-completion crash -> safely finalizes `COMPLETED` state without data loss.
    9. Transient Gemini HTTP 503 recovery -> retries analysis with backoff while preserving media and transcripts.

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                    REELIX PRODUCTION HYBRID ARCHITECTURE                     │
│                        Durable Cloud Processing Flow                         │
└──────────────────────────────────────────────────────────────────────────────┘
   [ Instagram / Share Sheet ]
                │
                ▼
   [ Android Reelix App ]
    - User shares post URL or media
    - Room SQLite stores author data locally (never overwritten)
    - Submits JobManifest to Google Drive 'Reelix_Vault/jobs/<item_id>.json'
                │
                ▼
   [ Google Drive Queue & Storage ]
    ├── jobs/<item_id>.json   (Authoritative queue, atomic read-after-write lease)
    ├── media/<item_id>_*     (Raw media tagged with SHA-256 appProperties)
    └── audio/<item_id>.wav   (16kHz mono audio tagged with SHA-256 appProperties)
                │
                ▼
   [ GitHub Actions Runner (Sole Production Worker) ]
    - Concurrency lock: group: reelix-processing, cancel-in-progress: false
    - ContinuousHeartbeatManager: 30s background lease renewal
    - yt-dlp media downloader / SHA-256 upload
    - FFmpeg 16kHz audio extraction + cancellation listener
    - OpenAI Whisper base transcription + cache
    - Tesseract OCR frame analysis + cancellation listener
    - Google Gemini AI (gemini-3.6-flash / gemini-3.8-flash)
    - Updates JobManifest to COMPLETED or WAITING_RETRY
                │
                ▼
   [ Android Reelix Sync ]
    - WorkManager CloudSyncWorker / User Manual Sync
    - Pulls completed JobManifest from Google Drive
    - Updates Room database (transcript, AI summary, topics, tags)
    - Reindexes SQLite FTS4 for full-text offline search
```

---

## What's New in Phase 3.5: Google Drive Upload Identity Correction

Phase 3.5 delivers complete upload identity isolation in Google Drive and robust duplicate detection:

- **Google Drive Metadata Identity (`appProperties`):**
  Uploaded media files in `Reelix_Vault/media` are stamped with custom Drive metadata:
  `appProperties: {"reelix_item_id": itemId, "reelix_type": "media"}`.
  1. Duplicate detection searches strictly by `appProperties has { key='reelix_item_id' and value='<itemId>' }` instead of filename alone.
  2. Preserves the original human-readable filename in Drive.
  3. Ensures retries for the same item reuse the existing Drive file.
  4. Guarantees different items with identical filenames or identical file sizes create separate Drive files and never collide.
  5. Un-tagged pre-existing Drive files without Reelix metadata are never falsely associated with new items.
- **Stable Item-Isolated Resumable Upload Identity:**
  Upload session keys strictly require and incorporate a stable Reelix item identifier alongside canonical file path, file length, and last modified timestamp (`item_${itemId}_${canonicalPath}_${length}_${lastModified}`):
  1. Prevents cross-item session collisions when different items share identical files or temporary paths.
  2. Guarantees legitimate retries of the same item and unchanged file safely resume from stored sessions.
  3. Modifying a file produces a distinct key, preventing stale session resumption.
  4. Automatically invalidates legacy un-versioned sessions via schema migration (`__schema_version__ = 2`) in `SharedPreferencesResumableUploadStore`.
- **Failed-Job Offline Search Indexing:**
  `CloudSyncRepository` triggers `SearchIndexer.reindex(itemId)` for `FAILED` jobs:
  1. Preserves and offline-indexes partial transcripts or metadata synchronized before job failure in Room FTS4.
  2. Reindexes items without results cleanly, ensuring legitimate user metadata (e.g. title) remains searchable while preventing stray or incorrect search entries.
- **Strict Gemini Model Enforcement:**
  Active Reelix processing is strictly restricted to:
  - `gemini-3.6-flash` (Default — verified zero-cost on Google AI Studio Standard Free Tier)
  - `gemini-3.8-flash` (Allowed Alternative)
  Live integration verification is strictly restricted to `gemini-3.6-flash`. Automatic fallback to other models is forbidden and fails fast before any API call.
- **Single-Writer Concurrency Architecture:**
  Google Drive API v3 does not offer native atomic CAS or server-side file locking across independent machines. Concurrency is guaranteed via:
  1. *Workflow Serialization:* GitHub Actions enforces `concurrency: group: reelix-processing, cancel-in-progress: false, queue: max`, ensuring exactly one cloud worker runs at any time and queues pending triggers up to 100 runs without dropping.
  2. *Local Execution Safeguard:* Local Mac worker claiming against live production Google Drive is strictly disabled. Running `python -m workers.processor.src.main` outside GitHub Actions against production credentials exits with a fatal error (`exit code 1`). For local testing, `REELIX_MOCK_MODE=1` must be used.
  3. *TOCTOU Guardrails:* Workers verify remote lease ownership, settled statuses (`COMPLETED`, `FAILED`, `PARTIAL`), and version monotonicity before committing stage updates.
  4. *Distributed Locking Boundary:* GitHub Actions concurrency serializes GitHub runners only; distributed mutual exclusion across nodes is maintained via atomic Drive manifest lease claims and continuous heartbeats.
- **URL-Only / Manual-Input-Required Processing:**
  When a link is shared from Instagram without public media or caption text:
  1. Reelix detects URL-only jobs before calling Gemini, avoiding empty-text API failures and infinite retry loops.
  2. The job is marked `PARTIAL` with a clear explanation (`URL-only item without media or metadata; share reel video or provide notes`).
  3. The item remains offline-searchable in Room FTS4 and displays a "Manual input needed" badge with guidance to share the video directly via Android share sheet or add personal notes.
  4. If user notes or post captions are present, Gemini structured analysis proceeds normally.

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                            REELIX CLOUD ARCHITECTURE                         │
│                           Phase 3.5 Verified Pipeline                        │
└──────────────────────────────────────────────────────────────────────────────┘
   [ Instagram / Share Sheet ]
                │
                ▼
   [ Android Reelix App ]
    - Receives URLs & Shared Media (video/audio)
    - Saves locally to Room SQLite + App-Private Storage
    - Item-isolated Resumable Streaming Upload (SharedPreferencesResumableUploadStore)
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
    - Re-indexes SQLite FTS4 for instant offline search (including partial and failed jobs)
```
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

## Service Boundaries, Storage Quotas & Platform Limits

Reelix is designed for personal use leveraging standard platform tiers without requiring custom server infrastructure. However, each integrated service operates under distinct boundaries, storage allowances, and usage quotas:

1. **User's Google Drive Account Storage:**
   - Saved videos, audio extractions, and job manifests are stored directly in your personal Google Drive (`Reelix_Vault/`).
   - Storage consumption counts against your Google Account quota (15 GB standard free allowance shared across Google Drive, Gmail, and Google Photos, or your paid Google One storage subscription).
   - If your Google Drive storage becomes full, uploads will fail until storage is freed or expanded.
2. **Google Drive API Quotas (v3):**
   - Personal Google Cloud projects are subject to Google Drive API query rate limits: typically 12,000 requests per minute per project and 20,000 queries per 100 seconds per user.
   - Operations stay well within these limits during typical single-user usage, but rapid loops or massive concurrent requests will trigger HTTP 403/429 rate limit errors.
3. **GitHub Actions Runner Limits:**
   - Standard personal GitHub accounts receive free runner minutes (e.g. 2,000 minutes/month for private repositories; public repositories receive standard hosted runner minutes subject to GitHub's Acceptable Use Policy).
   - Workflows are subject to GitHub runner job timeouts (max 360 minutes per job, configured to 45 minutes in Reelix) and concurrency limits (max 20 concurrent jobs on free tiers).
   - Reelix serializes queue processing (`concurrency: group: reelix-processing, queue: max`) to eliminate overlapping runner executions.
4. **Google Gemini API Limits (Google AI Studio):**
   - Free-tier API keys for `gemini-3.6-flash` and `gemini-3.8-flash` operate under Google AI Studio rate limits:
     - 15 Requests Per Minute (RPM)
     - 1,500 Requests Per Day (RPD)
     - 1,000,000 Tokens Per Minute (TPM)
   - Heavy usage or sudden bursts of requests will return HTTP 429 (Rate Limit Exceeded) or HTTP 503 (Temporary High Demand). Reelix handles these gracefully via exponential backoff with jitter and stage preservation.
   - Platform free tiers, quotas, and terms of service are controlled by Google and GitHub and may evolve over time. Reelix treats AI analysis as an optional enrichment layer so the vault remains functional even when Gemini is temporarily unavailable.

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
# or via pytest:
PYTHONPATH=. pytest workers/processor/tests
```
Output:
```text
163 passed, 5 skipped in 13.18s
```
*(163 unit & mock integration tests passed; 5 live external tests explicitly skipped when live credentials are not set)*

### 2. Run Android Unit Tests
```bash
./gradlew :app:testDebugUnitTest
```
Output:
```text
BUILD SUCCESSFUL
93 tests completed, 0 failed, 0 skipped
```

### 3. Build Android Debug APK
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`
