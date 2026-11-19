package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiSourceProfileEntity

@Dao
interface AiSourceProfileDao {
    @Query("select * from aiSourceProfiles where sourceUrl = :sourceUrl limit 1")
    fun get(sourceUrl: String): AiSourceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: AiSourceProfileEntity)

    @Query("delete from aiSourceProfiles where sourceUrl = :sourceUrl")
    fun delete(sourceUrl: String)
}
