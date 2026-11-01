# Reelix — Personal Instagram Knowledge Vault & Content Intelligence (Android)

Reelix is a single-user, local-first Android knowledge vault for capturing, organizing, enriching, transcribing, and searching informative Instagram Reels, posts, and shared media files.

100% free to operate: no subscriptions, no payment cards, no billing accounts, no third-party cloud lock-in, and zero scraping of unofficial Instagram private APIs.

---

## What's New in Phase 2

- **Direct Media File Ingestion:** Share videos (`video/*`), audio files (`audio/*`), or binary streams (`application/octet-stream`) directly into Reelix via Android's share sheet (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`). Files are streamed into app-private storage (`filesDir/media`) with storage availability verification and configurable size bounds.
- **Local Audio Extraction:** Uses Android's native `MediaExtractor` and hardware-accelerated `MediaCodec` to decode videos and extract audio tracks as 16,000 Hz 16-bit mono PCM WAV files. Zero heavy external binaries, GPL licensing issues, or APK bloat.
- **Offline Speech Recognition (Whisper):** Run speech recognition entirely offline without cloud APIs or network calls. Import standard Whisper GGML/GGUF models (e.g. `ggml-tiny.bin` ~75MB, `ggml-base.bin` ~142MB) via Android's Storage Access Framework (SAF) in Settings. When no model is configured, Reelix reports this honestly without fabricating transcripts.
- **Enhanced Content Understanding:** Extended OpenAI-compatible analysis to 9 structured keys:
  1. `summary`
  2. `detailed_summary`
  3. `main_topic`
  4. `category`
  5. `keywords`
  6. `tags`
  7. `key_takeaways`
  8. `important_concepts`
  9. `actionable_steps`
- **Live Ollama Connection Testing:** Settings includes a "Test connection" button that queries `/v1/models` (and `/api/tags`) against self-hosted endpoints like Ollama, showing live connection status and model availability.
- **Honest Status Semantics:** Replaced generic "Processed" statuses with independent provenance badges:
  - `Media available`
  - `Audio extracted`
  - `Transcribed`
  - `AI enriched`
  - `Metadata extracted`
  - `Needs attention`
- **Extended Full-Text Search (FTS4):** Offline SQLite FTS4 indexing across detailed summaries, important concepts, actionable steps, transcripts, notes, tags, and media file names with dedicated status filter chips.
- **Room Database Migration v1 → v2:** Seamless database upgrade via `MIGRATION_1_2` altering tables and rebuilding `saved_item_fts` without data loss.

---

## Core Flow

1. **Instagram URL Sharing:** Tap **Share** on any Instagram Reel or post and select **Reelix**. The URL is normalized and stored immediately (`QUEUED`).
2. **Media File Sharing:** Share a video or audio file downloaded from Instagram or another app to **Reelix**. The file is safely copied into private storage, checked against storage limits, and scheduled for audio extraction and transcription.
3. **Background Processing Pipeline:** WorkManager processes items through an idempotent 11-stage pipeline:
   - `VALIDATION`: Checks URL format or verifies local media file.
   - `MEDIA_VERIFICATION`: Verifies imported file integrity.
   - `ACQUISITION`: Confirms media presence or marks URL-only.
   - `METADATA`: Best-effort OpenGraph link preview extraction (URL-mode only).
   - `AUDIO_EXTRACTION`: Native decoding of video to 16kHz mono WAV.
   - `TRANSCRIPTION`: Offline Whisper speech recognition (when model is installed).
   - `VISUAL_ANALYSIS`: Video frame analysis hook (gracefully skipped if unconfigured).
   - `ANALYSIS`: Structured 9-field content enrichment using local Ollama or OpenAI-compatible endpoint.
   - `TAGGING`: Combines hashtags and AI tags with provenance tracking.
   - `INDEXING`: Rebuilds SQLite FTS4 index for offline search.
   - `COMPLETION`: Finalizes item record and trims event log history.

---

## Architecture Overview

