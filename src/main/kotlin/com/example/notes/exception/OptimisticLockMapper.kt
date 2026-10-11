package com.example.notes.exception

import com.example.notes.model.ApiError
import jakarta.persistence.OptimisticLockException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class OptimisticLockMapper : ExceptionMapper<OptimisticLockException> {
    override fun toResponse(error: OptimisticLockException): Response {
        return Response.status(409)
            .entity(ApiError(409, "Note changed; fetch the latest version and retry"))
            .build()
    }
}
