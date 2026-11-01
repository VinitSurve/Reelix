package app.reelix.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.reelix.data.db.entity.SavedItemTagEntity
import app.reelix.data.db.entity.TagEntity
import app.reelix.domain.model.TagSource
import kotlinx.coroutines.flow.Flow

data class TagWithSourceRow(val id: Long, val name: String, val source: TagSource)

data class TagCountRow(val name: String, val count: Int)

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefIgnore(ref: SavedItemTagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefReplace(ref: SavedItemTagEntity)

    @Query("DELETE FROM saved_item_tags WHERE savedItemId = :itemId AND source = :source")
    suspend fun deleteRefsBySource(itemId: String, source: TagSource)

    @Query("DELETE FROM saved_item_tags WHERE savedItemId = :itemId AND tagId = :tagId")
    suspend fun deleteRef(itemId: String, tagId: Long)

    @Query(
        """
        SELECT t.id AS id, t.name AS name, st.source AS source FROM saved_item_tags st
        JOIN tags t ON t.id = st.tagId WHERE st.savedItemId = :itemId ORDER BY t.name
        """,
    )
    suspend fun tagsForItem(itemId: String): List<TagWithSourceRow>

    @Query(
        """
        SELECT t.id AS id, t.name AS name, st.source AS source FROM saved_item_tags st
        JOIN tags t ON t.id = st.tagId WHERE st.savedItemId = :itemId ORDER BY t.name
        """,
    )
    fun observeTagsForItem(itemId: String): Flow<List<TagWithSourceRow>>

    @Query(
        """
        SELECT t.name AS name, COUNT(st.savedItemId) AS count FROM tags t
        JOIN saved_item_tags st ON st.tagId = t.id
        GROUP BY t.id ORDER BY count DESC, t.name ASC LIMIT :limit
        """,
    )
    fun observeTopTags(limit: Int): Flow<List<TagCountRow>>

    @Query("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tagId FROM saved_item_tags)")
    suspend fun deleteOrphanTags()
}

