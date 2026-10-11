package com.example.notes.exception

import com.example.notes.model.ApiError
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class HttpExceptionMapper : ExceptionMapper<WebApplicationException> {
    override fun toResponse(error: WebApplicationException): Response {
        val status = error.response.status
        return Response.fromResponse(error.response)
            .entity(ApiError(status, if (status == 400) "Invalid request" else "Request failed"))
            .build()
    }
}
