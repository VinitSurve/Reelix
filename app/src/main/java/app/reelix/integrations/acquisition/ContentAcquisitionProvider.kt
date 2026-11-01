package app.reelix.integrations.acquisition

import app.reelix.domain.model.InstagramLink

/**
 * Obtains the underlying media (video/audio/images) for a saved link.
 *
 * Android's share sheet gives Reelix only a URL. Instagram offers no public, credential-free API
 * that returns media for arbitrary Reels, and Reelix deliberately does not scrape or bypass
 * authentication. Future legitimate providers (e.g. a user-authorised Meta Graph API integration
 * for the user's *own* content, or media the user shares as a file) plug in here.
 */
interface ContentAcquisitionProvider {
    val id: String
    suspend fun acquire(link: InstagramLink): AcquisitionOutcome
}

sealed interface AcquisitionOutcome {
    /** Media is available locally for later stages. */
    data class MediaAvailable(val localMediaPath: String, val mimeType: String) : AcquisitionOutcome

    /** No media can be obtained legitimately; the item continues as a link with metadata. */
    data class UrlOnly(val reason: String) : AcquisitionOutcome

    data class TransientFailure(val error: String) : AcquisitionOutcome
    data class PermanentFailure(val error: String) : AcquisitionOutcome
}

/** The honest default: keeps the URL and reports that media is not accessible. */
class UrlOnlyAcquisitionProvider : ContentAcquisitionProvider {
    override val id: String = "url-only"

    override suspend fun acquire(link: InstagramLink): AcquisitionOutcome =
        AcquisitionOutcome.UrlOnly(
            "Instagram shares only a link with other apps. Media download is not available, so the item is kept as a link.",
        )
}

