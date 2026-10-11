package com.example.notes.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.io.Serializable
import java.util.UUID

@Embeddable
data class MembershipId(

    @field:Column(name = "user_id", nullable = false)
    var userId: UUID,

    @field:Column(name = "team_id", nullable = false)
    var teamId: UUID,

) : Serializable
