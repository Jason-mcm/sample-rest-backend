package com.example.notes.model

// Nullable fields allow clear validation errors for missing or explicit-null values.
data class CreateNote(
    val title: String? = null,
    val content: String? = null,
)
