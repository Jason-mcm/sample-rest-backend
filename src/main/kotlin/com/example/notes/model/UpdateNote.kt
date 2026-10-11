package com.example.notes.model

// Nullable fields allow clear validation errors for missing or explicit-null values.
data class UpdateNote(
    val title: String? = null,
    val content: String? = null,
    val version: Long? = null,
)
