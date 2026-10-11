package com.example.notes.model

data class MergeConflictResponse(
    val status: Int = 409,
    val code: String = "MERGE_CONFLICT",
    val message: String = "Overlapping edits require manual resolution; PUT the resolved note using current.version",
    val base: RevisionResponse,
    val current: NoteResponse,
    val draft: MergeNote,
    val conflictingFields: List<String>,
    val mergedTitle: String?,
    val mergedContent: String?,
)
