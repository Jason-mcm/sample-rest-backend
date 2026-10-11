package com.example.notes.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "note")
class Note(

    @field:Column(name = "team_id", nullable = false)
    var teamId: UUID,

    @field:Column(nullable = false, columnDefinition = "text")
    var title: String,

    @field:Column(nullable = false, columnDefinition = "text")
    var content: String,

    @field:Column(name = "created_by", nullable = false)
    var createdBy: UUID,

    @field:Column(name = "updated_by", nullable = false)
    var updatedBy: UUID
) {

    @field:Id
    var id: UUID = UUID.randomUUID()

    @field:Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @field:Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt

    @field:Version
    var version: Long = 0
}
