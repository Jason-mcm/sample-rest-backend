package com.example.notes.exception

import com.example.notes.model.MergeConflictResponse

class MergeConflictException(
    val response: MergeConflictResponse,
) : RuntimeException(response.message)
