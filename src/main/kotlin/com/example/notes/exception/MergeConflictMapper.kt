package com.example.notes.exception

import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class MergeConflictMapper : ExceptionMapper<MergeConflictException> {
    override fun toResponse(error: MergeConflictException): Response {
        return Response.status(409)
            .entity(error.response)
            .build()
    }
}
