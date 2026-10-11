package com.example.notes.controller

import com.example.notes.service.TeamService
import com.example.notes.service.UserService
import io.smallrye.common.annotation.Blocking
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Blocking
@RequestMapping("/api/teams")
class TeamController(
    private val service: TeamService,
    private val users: UserService,
) {
    @GetMapping
    fun teams(
        @RequestHeader(value = "X-User-Id", required = false) user: String?,
    ) = service.teams(users.identity(user))
}
