"""Unit tests for FirestoreJobCoordinator covering atomic claims, leasing, heartbeats, and safe resubmission."""

import time
import unittest
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock

from workers.processor.src.models import (
    ArtifactRef,
    JobManifest,
    JobStatus,
    MediaInfo,
    StageStatus,
)
from workers.processor.src.storage.firestore_client import FirestoreJobCoordinator


class InMemorySnapshot:
    def __init__(self, doc_id: str, data: Optional[Dict[str, Any]]):
        self.id = doc_id
        self._data = data

    @property
    def exists(self) -> bool:
        return self._data is not None

    def to_dict(self) -> Optional[Dict[str, Any]]:
        return dict(self._data) if self._data is not None else None


class InMemoryDocumentReference:
    def __init__(self, doc_id: str, store: Dict[str, Dict[str, Any]]):
        self.id = doc_id
        self._store = store

    def get(self, transaction=None) -> InMemorySnapshot:
        data = self._store.get(self.id)
        return InMemorySnapshot(self.id, data)

    def set(self, data: Dict[str, Any]):
        self._store[self.id] = dict(data)

    def update(self, updates: Dict[str, Any]):
        if self.id not in self._store:
            raise KeyError(f"Document {self.id} does not exist")
        self._store[self.id].update(updates)


class InMemoryCollectionReference:
    def __init__(self, store: Dict[str, Dict[str, Any]]):
        self._store = store

    def document(self, doc_id: str) -> InMemoryDocumentReference:
        return InMemoryDocumentReference(doc_id, self._store)

    def where(self, field: str, op: str, value: Any):
        class Query:
            def __init__(self, store: Dict[str, Dict[str, Any]], field: str, val: Any):
                self._store = store
                self._field = field
                self._val = val
                self._limit = 100

            def limit(self, count: int):
                self._limit = count
                return self

            def stream(self):
                count = 0
                for doc_id, data in self._store.items():
                    if data.get(self._field) == self._val:
                        yield InMemorySnapshot(doc_id, data)
                        count += 1
                        if count >= self._limit:
                            break

        return Query(self._store, field, value)


class InMemoryTransaction:
    def __init__(self, store: Dict[str, Dict[str, Any]]):
        self._store = store
        self._read_only = False
        self._max_attempts = 5
        self._id = b"test-tx-id"

    def _clean_up(self):
        pass

    def _begin(self, retry_id=None):
        pass

    def _commit(self):
        pass

    def _rollback(self):
        pass

    def get(self, doc_ref: InMemoryDocumentReference) -> InMemorySnapshot:
        return doc_ref.get()

    def set(self, doc_ref: InMemoryDocumentReference, data: Dict[str, Any]):
        self._store[doc_ref.id] = dict(data)

    def update(self, doc_ref: InMemoryDocumentReference, updates: Dict[str, Any]):
        if doc_ref.id not in self._store:
            raise KeyError(f"Document {doc_ref.id} does not exist")
        self._store[doc_ref.id].update(updates)


class MockFirestoreClient:
    def __init__(self):
        self._store: Dict[str, Dict[str, Any]] = {}

    def collection(self, name: str) -> InMemoryCollectionReference:
        return InMemoryCollectionReference(self._store)

    def transaction(self) -> InMemoryTransaction:
        return InMemoryTransaction(self._store)


