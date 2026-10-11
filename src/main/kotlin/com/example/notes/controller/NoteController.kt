package com.example.notes.controller

import com.example.notes.exception.ApiException

import com.example.notes.model.CreateNote
import com.example.notes.model.UpdateNote
import com.example.notes.model.NoteResponse
import com.example.notes.service.UserService
import com.example.notes.service.NoteService

import io.smallrye.common.annotation.Blocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

@RestController
@Blocking
@RequestMapping("/api/teams")
class NoteController(
    private val service: NoteService,
    private val users: UserService,
) {
    @GetMapping("/{teamId}/notes")
    fun list(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
        @PathVariable("teamId") team: UUID,
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "size", defaultValue = "20") size: Int,
    ) = service.list(users.identity(user), team, page, size)

    @GetMapping("/{teamId}/notes/{noteId}")
    fun get(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
        @PathVariable("teamId") team: UUID,
        @PathVariable("noteId") id: UUID,
    ) = service.get(users.identity(user), team, id)

    @PostMapping("/{teamId}/notes")
    fun create(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
        @PathVariable("teamId") team: UUID,
        @RequestBody request: CreateNote?,
    ): ResponseEntity<NoteResponse> {
        val note = service.create(users.identity(user), team, request ?: throw ApiException(400, "Request body is required"))
        return ResponseEntity.created(URI.create("/api/teams/$team/notes/${note.id}")).body(note)
    }

    @PutMapping("/{teamId}/notes/{noteId}")
    fun update(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
        @PathVariable("teamId") team: UUID,
        @PathVariable("noteId") id: UUID,
        @RequestBody request: UpdateNote?,
    ) = service.update(users.identity(user), team, id, request ?: throw ApiException(400, "Request body is required"))

    @DeleteMapping("/{teamId}/notes/{noteId}")
    fun delete(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
        @PathVariable("teamId") team: UUID,
        @PathVariable("noteId") id: UUID,
        @RequestParam(value = "version", required = false) version: Long?,
    ): ResponseEntity<Void> {
        service.delete(users.identity(user), team, id, version)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{teamId}/notes/{noteId}/revisions")
    fun revisions(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
        @PathVariable("teamId") team: UUID,
        @PathVariable("noteId") id: UUID,
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "size", defaultValue = "20") size: Int,
    ) = service.revisions(users.identity(user), team, id, page, size)

    @GetMapping("/{teamId}/notes/{noteId}/revisions/{version}")
    fun revision(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
        @PathVariable("teamId") team: UUID,
        @PathVariable("noteId") id: UUID,
        @PathVariable("version") version: Long,
    ) = service.revision(users.identity(user), team, id, version)

}
