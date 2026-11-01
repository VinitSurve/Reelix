# Reelix — Personal Instagram Knowledge Vault & Content Intelligence (Android)

Reelix is a single-user, local-first Android knowledge vault for capturing, organizing, enriching, transcribing, and searching informative Instagram Reels, posts, and shared media files.

**100% free to operate**: no subscriptions, no payment cards, no billing accounts, no third-party cloud lock-in, and zero scraping of unofficial Instagram private APIs.

---

## What's New in Phase 3: Cloud Processing Migration & GitHub Actions

Phase 3 introduces an asynchronous, battery-saving cloud processing architecture that offloads heavy compute (FFmpeg audio extraction, Whisper speech-to-text, and Gemini AI analysis) to free GitHub Actions runners, using persistent Google Drive storage for durable queueing.

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
    - Uploads media & registers manifest in Google Drive queue
                │
                ▼
   [ Persistent Cloud Storage: Google Drive ]
    ├── jobs/<item_id>.json   (Atomic job manifests: QUEUED / PROCESSING / COMPLETED)
    ├── media/<item_id>_*     (Raw shared media files)
    └── audio/<item_id>.wav   (Extracted 16kHz mono WAV files)
                │
                ▼
   [ GitHub Actions Runner (Cron / Manual Dispatch) ]
    - Concurrency protected (no race conditions)
    - Claims queued job manifests atomically
    - FFmpeg: Extracts 16kHz 16-bit mono WAV audio
    - Whisper: CPU speech transcription (OpenAI Whisper base model with weight caching)
    - Google AI Studio Gemini API: Generates 9-field structured content intelligence
    - Writes results back to jobs/<item_id>.json in Google Drive
                │
                ▼
   [ Android Reelix Sync ]
    - WorkManager CloudSyncWorker / Settings "Sync cloud results now"
    - Downloads transcripts & structured Gemini intelligence
    - Stores into Room database & populates tags
    - Re-indexes SQLite FTS4 for instant offline search
```

### Key Highlights
- **Zero Heavy Processing on Android Phones:** No CPU-heavy Whisper decoding or battery-draining audio encoding on your device.
- **Durable Storage & Atomic Job Claiming:** Google Drive serves as a resilient queue. The GitHub Actions worker claims jobs using a runner ID and timestamps, with automatic retries up to 3 attempts.
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
2. **Google AI Studio (Gemini 1.5 Flash):** Google AI Studio provides a **permanent free-tier API** (15 requests/minute, 1,000,000 tokens/minute, 1,500 requests/day) without requiring a credit card or billing account.
3. **GitHub Actions:** GitHub accounts receive free runner minutes each month for public and private repositories.
4. **Whisper:** Uses the open-source OpenAI Whisper model running on GitHub Actions CPU runners with runner caching (`actions/cache@v4`).

**Total Operating Cost: $0.00 / month forever.**

---

## Cloud Worker Setup (GitHub Actions & Google Drive)

### 1. Set Up Google Drive
1. Create a standard project in [Google Cloud Console](https://console.cloud.google.com/) (no billing needed).
2. Enable the **Google Drive API**.
3. Create a **Service Account** (e.g. `reelix-worker@<project-id>.iam.gserviceaccount.com`) and create a JSON key.
4. In Google Drive, create a folder named `Reelix_Vault` and share it with your Service Account email with **Editor** permissions.
5. Note the folder ID from the Google Drive URL: `https://drive.google.com/drive/folders/<FOLDER_ID>`.

### 2. Get a Free Gemini API Key
1. Go to [Google AI Studio](https://aistudio.google.com/).
2. Click **Get API key** and generate a free API key (zero payment required).

### 3. Add GitHub Repository Secrets
In your GitHub repository (`https://github.com/VinitSurve/Reelix`), navigate to **Settings → Secrets and variables → Actions** and add:
- `GDRIVE_SERVICE_ACCOUNT_JSON`: Paste the entire JSON string of the Service Account key.
- `GDRIVE_FOLDER_ID`: Paste the Google Drive folder ID.
- `GEMINI_API_KEY`: Paste your free Gemini API key.

### 4. Enable Cloud Offloading in Reelix Android App
1. Open Reelix on your device or emulator.
2. Go to **Settings → Cloud processing (GitHub Actions & Google Drive)**.
3. Toggle **Enable cloud offloading**.
4. Enter your **Google Drive Folder ID** and OAuth token / credentials.
5. Tap **Save cloud config**.
6. When media items are shared to Reelix, they will be uploaded to Drive and processed automatically by GitHub Actions!
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
│       ├── src/
│       │   ├── config.py      # Environment configuration (secrets & folders)
│       │   ├── models.py      # JobManifest, MediaInfo, StageStates, GeminiAnalysisResult
│       │   ├── pipeline.py    # Atomic job claiming, stage execution, retries, disk cleanup
│       │   ├── main.py        # CLI entrypoint for GitHub Actions runner
│       │   ├── stages/        # audio_extractor (FFmpeg), transcriber (Whisper), gemini_analyzer
│       │   └── storage/       # DriveClient (Google Drive API v3) & MockStorageClient
│       ├── tests/             # 16 unit tests for cloud pipeline (audio, whisper, gemini, drive)
│       └── requirements.txt   # Worker dependencies
├── app/src/main/java/app/reelix/
│   ├── cloud/                 # Android CloudSyncClient (Google Drive v3), CloudSyncRepository, CloudSyncWorker
│   ├── core/                  # Clock, privacy-safe logging, InstagramUrlParser, ShareIntentReader
│   ├── data/
│   │   ├── db/                # ReelixDatabase (Room v2 + FTS4 full-text search)
│   │   ├── files/             # MediaStorage (AppPrivateMediaStorage), StorageInspector
│   │   ├── media/             # Local AndroidMediaCodecAudioExtractor
│   │   ├── repository/        # SavedItemRepository, SearchIndexer, TagNormalizer
│   │   └── settings/          # DataStoreSettingsRepository, KeystoreSecretStore
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
Ran 16 tests in 0.011s
OK
```

### 2. Run Android Unit Tests
```bash
./gradlew :app:testDebugUnitTest
```
Output:
```text
BUILD SUCCESSFUL in 9s
30 actionable tasks: 3 executed, 27 up-to-date
```

### 3. Build Android Debug APK
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Core Flow (End-to-End)

1. **Share Media:** User shares an Instagram Reel video to Reelix.
2. **Instant Intake:** Reelix saves media to local storage and creates an item in Room database (`QUEUED`).
3. **Cloud Offload:** `ProcessingWorker` uploads the media to Google Drive (`media/`) and posts a manifest to Google Drive (`jobs/<id>.json`).
4. **GitHub Actions Execution:** The GitHub Actions workflow wakes up, downloads pending media, extracts audio with FFmpeg, transcribes speech with Whisper, generates 9 structured insights with Gemini, and writes the completed manifest back to Google Drive.
5. **Sync & Instant Search:** Reelix fetches completed manifests from Google Drive, saves transcripts and 9-field intelligence into Room, applies tags, and rebuilds the SQLite FTS4 virtual index.
6. **Offline Availability:** The user can search, read, and browse their vault anytime, completely offline.
