# Reelix — Engineering Agent Guidelines & Continuous Git Policy

This document defines the permanent development and version control policies for autonomous and pair-programming agents working on the Reelix repository (`VinitSurve/Reelix`).

---

## 1. Continuous Commit & Automatic Push Policy

**Permanent Rule:** Stop accumulating large batches of uncommitted changes or dozens of unpushed commits. Autonomous agents are authorized to commit and push completed, verified development milestones directly to GitHub without waiting for explicit per-commit authorization.

### When to Commit
Commit immediately upon completing and verifying any logical milestone:
- A new feature or stage is implemented and unit/integration tests pass.
- A bug or regression is fixed and verified with tests.
- A database schema migration is completed and verified.
- An architectural or distributed reliability improvement is established.
- A CI/CD workflow issue is corrected and verified.
- Documentation for a completed feature or system boundary is updated.

**Do NOT:**
- Create one enormous commit combining unrelated features across Android, Python, and CI/CD.
- Create trivial keystroke commits for intermediate typos.
- Create empty commits or WIP placeholder commits.
- Commit broken, failing, or unverified work.

### When to Push
Push to GitHub automatically after every successful logical milestone:
1. Implement the minimal logical change.
2. Run relevant tests (Android `./gradlew testDebugUnitTest`, Python `PYTHONPATH=. pytest workers/processor/tests`).
3. Inspect `git diff` for secrets and unintended changes.
4. Stage only relevant files with explicit file paths (`git add <file>...`).
5. Commit using Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `ci:`).
6. Push to the tracked upstream branch (`git push origin <branch>`).
7. Verify that the remote received the commit (`git status -sb`, `git rev-list --left-right --count @{u}...HEAD`).

---

## 2. Pre-Push Verification Checklist

Before pushing any commit, the agent MUST verify:
- [ ] **Tests Pass:** All relevant test suites succeed (0 failures).
- [ ] **Zero Secrets:** No API keys, OAuth tokens, client secrets, passwords, private keys, or `.env` files are staged.
- [ ] **Zero Build Bloat:** No `.apk`, `.aab`, `.class`, `.pyc`, `.db`, `.wav`, or `.mp4` build artifacts are staged.
- [ ] **No Accidental Overwrites:** Unrelated user changes or concurrent edits are preserved.
- [ ] **Monotonic History:** Never use `git push --force`, `git push --force-with-lease`, or `git reset --hard`.
- [ ] **Branch Alignment:** Push targets the correct tracked upstream branch (`main` or designated feature branch).

---

## 3. Architecture & Operational Invariants

1. **Single Production Worker:** GitHub Actions is the sole authorized production worker under concurrency group `reelix-processing` (`cancel-in-progress: false`, `queue: max`).
2. **Mac Worker Guard:** Local Mac worker execution against live Google Drive production queues is strictly disabled; local development must set `REELIX_MOCK_MODE=1`.
3. **Continuous Heartbeat Renewal:** Long operations renew the 15-minute lease every 30 seconds; lease preemption terminates pipeline execution immediately.
4. **Content-Addressable Artifact Verification:** Streaming chunked SHA-256 digests verify media and audio files before upload and after download. Corrupt files are unlinked immediately.
5. **Durable Crash Recovery:** All 9 durable pipeline stages recover without repeating completed expensive work.
6. **Honest Completion States:** Jobs without optional Gemini AI analysis complete honestly as `COMPLETED` ("Processed without AI analysis").
