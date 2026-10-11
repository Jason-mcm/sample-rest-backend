package com.example.notes.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "note_revision")
class NoteRevision(

    @field:Column(name = "note_id", nullable = false)
    val noteId: UUID,

    @field:Column(name = "team_id", nullable = false)
    val teamId: UUID,

    @field:Column(nullable = false)
    val version: Long,

    @field:Column(nullable = false, columnDefinition = "text")
    val title: String,

    @field:Column(nullable = false, columnDefinition = "text")
    val content: String,

    @field:Column(name = "edited_by", nullable = false)
    val editedBy: UUID,

    @field:Column(name = "edited_at", nullable = false)
    val editedAt: Instant,

) {

    @field:Id
    val id: UUID = UUID.randomUUID()
}
