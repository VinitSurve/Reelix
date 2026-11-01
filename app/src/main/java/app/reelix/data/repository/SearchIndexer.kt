package app.reelix.data.repository

import app.reelix.data.db.ReelixDatabase
import app.reelix.data.db.entity.SavedItemFtsEntity

/**
 * Keeps the FTS4 table in sync with an item's searchable data.
 * Covers title, caption, transcript, short/detailed summaries, concepts, key takeaways,
 * actionable steps, keywords, tags, categories and personal notes.
 */
class SearchIndexer(private val db: ReelixDatabase) {

    suspend fun reindex(itemId: String) {
        val searchDao = db.searchDao()
        searchDao.deleteForItem(itemId)
        val item = db.savedItemDao().getById(itemId) ?: return
        val tags = db.tagDao().tagsForItem(itemId)
        val results = db.processingDao().resultsFor(itemId)
        val transcripts = results.mapNotNull { it.transcript }
        val analysis = results.firstOrNull { it.summary != null || it.mainTopic != null }

        val conceptsText = join(
            analysis?.importantConcepts,
            analysis?.keyTakeaways,
        )

        searchDao.insert(
            SavedItemFtsEntity(
                itemId = itemId,
                title = join(item.title, item.extractedTitle, item.authorName, item.mediaFileName, analysis?.mainTopic),
                caption = join(item.caption, item.userCaption),
                transcript = join(item.userTranscript, *transcripts.toTypedArray()),
                summary = join(analysis?.summary),
                detailedSummary = join(analysis?.detailedSummary),
                tags = tags.joinToString(" ") { it.name },
                category = join(item.category, analysis?.category),
                notes = join(item.userNotes),
                keywords = join(analysis?.keywords?.replace(',', ' '), item.contentType.label),
                concepts = conceptsText,
                actionableSteps = join(analysis?.actionableSteps),
            ),
        )
    }

    suspend fun reindexAll() {
        db.savedItemDao().allIds().forEach { reindex(it) }
    }

    private fun join(vararg parts: String?): String =
        parts.filterNot { it.isNullOrBlank() }.joinToString("\n")
}
