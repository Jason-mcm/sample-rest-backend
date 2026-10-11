package com.example.notes

import com.example.notes.exception.ApiException
import com.example.notes.service.UserService
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID

class UserServiceTest {
    private val em: EntityManager = mock(EntityManager::class.java)
    private val service: UserService = UserService(em)
    private val user: UUID = UUID.randomUUID()

    @Test
    fun `existing identity resolves to its user ID`() {
        givenUserCount(1)

        assertEquals(user, service.identity(user.toString()))
    }

    @Test
    fun `unknown identity is unauthorized`() {
        givenUserCount(0)

        val error: ApiException = assertThrows(ApiException::class.java) { service.identity(user.toString()) }

        assertEquals(401, error.status)
    }

    @Test
    fun `missing identity is rejected before querying storage`() {
        val error: ApiException = assertThrows(ApiException::class.java) { service.identity(null) }

        assertEquals(401, error.status)
        verifyNoInteractions(em)
    }

    @Test
    fun `malformed identity is rejected before querying storage`() {
        val error: ApiException = assertThrows(ApiException::class.java) { service.identity("invalid") }

        assertEquals(401, error.status)
        verifyNoInteractions(em)
    }

    private fun givenUserCount(count: Long): Unit {
        @Suppress("UNCHECKED_CAST")
        val query: TypedQuery<Long> = mock(TypedQuery::class.java) as TypedQuery<Long>
        `when`(em.createQuery(anyString(), eq(Long::class.javaObjectType))).thenReturn(query)
        `when`(query.setParameter("id", user)).thenReturn(query)
        `when`(query.singleResult).thenReturn(count)
    }
}
