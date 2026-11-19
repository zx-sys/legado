package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "aiChapterPatches",
    indices = [Index(value = ["bookUrl", "chapterIndex"], unique = true)]
)
data class AiChapterPatch(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    var bookUrl: String = "",
    var chapterIndex: Int = 0,
    var chapterUrl: String = "",
    var sourceHash: String = "",
    var appliedHash: String = "",
    var appliedTextHash: String = "",
    var status: String = "ok",
    var patchJson: String = "",
    var boundaryParagraph: Int = -1,
    var reason: String = "",
    var appliedProfileVersion: Int = 0,
    var restored: Boolean = false,
    var appliedAt: Long = 0,
    var updatedAt: Long = 0
)
