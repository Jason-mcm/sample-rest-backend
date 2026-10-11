package com.example.notes.model

import java.time.Instant
import java.util.UUID

data class RevisionResponse(
    val noteId: UUID,
    val teamId: UUID,
    val version: Long,
    val title: String,
    val content: String,
    val editedBy: UUID,
    val editedAt: Instant,
)
