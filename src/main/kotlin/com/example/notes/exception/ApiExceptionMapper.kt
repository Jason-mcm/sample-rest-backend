package com.example.notes.exception

import com.example.notes.model.ApiError
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class ApiExceptionMapper : ExceptionMapper<ApiException> {
    override fun toResponse(error: ApiException): Response {
        return Response.status(error.status)
            .entity(ApiError(error.status, error.message))
            .build()
    }
}
