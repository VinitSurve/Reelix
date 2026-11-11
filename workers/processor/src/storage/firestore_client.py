"""Cloud Firestore coordinator for atomic distributed job claiming, leasing, and stage tracking.

Implements transactional job coordination on top of Google Cloud Firestore (Spark free plan).
Google Drive is used for persistent media/artifact storage, while Firestore guarantees
atomic compare-and-swap leasing, heartbeats, and stage attempt tracking.
"""

from __future__ import annotations

import logging
import os
import time
from typing import Any, Dict, List, Optional

from google.cloud import firestore
from google.cloud.firestore import Client, DocumentReference, Transaction

from ..models import ArtifactRef, JobManifest, JobStatus, StageDetail, StageStatus

logger = logging.getLogger("reelix_firestore")

DEFAULT_COLLECTION = "jobs"
DEFAULT_LEASE_TIMEOUT_MS = 900_000  # 15 minutes


class FirestoreJobCoordinator:
    """Coordinates job lifecycle, leasing, and status transitions via Cloud Firestore."""

    def __init__(
        self,
        project_id: Optional[str] = None,
        credentials: Optional[Any] = None,
        collection_name: str = DEFAULT_COLLECTION,
        client: Optional[Client] = None,
    ):
        self.collection_name = collection_name
        if client is not None:
            self.client = client
        else:
            proj = project_id or os.getenv("FIRESTORE_PROJECT_ID") or os.getenv("GOOGLE_CLOUD_PROJECT")
            if proj:
                self.client = firestore.Client(project=proj, credentials=credentials)
            else:
                self.client = firestore.Client(credentials=credentials)

    def _doc(self, job_id: str) -> DocumentReference:
        return self.client.collection(self.collection_name).document(job_id)

    def get_job(self, job_id: str) -> Optional[JobManifest]:
        """Fetches the current job manifest by ID."""
        doc_ref = self._doc(job_id)
        snapshot = doc_ref.get()
        if not snapshot.exists:
            return None
        data = snapshot.to_dict() or {}
        return JobManifest.from_dict(data)

    def submit_job(self, manifest: JobManifest, safe_resubmit: bool = True) -> bool:
        """Submits or updates a job document in Firestore.

        If safe_resubmit is True and the document already exists in PROCESSING or COMPLETED
        state, active processing status and stage results are preserved, updating only
        user-editable metadata (notes, category, tags, caption).
        """
        doc_ref = self._doc(manifest.item_id)
        now_ms = int(time.time() * 1000)

        transaction = self.client.transaction()

        @firestore.transactional
        def _submit_tx(tx: Transaction) -> bool:
            snapshot = doc_ref.get(transaction=tx)
            if snapshot.exists and safe_resubmit:
                existing = snapshot.to_dict() or {}
                status = existing.get("status")
                if status in (JobStatus.PROCESSING.value, JobStatus.COMPLETED.value):
                    logger.info(
                        "Job %s is currently '%s'; preserving state and updating user metadata only",
                        manifest.item_id,
                        status,
                    )
                    updates = {
                        "caption": manifest.caption,
                        "user_notes": manifest.user_notes,
                        "category": manifest.category,
                        "user_tags": manifest.user_tags,
                        "record_revision": (existing.get("record_revision") or 0) + 1,
                        "updated_at": now_ms,
                    }
                    tx.update(doc_ref, updates)
                    return True

            data = manifest.to_dict()
            data["updated_at"] = now_ms
            if snapshot.exists:
                existing = snapshot.to_dict() or {}
                data["record_revision"] = (existing.get("record_revision") or 0) + 1
            else:
                data["record_revision"] = max(data.get("record_revision", 1), 1)

            tx.set(doc_ref, data)
            return True

        try:
            return _submit_tx(transaction)
        except Exception as e:
            logger.error("Failed to submit job %s to Firestore: %s", manifest.item_id, e)
            return False

    def claim_job(
        self,
        job_id: str,
        worker_id: str,
        lease_duration_ms: int = DEFAULT_LEASE_TIMEOUT_MS,
    ) -> Optional[JobManifest]:
        """Atomically claims a job using a Firestore transaction.

        A job is claimable if:
        1. status is QUEUED
        2. status is WAITING_RETRY and now >= next_retry_at
        3. status is PROCESSING and now > lease_expires_at (reclaiming abandoned lease)
        """
        doc_ref = self._doc(job_id)
        now_ms = int(time.time() * 1000)
        transaction = self.client.transaction()

        @firestore.transactional
        def _claim_tx(tx: Transaction) -> Optional[Dict[str, Any]]:
            snapshot = doc_ref.get(transaction=tx)
            if not snapshot.exists:
                return None

            data = snapshot.to_dict() or {}
            status = data.get("status")
            lease_expires_at = data.get("lease_expires_at") or 0
            next_retry_at = data.get("next_retry_at") or 0

            eligible = False
            if status == JobStatus.QUEUED.value:
                eligible = True
            elif status == JobStatus.WAITING_RETRY.value:
                if now_ms >= next_retry_at:
                    eligible = True
                else:
                    logger.info("Job %s in WAITING_RETRY is not yet due (due at %d, now is %d)", job_id, next_retry_at, now_ms)
            elif status == JobStatus.PROCESSING.value:
                if now_ms > lease_expires_at:
                    logger.warning(
                        "Reclaiming stale lease for job %s from previous worker %s (expired %d ms ago)",
                        job_id,
                        data.get("claimed_by"),
                        now_ms - lease_expires_at,
                    )
                    eligible = True
                else:
                    logger.info(
                        "Job %s is actively claimed by %s (lease expires in %d ms)",
                        job_id,
                        data.get("claimed_by"),
                        lease_expires_at - now_ms,
                    )

            if not eligible:
                return None

            expires_at = now_ms + lease_duration_ms
            new_revision = (data.get("record_revision") or 0) + 1
            updates = {
                "status": JobStatus.PROCESSING.value,
                "claimed_by": worker_id,
                "claimed_at": now_ms,
                "lease_expires_at": expires_at,
                "lease_heartbeat_at": now_ms,
                "lease_timeout_ms": lease_duration_ms,
                "record_revision": new_revision,
                "updated_at": now_ms,
            }
            tx.update(doc_ref, updates)
            data.update(updates)
            return data

        try:
            claimed_data = _claim_tx(transaction)
            if claimed_data:
                logger.info("Successfully claimed job %s for worker %s", job_id, worker_id)
                return JobManifest.from_dict(claimed_data)
            return None
        except Exception as e:
            logger.error("Error during atomic claim for job %s: %s", job_id, e)
            return None

    def renew_lease(
        self,
        job_id: str,
        worker_id: str,
        extension_ms: int = DEFAULT_LEASE_TIMEOUT_MS,
    ) -> bool:
        """Heartbeat renewal for a currently claimed job.

        Returns True if lease was successfully renewed; False if lost or expired.
        """
        doc_ref = self._doc(job_id)
        now_ms = int(time.time() * 1000)
        transaction = self.client.transaction()

        @firestore.transactional
        def _renew_tx(tx: Transaction) -> bool:
            snapshot = doc_ref.get(transaction=tx)
            if not snapshot.exists:
                return False

            data = snapshot.to_dict() or {}
            if data.get("status") != JobStatus.PROCESSING.value:
                logger.warning("Cannot renew lease for %s: status is '%s', not PROCESSING", job_id, data.get("status"))
                return False

            if data.get("claimed_by") != worker_id:
                logger.warning(
                    "Cannot renew lease for %s: claimed_by is '%s', expected '%s'",
                    job_id,
                    data.get("claimed_by"),
                    worker_id,
                )
                return False

            lease_expires_at = data.get("lease_expires_at") or 0
            if lease_expires_at < now_ms:
                logger.warning("Cannot renew lease for %s: lease already expired at %d", job_id, lease_expires_at)
                return False

            new_expires_at = now_ms + extension_ms
            updates = {
                "lease_expires_at": new_expires_at,
                "lease_heartbeat_at": now_ms,
                "record_revision": (data.get("record_revision") or 0) + 1,
                "updated_at": now_ms,
            }
            tx.update(doc_ref, updates)
            return True

        try:
            return _renew_tx(transaction)
        except Exception as e:
            logger.error("Error during lease renewal for job %s: %s", job_id, e)
            return False

    def release_lease(self, job_id: str, worker_id: str) -> bool:
        """Releases the lease on a job and resets it to QUEUED status."""
        doc_ref = self._doc(job_id)
        now_ms = int(time.time() * 1000)
        transaction = self.client.transaction()

        @firestore.transactional
        def _release_tx(tx: Transaction) -> bool:
            snapshot = doc_ref.get(transaction=tx)
            if not snapshot.exists:
                return False

            data = snapshot.to_dict() or {}
            if data.get("claimed_by") != worker_id:
                logger.warning("Cannot release lease for %s: owner is '%s', caller is '%s'", job_id, data.get("claimed_by"), worker_id)
                return False

            updates = {
                "status": JobStatus.QUEUED.value,
                "claimed_by": None,
                "claimed_at": None,
                "lease_expires_at": None,
                "lease_heartbeat_at": None,
                "record_revision": (data.get("record_revision") or 0) + 1,
                "updated_at": now_ms,
            }
            tx.update(doc_ref, updates)
            return True

        try:
            return _release_tx(transaction)
        except Exception as e:
            logger.error("Error releasing lease for job %s: %s", job_id, e)
            return False

    def update_stage_progress(
        self,
        job_id: str,
        worker_id: str,
        stage: str,
        status: StageStatus,
        artifact: Optional[ArtifactRef] = None,
        error: Optional[str] = None,
    ) -> bool:
        """Updates stage progress within an atomic lease-checked transaction."""
        doc_ref = self._doc(job_id)
        now_ms = int(time.time() * 1000)
        transaction = self.client.transaction()

        @firestore.transactional
        def _stage_tx(tx: Transaction) -> bool:
            snapshot = doc_ref.get(transaction=tx)
            if not snapshot.exists:
                return False

            data = snapshot.to_dict() or {}
            if data.get("status") != JobStatus.PROCESSING.value:
                logger.warning("Aborting stage update for %s: status is '%s'", job_id, data.get("status"))
                return False

            if data.get("claimed_by") != worker_id:
                logger.warning("Aborting stage update for %s: lease owned by '%s'", job_id, data.get("claimed_by"))
                return False

            lease_expires_at = data.get("lease_expires_at") or 0
            if lease_expires_at < now_ms:
                logger.warning("Aborting stage update for %s: lease expired at %d", job_id, lease_expires_at)
                return False

            stages = data.get("stages", {})
            stages[stage] = status.value

            stage_details = data.get("stage_details", {})
            detail = stage_details.get(stage, {})
            detail["status"] = status.value
            detail["attempts"] = (detail.get("attempts") or 0) + 1
            if status == StageStatus.RUNNING:
                detail["started_at"] = now_ms
            elif status in (StageStatus.SUCCEEDED, StageStatus.SKIPPED):
                detail["completed_at"] = now_ms
                detail["last_error"] = None
            elif status == StageStatus.FAILED:
                detail["completed_at"] = now_ms
                detail["last_error"] = error
            stage_details[stage] = detail

            updates: Dict[str, Any] = {
                "stages": stages,
                "stage_details": stage_details,
                "lease_heartbeat_at": now_ms,
                "record_revision": (data.get("record_revision") or 0) + 1,
                "updated_at": now_ms,
            }

            if artifact:
                artifacts = data.get("artifacts", {})
                artifacts[stage] = artifact.to_dict()
                updates["artifacts"] = artifacts

            tx.update(doc_ref, updates)
            return True

        try:
            return _stage_tx(transaction)
        except Exception as e:
            logger.error("Error updating stage %s for job %s: %s", stage, job_id, e)
            return False

    def complete_job(
        self,
        job_id: str,
        worker_id: str,
        manifest: JobManifest,
    ) -> bool:
        """Marks a job COMPLETED or PARTIAL and persists final results."""
        doc_ref = self._doc(job_id)
        now_ms = int(time.time() * 1000)
        transaction = self.client.transaction()

        @firestore.transactional
        def _complete_tx(tx: Transaction) -> bool:
            snapshot = doc_ref.get(transaction=tx)
            if not snapshot.exists:
                return False

            data = snapshot.to_dict() or {}
            if data.get("claimed_by") != worker_id:
                logger.warning("Cannot complete job %s: claimed_by is '%s'", job_id, data.get("claimed_by"))
                return False

            lease_expires_at = data.get("lease_expires_at") or 0
            if lease_expires_at < now_ms:
                logger.warning("Cannot complete job %s: lease expired", job_id)
                return False

            updated_data = manifest.to_dict()
            updated_data["lease_expires_at"] = None
            updated_data["record_revision"] = (data.get("record_revision") or 0) + 1
            updated_data["updated_at"] = now_ms
            tx.update(doc_ref, updated_data)
            return True

        try:
            return _complete_tx(transaction)
        except Exception as e:
            logger.error("Error completing job %s: %s", job_id, e)
            return False

    def fail_job(
        self,
        job_id: str,
        worker_id: str,
        error: str,
        is_retryable: bool = True,
        next_retry_at: Optional[int] = None,
        max_retries: int = 3,
    ) -> bool:
        """Transitions job to WAITING_RETRY or FAILED."""
        doc_ref = self._doc(job_id)
        now_ms = int(time.time() * 1000)
        transaction = self.client.transaction()

        @firestore.transactional
        def _fail_tx(tx: Transaction) -> bool:
            snapshot = doc_ref.get(transaction=tx)
            if not snapshot.exists:
                return False

            data = snapshot.to_dict() or {}
            if data.get("claimed_by") != worker_id:
                logger.warning("Cannot fail job %s: lease claimed by '%s'", job_id, data.get("claimed_by"))
                return False

            current_retries = (data.get("retry_count") or 0)
            if is_retryable and (current_retries + 1) < max_retries:
                status = JobStatus.WAITING_RETRY.value
                new_retry_count = current_retries + 1
            else:
                status = JobStatus.FAILED.value
                new_retry_count = current_retries + 1 if is_retryable else current_retries
                next_retry_at_val = None

            next_retry_at_val = next_retry_at if status == JobStatus.WAITING_RETRY.value else None

            updates = {
                "status": status,
                "error_message": error,
                "retry_count": new_retry_count,
                "next_retry_at": next_retry_at_val,
                "claimed_by": None,
                "lease_expires_at": None,
                "record_revision": (data.get("record_revision") or 0) + 1,
                "updated_at": now_ms,
            }
            tx.update(doc_ref, updates)
            return True

        try:
            return _fail_tx(transaction)
        except Exception as e:
            logger.error("Error setting failure state for job %s: %s", job_id, e)
            return False

    def list_queued_jobs(self, limit: int = 10) -> List[JobManifest]:
        """Lists candidate jobs eligible for processing from Firestore.

        Retrieves:
        1. Jobs with status == QUEUED
        2. Jobs with status == WAITING_RETRY (filtered by next_retry_at <= now)
        3. Jobs with status == PROCESSING (filtered by lease_expires_at < now)
        """
        now_ms = int(time.time() * 1000)
        col = self.client.collection(self.collection_name)
        eligible_manifests: List[JobManifest] = []

        try:
            # 1. QUEUED jobs
            queued_docs = col.where("status", "==", JobStatus.QUEUED.value).limit(limit).stream()
            for doc in queued_docs:
                data = doc.to_dict()
                eligible_manifests.append(JobManifest.from_dict(data))
                if len(eligible_manifests) >= limit:
                    return eligible_manifests

            # 2. WAITING_RETRY jobs
            retry_docs = col.where("status", "==", JobStatus.WAITING_RETRY.value).limit(limit).stream()
            for doc in retry_docs:
                data = doc.to_dict()
                next_retry = data.get("next_retry_at") or 0
                if now_ms >= next_retry:
                    eligible_manifests.append(JobManifest.from_dict(data))
                    if len(eligible_manifests) >= limit:
                        return eligible_manifests

            # 3. Abandoned / expired PROCESSING jobs
            processing_docs = col.where("status", "==", JobStatus.PROCESSING.value).limit(limit).stream()
            for doc in processing_docs:
                data = doc.to_dict()
                lease_expires = data.get("lease_expires_at") or 0
                if now_ms > lease_expires:
                    logger.warning("Found expired lease for job %s (expired %d ms ago)", doc.id, now_ms - lease_expires)
                    eligible_manifests.append(JobManifest.from_dict(data))
                    if len(eligible_manifests) >= limit:
                        return eligible_manifests

        except Exception as e:
            logger.error("Error listing queued jobs from Firestore: %s", e)

        return eligible_manifests

