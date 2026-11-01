# Reelix — Personal Instagram Knowledge Vault & Content Intelligence (Android)

Reelix is a single-user, local-first Android knowledge vault for capturing, organizing, enriching, transcribing, and searching informative Instagram Reels, posts, and shared media files.

**100% free to operate**: no subscriptions, no payment cards, no billing accounts, no third-party cloud lock-in, and zero scraping of unofficial Instagram private APIs.

---

## What's New in Phase 3 & 3.1: Cloud Processing Migration & Verified OAuth Integration

Phase 3 introduces an asynchronous, battery-saving cloud processing architecture that offloads heavy compute (FFmpeg audio extraction, Whisper speech-to-text, and Gemini AI analysis) to free GitHub Actions runners, using persistent Google Drive storage for durable queueing.

Phase 3.1 addresses critical integration requirements:
- **Upgraded Gemini Model:** Upgraded to supported `gemini-2.5-flash` with zero billing requirements and permanent free-tier quotas (configurable via `GEMINI_MODEL`).
- **Google OAuth 2.0 on Personal My Drive:** Standalone service accounts cannot own files in personal Gmail My Drive and have zero storage quota. Reelix now uses Google OAuth 2.0 with the least-privilege `drive.file` scope and automatic token refresh.
- **Streaming Resumable Android Uploads:** Uses Google Drive's Resumable Upload protocol with OkHttp streaming (`file.asRequestBody()`), preventing `OutOfMemoryError` on large video files. Includes duplicate detection to prevent redundant uploads and automatic token refresh on HTTP 401.
- **Keystore Security:** OAuth access and refresh tokens are encrypted using AES-256-GCM in the Android Keystore (`SecretStore`) and are never written to plain DataStore preferences or system logs.
- **Concurrency & Lease-Claiming Strategy:** Uses Google Drive ETags (`If-Match`) and a 15-minute lease timeout to handle competing runners safely and reclaim stale/abandoned processing jobs.

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                            REELIX CLOUD ARCHITECTURE                         │
└──────────────────────────────────────────────────────────────────────────────┘
   [ Instagram / Share Sheet ]
                │
                ▼
   [ Android Reelix App ]
    - Receives URLs & Shared Media (video/audio)
    - Saves locally to Room SQLite + App-Private Storage
    - Resumable streaming upload to Google Drive queue (no heap buffering)
                │
                ▼
   [ Persistent Cloud Storage: Google Drive (Personal My Drive) ]
    ├── jobs/<item_id>.json   (Lease-tracked manifests with ETag concurrency)
    ├── media/<item_id>_*     (Raw shared media files)
    └── audio/<item_id>.wav   (Extracted 16kHz mono WAV files)
                │
                ▼
   [ GitHub Actions Runner (Scheduled Cron / Manual Dispatch) ]
    - Concurrency protected (no race conditions)
    - Optimistic lease claim with ETag pre-condition checks
    - FFmpeg: Extracts 16kHz 16-bit mono WAV audio
    - Whisper: CPU speech transcription (OpenAI Whisper base model with weight caching)
    - Google AI Studio Gemini API: Generates 9-field structured content intelligence
    - Writes results back to jobs/<item_id>.json in Google Drive
                │
                ▼
   [ Android Reelix Sync ]
    - WorkManager CloudSyncWorker / Settings "Sync results now"
    - Downloads transcripts & structured Gemini intelligence
    - Stores into Room database & populates tags
    - Re-indexes SQLite FTS4 for instant offline search
```

### Key Highlights
- **Zero Heavy Processing on Android Phones:** No CPU-heavy Whisper decoding or battery-draining audio encoding on your device.
- **Durable Storage & Atomic Job Claiming:** Google Drive serves as a resilient queue. The GitHub Actions worker claims jobs using a runner ID, timestamps, and ETags, with automatic retries up to 3 attempts.
- **Git Repository Cleanliness:** No media files, model binaries, or database records are committed to Git.
- **9 Structured Content Intelligence Fields:**
  1. `summary`
  2. `detailed_summary`
  3. `main_topic`
  4. `category`
  5. `keywords`
  6. `tags`
  7. `key_takeaways`
  8. `important_concepts`
  9. `actionable_steps`
- **Dual Mode Flexibility:** Keep local processing (native MediaCodec + local Whisper + local Ollama) or enable Cloud Offloading in Settings with a single switch.

---

## 100% Free-Tier Architecture Verification

Reelix strictly adheres to the zero-cost principle:
1. **Google Drive API (v3):** Standard GCP projects can enable Google Drive API with **no payment card and no billing account**. Google accounts provide 15GB of free persistent storage and generous Drive API quotas (20,000 queries / 100 seconds).
2. **Google AI Studio (Gemini 2.5 Flash):** Google AI Studio provides a **permanent free-tier API** without requiring a credit card or billing account.
3. **GitHub Actions:** GitHub accounts receive free runner minutes each month for public and private repositories.
4. **Whisper:** Uses the open-source OpenAI Whisper model running on GitHub Actions CPU runners with runner caching (`actions/cache@v4`).

**Total Operating Cost: $0.00 / month forever.**

---

## Cloud Worker Setup (GitHub Actions & Google Drive)

### 1. Set Up Google Cloud Project & OAuth 2.0
> **Why OAuth 2.0?** Standalone Service Accounts have 0 bytes of storage quota and cannot own files in personal Google Drive accounts (@gmail.com). Using Google OAuth 2.0 with the least-privilege `drive.file` scope allows the worker to access only the files/folders created by Reelix in your personal Drive.

1. Go to [Google Cloud Console](https://console.cloud.google.com/) and create a project (no billing account needed).
2. Go to **APIs & Services → Library** and enable **Google Drive API**.
3. Go to **APIs & Services → OAuth consent screen**:
   - Choose **External** user type.
   - Fill in app name (e.g. `Reelix`) and your email.
   - Under **Scopes**, add `https://www.googleapis.com/auth/drive.file`.
   - Under **Test users**, add your own Gmail address.
