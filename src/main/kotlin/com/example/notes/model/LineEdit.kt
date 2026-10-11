package com.example.notes.model

// Half-open line range in the original content, replaced by the supplied lines.
internal data class LineEdit(
    val start: Int,
    val end: Int,
    val replacement: List<String>,
)
