package com.example.notes.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "app_user")
class AppUser(

    @field:Column(nullable = false, length = 100)
    var name: String,

) {

    @field:Id
    var id: UUID = UUID.randomUUID()
}
