package com.example.notes.service

import com.example.notes.exception.ApiException
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Resolves demo request identities to existing application users.
 *
 * The caller-supplied X-User-Id header identifies a user for the sample application;
 * it does not authenticate the caller. Team access is checked separately by [TeamService].
 */
@Service
class UserService(
    private val em: EntityManager,
) {
    /**
     * Parses the demo identity header and verifies that the user exists.
     *
     * @param header value of the X-User-Id request header.
     * @return the existing user's ID for subsequent service operations.
     * @throws ApiException if the header is missing, cannot be parsed as a UUID,
     * or identifies an unknown user (401).
     */
    fun identity(header: String?): UUID {
        val id: UUID = try {
            UUID.fromString(header)
        } catch (_: IllegalArgumentException) {
            throw ApiException(401, "A valid X-User-Id demo identity is required")
        } catch (_: NullPointerException) {
            throw ApiException(401, "A valid X-User-Id demo identity is required")
        }
        val count: Long = em.createQuery("select count(u) from AppUser u where u.id = :id", Long::class.javaObjectType)
            .setParameter("id", id)
            .singleResult
        if (count == 0L) {
            throw ApiException(401, "Unknown demo identity")
        }
        return id
    }
}
