package com.example.notes

import com.example.notes.entity.Team
import com.example.notes.exception.ApiException
import com.example.notes.model.TeamResponse
import com.example.notes.service.TeamService
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID

class TeamServiceTest {
    private val em: EntityManager = mock(EntityManager::class.java)
    private val service: TeamService = TeamService(em)
    private val user: UUID = UUID.randomUUID()
    private val team: UUID = UUID.randomUUID()

    @Test
    fun `membership grants access`() {
        givenMembershipCount(1)

        assertDoesNotThrow { service.authorize(user, team) }
    }

    @Test
    fun `missing membership hides the team`() {
        givenMembershipCount(0)

        val error: ApiException = assertThrows(ApiException::class.java) { service.authorize(user, team) }

        assertEquals(404, error.status)
        assertEquals("Team not found", error.message)
    }

    @Test
    fun `team discovery maps stored teams to public responses`() {
        val engineering: Team = Team(name = "Engineering")
        givenTeams(listOf(engineering))

        assertEquals(listOf(TeamResponse(engineering.id, engineering.name)), service.teams(user))
    }

    @Test
    fun `user with no memberships sees an empty list`() {
        givenTeams(emptyList())

        assertEquals(emptyList<TeamResponse>(), service.teams(user))
    }

    private fun givenMembershipCount(count: Long): Unit {
        @Suppress("UNCHECKED_CAST")
        val query: TypedQuery<Long> = mock(TypedQuery::class.java) as TypedQuery<Long>
        `when`(em.createQuery(anyString(), eq(Long::class.javaObjectType))).thenReturn(query)
        `when`(query.setParameter("user", user)).thenReturn(query)
        `when`(query.setParameter("team", team)).thenReturn(query)
        `when`(query.singleResult).thenReturn(count)
    }

    private fun givenTeams(teams: List<Team>): Unit {
        @Suppress("UNCHECKED_CAST")
        val query: TypedQuery<Team> = mock(TypedQuery::class.java) as TypedQuery<Team>
        `when`(em.createQuery(anyString(), eq(Team::class.java))).thenReturn(query)
        `when`(query.setParameter("user", user)).thenReturn(query)
        `when`(query.resultList).thenReturn(teams)
    }
}
