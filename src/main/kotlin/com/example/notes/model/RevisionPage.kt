package com.example.notes.model

data class RevisionPage(
    val items: List<RevisionResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
)