```text
app/src/main/java/app/reelix/
├── core/                  # Clock, privacy-safe logging, InstagramUrlParser, ShareIntentReader, SafeUrlOpener
├── domain/
│   ├── model/             # ContentType, ProcessingStatus, StageStatus, ProcessingStage, StatusFilter, models
│   └── usecase/           # ShareIntakeUseCase (URL & media intake, storage validation, WorkManager scheduling)
├── data/
│   ├── db/                # ReelixDatabase (Room v2 + FTS4, MIGRATION_1_2)
│   │   ├── entity/        # SavedItemEntity, ProcessingJobEntity, ProcessingResultEntity, SavedItemFtsEntity, etc.
│   │   └── dao/           # SavedItemDao, ProcessingDao, TagDao
│   ├── files/             # MediaStorage (AppPrivateMediaStorage), ThumbnailStore, AndroidStorageInspector
│   ├── media/             # AudioExtractor (AndroidMediaCodecAudioExtractor), SimplePcmResampler
│   ├── repository/        # SavedItemRepository, SearchIndexer, TagNormalizer, FtsQueryBuilder
│   └── settings/          # DataStoreSettingsRepository, KeystoreSecretStore (AES-256-GCM)
├── integrations/
│   ├── acquisition/       # ContentAcquisitionProvider + UrlOnlyAcquisitionProvider
│   ├── metadata/          # MetadataExtractor + OpenGraphMetadataExtractor
│   ├── transcription/     # TranscriptionModelManager (LocalWhisperModelManager), LocalWhisperTranscriptionProvider
│   └── analysis/          # ContentAnalysisProvider + OpenAiCompatibleAnalysisProvider
├── processing/            # ProcessingPipeline, StageHandlers (11 stages), ProcessingWorker, ProcessingScheduler
├── di/                    # AppContainer / DefaultAppContainer
└── ui/                    # Jetpack Compose UI (Material 3), HomeScreen, SavedItemsScreen, ItemDetailScreen, SearchScreen, SettingsScreen
```

---

## Offline Transcription Setup (Whisper)

Reelix runs speech recognition locally on-device. No Whisper model is bundled into the APK to keep download size minimal (~18MB debug APK).

### Supported Models
- **Format:** GGML or GGUF binary models (`.bin`) compatible with Whisper.
- **Recommended:**
  - `ggml-tiny.bin` (~75 MB): fastest inference, minimal RAM.
  - `ggml-base.bin` (~142 MB): great balance of accuracy and speed for phone processors.

### How to Import
1. Download a Whisper model (e.g., from Hugging Face or the whisper.cpp repository).
2. Transfer or download the `.bin` file to your Android device.
3. In Reelix, navigate to **Settings → Offline speech recognition**.
4. Tap **Import Whisper model (.bin)** and pick the file using Android's system file picker.
5. Reelix validates the file header, checks available storage, copies the model to app-private storage, and updates the status to **Model ready**.

---

## Self-Hosted AI Enrichment Setup (Ollama)

1. Start Ollama on your machine or home server:
   ```bash
   ollama pull llama3.2
   ollama serve
   ```
2. If testing on an Android Emulator:
   - Use `http://10.0.2.2:11434/v1` as the Base URL.
3. If testing on a Physical Device connected via USB:
   - Reverse the port via ADB:
     ```bash
     adb reverse tcp:11434 tcp:11434
     ```
   - Use `http://127.0.0.1:11434/v1` as the Base URL.
4. In Reelix, navigate to **Settings → Optional AI integration**:
   - Enable AI summarization.
   - Enter **Base URL** and **Model name** (`llama3.2`).
   - Tap **Test connection** to verify connectivity with your local server.
   - Tap **Save AI config**.

---

## Build & Test Instructions

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Run Automated Unit & UI Tests
```bash
./gradlew :app:testDebugUnitTest
```
Runs 39 tests covering:
- Share intent parsing (URLs, single media, multiple media, clip data).
- Media storage streaming, size bounds, and deletion cascading.
- Native audio extraction pipeline.
- Offline transcription lifecycle and fallback semantics.
- Structured 9-field AI analysis and live endpoint connection testing.
- Offline SQLite FTS4 full-text search and filtering.
- Room database schema migration from v1 to v2.
- Compose end-to-end user navigation flows.

### Install on Device / Emulator
```bash
./gradlew :app:installDebug
adb shell am start -n app.reelix/.MainActivity
```

### Test Media Share Intent via ADB
```bash
adb shell am start -a android.intent.action.SEND \
  -t "video/mp4" \
  --eu android.intent.extra.STREAM "content://media/external/video/media/1" \
  -n app.reelix/.ShareReceiverActivity
```
