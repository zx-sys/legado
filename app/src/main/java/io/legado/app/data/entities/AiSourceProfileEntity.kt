package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "aiSourceProfiles")
data class AiSourceProfileEntity(
    @PrimaryKey var sourceUrl: String = "",
    var version: Int = 0,
    var confidence: Double = 0.0,
    var profileJson: String = "",
    var updatedAt: Long = 0
)
