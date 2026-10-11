package com.example.notes.entity

import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "membership")
class Membership(

    @field:EmbeddedId
    var id: MembershipId,

)
