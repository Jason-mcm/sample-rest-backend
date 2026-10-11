package com.example.notes.controller

import com.example.notes.exception.ApiException

import com.example.notes.model.MergeNote
import com.example.notes.service.NoteService
import com.example.notes.service.UserService
import io.smallrye.common.annotation.Blocking
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@Blocking
@RequestMapping("/api/teams")
class NoteMergeController(
    private val service: NoteService,
    private val users: UserService,
) {
    @PostMapping("/{teamId}/notes/{noteId}/merge")
    fun merge(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
        @PathVariable("teamId") team: UUID,
        @PathVariable("noteId") id: UUID,
        @RequestBody request: MergeNote?,
    ) = service.merge(users.identity(user), team, id, request ?: throw ApiException(400, "Request body is required"))
}
