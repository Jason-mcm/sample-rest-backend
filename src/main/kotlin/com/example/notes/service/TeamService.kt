package com.example.notes.service

import com.example.notes.entity.Team
import com.example.notes.exception.ApiException
import com.example.notes.model.TeamResponse
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Provides team discovery and membership-based access checks.
 *
 * Callers supply an identity validated by [UserService]. Missing and inaccessible teams
 * produce the same not-found response to avoid disclosing another team's existence.
 */
@Service
class TeamService(
    private val em: EntityManager,
) {
    /**
     * Verifies that [user] belongs to [team], returning normally when access is allowed.
     * All members have the same access; this check does not distinguish roles.
     *
     * @param user validated application user ID.
     * @param team team whose membership is required.
     * @throws ApiException if the team does not exist or the user is not a member (404).
     */
    fun authorize(user: UUID, team: UUID) {
        val count: Long = em.createQuery(
            "select count(m) from Membership m where m.id.userId = :user and m.id.teamId = :team",
            Long::class.javaObjectType,
        )
            .setParameter("user", user)
            .setParameter("team", team)
            .singleResult
        // Same response for nonexistent and inaccessible teams avoids disclosing team existence.
        if (count == 0L) {
            throw ApiException(404, "Team not found")
        }
    }

    /**
     * Returns the teams that [user] belongs to, ordered by name and then ID ascending.
     *
     * @param user validated application user ID.
     * @return all accessible teams, or an empty list when the user has no memberships.
     */
    fun teams(user: UUID): List<TeamResponse> {
        return em.createQuery(
            """
            select t from Team t
            where exists (
                select m from Membership m where m.id.teamId = t.id and m.id.userId = :user
            )
            order by t.name, t.id
            """.trimIndent(),
            Team::class.java,
        )
            .setParameter("user", user)
            .resultList
            .map { team: Team -> TeamResponse(team.id, team.name) }
    }
}