class TestFirestoreCoordinator(unittest.TestCase):
    def setUp(self):
        self.mock_client = MockFirestoreClient()
        self.coordinator = FirestoreJobCoordinator(client=self.mock_client)

    def _sample_manifest(self, item_id: str = "item-test-1", status: JobStatus = JobStatus.QUEUED) -> JobManifest:
        now = int(time.time() * 1000)
        return JobManifest(
            item_id=item_id,
            source="SHARE",
            original_url="https://www.instagram.com/reel/123/",
            caption="Initial caption",
            user_notes="Initial note",
            category="Design",
            user_tags=["ui", "ux"],
            status=status,
            created_at=now,
            updated_at=now,
        )

    def test_submit_new_job(self):
        manifest = self._sample_manifest()
        success = self.coordinator.submit_job(manifest)
        self.assertTrue(success)

        stored = self.coordinator.get_job(manifest.item_id)
        self.assertIsNotNone(stored)
        self.assertEqual(stored.item_id, manifest.item_id)
        self.assertEqual(stored.status, JobStatus.QUEUED)
        self.assertEqual(stored.record_revision, 1)

    def test_safe_resubmit_preserves_active_processing_state(self):
        # Defect 3 regression test: resubmission should NOT clobber active processing
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)

        # Worker claims job
        claimed = self.coordinator.claim_job(manifest.item_id, "worker-1", lease_duration_ms=600_000)
        self.assertIsNotNone(claimed)
        self.assertEqual(claimed.status, JobStatus.PROCESSING)

        # Android user updates notes and resubmits
        resubmit_manifest = self._sample_manifest()
        resubmit_manifest.user_notes = "Updated note while processing"
        resubmit_manifest.user_tags = ["ui", "design", "figma"]
        success = self.coordinator.submit_job(resubmit_manifest, safe_resubmit=True)
        self.assertTrue(success)

        # Check stored state: status is STILL PROCESSING, but notes are updated
        stored = self.coordinator.get_job(manifest.item_id)
        self.assertEqual(stored.status, JobStatus.PROCESSING)
        self.assertEqual(stored.claimed_by, "worker-1")
        self.assertEqual(stored.user_notes, "Updated note while processing")
        self.assertEqual(stored.user_tags, ["ui", "design", "figma"])
        self.assertGreater(stored.record_revision, 1)

    def test_atomic_claim_success_and_collision_rejection(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)

        # Worker 1 claims job
        claim1 = self.coordinator.claim_job(manifest.item_id, "worker-1", lease_duration_ms=600_000)
        self.assertIsNotNone(claim1)
        self.assertEqual(claim1.status, JobStatus.PROCESSING)
        self.assertEqual(claim1.claimed_by, "worker-1")

        # Worker 2 attempts concurrent claim on the same job
        claim2 = self.coordinator.claim_job(manifest.item_id, "worker-2", lease_duration_ms=600_000)
        self.assertIsNone(claim2)  # Must be rejected

    def test_claim_expired_lease_recovery(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)

        # Worker 1 claims job with 1 millisecond lease
        now = int(time.time() * 1000)
        self.mock_client._store[manifest.item_id].update({
            "status": JobStatus.PROCESSING.value,
            "claimed_by": "crashed-worker",
            "lease_expires_at": now - 5000,  # Expired 5 seconds ago
        })

        # Worker 2 should successfully recover the stale job
        recovered = self.coordinator.claim_job(manifest.item_id, "worker-2", lease_duration_ms=600_000)
        self.assertIsNotNone(recovered)
        self.assertEqual(recovered.claimed_by, "worker-2")
        self.assertEqual(recovered.status, JobStatus.PROCESSING)

    def test_renew_lease_heartbeat(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)
        self.coordinator.claim_job(manifest.item_id, "worker-1", lease_duration_ms=600_000)

        # Valid worker renews lease
        renewed = self.coordinator.renew_lease(manifest.item_id, "worker-1", extension_ms=900_000)
        self.assertTrue(renewed)

        # Wrong worker fails to renew lease
        bad_worker_renew = self.coordinator.renew_lease(manifest.item_id, "worker-impostor")
        self.assertFalse(bad_worker_renew)

    def test_update_stage_progress_tracks_attempts_and_artifacts(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)
        self.coordinator.claim_job(manifest.item_id, "worker-1")

        # Update audio_extraction to RUNNING
        ok = self.coordinator.update_stage_progress(
            job_id=manifest.item_id,
            worker_id="worker-1",
            stage="audio_extraction",
            status=StageStatus.RUNNING,
        )
        self.assertTrue(ok)

        # Complete audio_extraction with artifact
        artifact = ArtifactRef(
            drive_file_id="drive-wav-123",
            mime_type="audio/wav",
            size_bytes=45000,
            content_hash="sha256:abc",
            name="audio.wav",
        )
        ok2 = self.coordinator.update_stage_progress(
            job_id=manifest.item_id,
            worker_id="worker-1",
            stage="audio_extraction",
            status=StageStatus.SUCCEEDED,
            artifact=artifact,
        )
        self.assertTrue(ok2)

        stored = self.coordinator.get_job(manifest.item_id)
        self.assertEqual(stored.stages.audio_extraction, StageStatus.SUCCEEDED)
        self.assertEqual(stored.get_stage_detail("audio_extraction").attempts, 2)
        self.assertIn("audio_extraction", stored.artifacts)
        self.assertEqual(stored.artifacts["audio_extraction"].drive_file_id, "drive-wav-123")

    def test_update_stage_rejected_when_lease_lost(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)
        self.coordinator.claim_job(manifest.item_id, "worker-1")

        # Expire lease artificially
        self.mock_client._store[manifest.item_id]["lease_expires_at"] = int(time.time() * 1000) - 1000

        # Worker attempts to update stage
        ok = self.coordinator.update_stage_progress(
            job_id=manifest.item_id,
            worker_id="worker-1",
            stage="transcription",
            status=StageStatus.RUNNING,
        )
        self.assertFalse(ok)

    def test_complete_job(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)
        self.coordinator.claim_job(manifest.item_id, "worker-1")

        stored = self.coordinator.get_job(manifest.item_id)
        stored.status = JobStatus.COMPLETED
        success = self.coordinator.complete_job(manifest.item_id, "worker-1", stored)
        self.assertTrue(success)

        final = self.coordinator.get_job(manifest.item_id)
        self.assertEqual(final.status, JobStatus.COMPLETED)

    def test_fail_job_with_retry_backoff(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)
        self.coordinator.claim_job(manifest.item_id, "worker-1")

        due_at = int(time.time() * 1000) + 60_000
        ok = self.coordinator.fail_job(
            job_id=manifest.item_id,
            worker_id="worker-1",
            error="Transient 503 error",
            is_retryable=True,
            next_retry_at=due_at,
            max_retries=3,
        )
        self.assertTrue(ok)

        stored = self.coordinator.get_job(manifest.item_id)
        self.assertEqual(stored.status, JobStatus.WAITING_RETRY)
        self.assertEqual(stored.retry_count, 1)
        self.assertEqual(stored.next_retry_at, due_at)

    def test_list_queued_jobs(self):
        manifest1 = self._sample_manifest("item-1")
        manifest2 = self._sample_manifest("item-2")
        manifest3 = self._sample_manifest("item-3")

        self.coordinator.submit_job(manifest1)
        self.coordinator.submit_job(manifest2)
        self.coordinator.submit_job(manifest3)

        # Claim item-2
        self.coordinator.claim_job("item-2", "worker-1")

        queued = self.coordinator.list_queued_jobs(limit=10)
        queued_ids = [j.item_id for j in queued]
        self.assertIn("item-1", queued_ids)
        self.assertIn("item-3", queued_ids)
        self.assertNotIn("item-2", queued_ids)

    def test_release_lease_resets_to_queued(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)
        self.coordinator.claim_job(manifest.item_id, "worker-1")

        released = self.coordinator.release_lease(manifest.item_id, "worker-1")
        self.assertTrue(released)

        stored = self.coordinator.get_job(manifest.item_id)
        self.assertEqual(stored.status, JobStatus.QUEUED)
        self.assertIsNone(stored.claimed_by)

    def test_release_lease_by_wrong_worker_fails(self):
        manifest = self._sample_manifest()
        self.coordinator.submit_job(manifest)
        self.coordinator.claim_job(manifest.item_id, "worker-1")

        released = self.coordinator.release_lease(manifest.item_id, "worker-impostor")
        self.assertFalse(released)

        stored = self.coordinator.get_job(manifest.item_id)
        self.assertEqual(stored.status, JobStatus.PROCESSING)
        self.assertEqual(stored.claimed_by, "worker-1")

    def test_concurrent_collision_simulation(self):
        # Two workers trying to claim the same QUEUED item
        manifest = self._sample_manifest("item-race")
        self.coordinator.submit_job(manifest)

        claim_alpha = self.coordinator.claim_job("item-race", "worker-alpha")
        self.assertIsNotNone(claim_alpha)
        self.assertEqual(claim_alpha.claimed_by, "worker-alpha")

        # Second worker immediately tries to claim
        claim_beta = self.coordinator.claim_job("item-race", "worker-beta")
        self.assertIsNone(claim_beta)

        # Third worker tries to claim
        claim_gamma = self.coordinator.claim_job("item-race", "worker-gamma")
        self.assertIsNone(claim_gamma)


if __name__ == "__main__":
    unittest.main()
