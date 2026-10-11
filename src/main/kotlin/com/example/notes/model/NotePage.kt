package com.example.notes.model

data class NotePage(
    val items: List<NoteResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
)
