package com.example.notes.model

import java.time.Instant
import java.util.UUID

data class NoteResponse(
    val id: UUID,
    val teamId: UUID,
    val title: String,
    val content: String,
    val createdBy: UUID,
    val updatedBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)
