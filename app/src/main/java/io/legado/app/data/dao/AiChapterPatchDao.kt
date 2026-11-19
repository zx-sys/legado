package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiChapterPatch

@Dao
interface AiChapterPatchDao {
    @Query("select * from aiChapterPatches where bookUrl = :bookUrl")
    fun getByBook(bookUrl: String): List<AiChapterPatch>

    @Query(
        "select * from aiChapterPatches where bookUrl = :bookUrl and chapterIndex = :index limit 1"
    )
    fun getChapter(bookUrl: String, index: Int): AiChapterPatch?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(patch: AiChapterPatch)

    @Query("delete from aiChapterPatches where bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)

    @Query("select count(*) from aiChapterPatches where bookUrl = :bookUrl and appliedAt > 0")
    fun appliedCount(bookUrl: String): Int
}