4. Go to **APIs & Services → Credentials**:
   - Click **Create Credentials → OAuth client ID**.
   - Application type: **Desktop app**.
   - Copy the **Client ID** and **Client Secret**.

### 2. Generate Refresh Token
Run the included helper script:
```bash
python workers/processor/scripts/get_oauth_token.py
```
Enter your Client ID and Client Secret, authorize in your browser, and copy the printed `GDRIVE_REFRESH_TOKEN`.

### 3. Create Google Drive Target Folder
1. Open [Google Drive](https://drive.google.com/).
2. Create a folder named `Reelix_Vault`.
3. Copy the Folder ID from the URL: `https://drive.google.com/drive/folders/<FOLDER_ID>`.

### 4. Get a Free Gemini API Key
1. Go to [Google AI Studio](https://aistudio.google.com/).
2. Click **Get API key** and generate a free API key (zero payment required).

### 5. Add GitHub Repository Secrets
In your GitHub repository (`https://github.com/VinitSurve/Reelix`), navigate to **Settings → Secrets and variables → Actions** and add:
- `GDRIVE_CLIENT_ID`: Your OAuth Client ID.
- `GDRIVE_CLIENT_SECRET`: Your OAuth Client Secret.
- `GDRIVE_REFRESH_TOKEN`: The generated refresh token.
- `GDRIVE_FOLDER_ID`: The `Reelix_Vault` folder ID.
- `GEMINI_API_KEY`: Your free Gemini API key.
- `GEMINI_MODEL`: (Optional, defaults to `gemini-2.5-flash`).

### 6. Enable Cloud Offloading in Reelix Android App
1. Open Reelix on your device or emulator.
2. Go to **Settings → Cloud processing (GitHub Actions & Google Drive)**.
3. Toggle **Enable cloud offloading**.
4. Enter your **Google Drive Folder ID** and OAuth Refresh Token (or access token).
5. Tap **Save cloud config** (tokens are encrypted in Android Keystore).
6. When media items are shared to Reelix, they will be streamed to Drive and processed automatically by GitHub Actions!
7. Tap **Sync results now** anytime to integrate completed transcripts and Gemini intelligence immediately.

---

## Repository Structure

```text
.
├── .github/
│   └── workflows/
│       ├── process-queue.yml  # Scheduled (every 30m) & dispatch processor on GitHub Actions
│       └── ci.yml             # Continuous integration (Android testDebugUnitTest + Python unit tests)
├── workers/
│   └── processor/             # Cloud Python worker executed in GitHub Actions
│       ├── scripts/
│       │   └── get_oauth_token.py # Interactive OAuth 2.0 token generator
│       ├── src/
│       │   ├── config.py      # Environment configuration (secrets & folders)
│       │   ├── models.py      # JobManifest, MediaInfo, StageStates, GeminiAnalysisResult
│       │   ├── pipeline.py    # Atomic job claiming, stage execution, retries, disk cleanup
│       │   ├── main.py        # CLI entrypoint for GitHub Actions runner
│       │   ├── stages/        # audio_extractor (FFmpeg), transcriber (Whisper), gemini_analyzer (Gemini 2.5 Flash)
│       │   └── storage/       # DriveClient (OAuth 2.0 & ETags) & MockStorageClient
│       ├── tests/             # 24 unit and live-integration tests for cloud pipeline
│       └── requirements.txt   # Worker dependencies
├── app/src/main/java/app/reelix/
│   ├── cloud/                 # Android CloudSyncClient (resumable streaming), CloudSyncRepository, CloudSyncWorker
│   ├── core/                  # Clock, privacy-safe logging, InstagramUrlParser, ShareIntentReader
│   ├── data/
│   │   ├── db/                # ReelixDatabase (Room v2 + FTS4 full-text search)
│   │   ├── files/             # MediaStorage (AppPrivateMediaStorage), StorageInspector
│   │   ├── media/             # Local AndroidMediaCodecAudioExtractor
│   │   ├── repository/        # SavedItemRepository, SearchIndexer, TagNormalizer
│   │   └── settings/          # DataStoreSettingsRepository, KeystoreSecretStore (AES-256-GCM)
│   ├── integrations/          # Local Whisper model manager & Ollama integration
│   ├── processing/            # ProcessingPipeline, StageHandlers, ProcessingWorker, Scheduler
│   └── ui/                    # Jetpack Compose UI (Material 3), Navigation, Screens, Settings
└── app/src/test/java/app/reelix/
    ├── CloudSyncTest.kt       # Manifest round-trip, cloud upload, completed job sync & FTS4 indexing
    ├── AudioExtractionAndTranscriptionTest.kt
    ├── AiEnrichmentAndConnectionTest.kt
    └── ... (43 passing unit tests)
```

---

## Build & Test Instructions

### 1. Run Cloud Worker Python Tests
```bash
python3 -m unittest discover -s workers/processor/tests -v
```
Output:
```text
Ran 24 tests in 0.455s
OK (skipped=2)
```

### 2. Run Android Unit Tests
```bash
./gradlew :app:testDebugUnitTest
```
Output:
```text
BUILD SUCCESSFUL in 8s
30 actionable tasks: 7 executed, 23 up-to-date
```

### 3. Build Android Debug APK
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`
