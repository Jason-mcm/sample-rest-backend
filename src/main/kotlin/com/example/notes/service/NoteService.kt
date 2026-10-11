package com.example.notes.service

import com.example.notes.entity.Note
import com.example.notes.entity.NoteRevision
import com.example.notes.exception.ApiException
import com.example.notes.exception.MergeConflictException
import com.example.notes.model.MergeConflictResponse
import com.example.notes.model.MergeNote
import com.example.notes.model.RevisionPage
import com.example.notes.model.RevisionResponse
import com.example.notes.model.CreateNote
import com.example.notes.model.UpdateNote
import com.example.notes.model.NoteResponse
import com.example.notes.model.NotePage
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Provides team-scoped note operations and revision history.
 *
 * Callers supply an identity validated by [UserService]. Every public operation checks
 * team membership through [TeamService]; inaccessible teams are reported as not found.
 * Note writes and their revision snapshots share a transaction.
 */
@Service
open class NoteService(
    private val em: EntityManager,
    private val merger: NoteMergeService,
    private val teams: TeamService,
) {
    /**
     * Returns a zero-based page of team notes, ordered by latest update and then ID descending.
     *
     * @param page page index, from 0 through 100000.
     * @param size number of notes per page, from 1 through 100.
     * @throws ApiException if pagination is invalid (400) or the team is inaccessible (404).
     */
    fun list(user: UUID, team: UUID, page: Int, size: Int): NotePage {
        teams.authorize(user, team)
        if (page !in 0..100000 || size !in 1..100) {
            throw ApiException(400, "page must be 0..100000 and size must be 1..100")
        }
        val items: List<NoteResponse> = em.createQuery("from Note where teamId = :team order by updatedAt desc, id desc", Note::class.java)
            .setParameter("team", team)
            .setFirstResult(page * size)
            .setMaxResults(size)
            .resultList
            .map { note: Note -> note.response() }
        val total: Long = em.createQuery("select count(n) from Note n where teamId = :team", Long::class.javaObjectType)
            .setParameter("team", team)
            .singleResult
        return NotePage(items, page, size, total)
    }

    /**
     * Returns the current note and its optimistic-lock version.
     *
     * @throws ApiException if the team is inaccessible or the note is not in that team (404).
     */
    fun get(user: UUID, team: UUID, id: UUID): NoteResponse {
        teams.authorize(user, team)
        return find(team, id).response()
    }

    /**
     * Creates a note and its initial revision in one transaction, attributing the edit to [user].
     * The title is trimmed; content is preserved as supplied.
     *
     * @throws ApiException if the title is blank, content is missing or exceeds 50000 UTF-16
     * code units (400), or the team is inaccessible (404).
     */
    @Transactional
    open fun create(user: UUID, team: UUID, request: CreateNote): NoteResponse {
        teams.authorize(user, team)
        val (title: String, content: String) = validate(request.title, request.content)
        val note = Note(
            teamId = team,
            title = title,
            content = content,
            createdBy = user,
            updatedBy = user,
        )
        em.persist(note)
        em.flush()
        recordRevision(note)
        return note.response()
    }

    /**
     * Replaces the title and content when the supplied version matches the current note.
     * Saves a revision atomically and returns the note with its incremented version.
     *
     * @throws ApiException if input or version is invalid (400), the team or note is inaccessible
     * (404), or the supplied version is stale (409).
     * @throws jakarta.persistence.OptimisticLockException if another write wins during persistence;
     * the API maps this concurrent-write conflict to 409.
     */
    @Transactional
    open fun update(user: UUID, team: UUID, id: UUID, request: UpdateNote): NoteResponse {
        teams.authorize(user, team)
        val (title: String, content: String) = validate(request.title, request.content)
        val note: Note = find(team, id)
        checkVersion(note, request.version)
        return save(note, user, title, content)
    }

    /**
     * Returns a zero-based page of saved snapshots, ordered by version descending.
     *
     * @param page page index, from 0 through 100000.
     * @param size number of snapshots per page, from 1 through 100.
     * @throws ApiException if pagination is invalid (400) or the team or note is inaccessible (404).
     */
    fun revisions(user: UUID, team: UUID, id: UUID, page: Int, size: Int): RevisionPage {
        teams.authorize(user, team)
        find(team, id)
        if (page !in 0..100000 || size !in 1..100) {
            throw ApiException(400, "page must be 0..100000 and size must be 1..100")
        }
        val items: List<RevisionResponse> = em.createQuery(
            "from NoteRevision where noteId = :id and teamId = :team order by version desc",
            NoteRevision::class.java,
        )
            .setParameter("id", id)
            .setParameter("team", team)
            .setFirstResult(page * size)
            .setMaxResults(size)
            .resultList
            .map { revision: NoteRevision -> revision.response() }
        val total: Long = em.createQuery(
            "select count(r) from NoteRevision r where noteId = :id and teamId = :team",
            Long::class.javaObjectType,
        )
            .setParameter("id", id)
            .setParameter("team", team)
            .singleResult
        return RevisionPage(items, page, size, total)
    }

    /**
     * Returns the saved snapshot for an exact note version.
     *
     * @param version nonnegative version identifying the snapshot.
     * @throws ApiException if the version is negative (400), or the team, note, or revision
     * is inaccessible or missing (404).
     */
    fun revision(user: UUID, team: UUID, id: UUID, version: Long): RevisionResponse {
        teams.authorize(user, team)
        find(team, id)
        return findRevision(team, id, version).response()
    }

    /**
     * Combines the draft with the current note using the supplied base revision.
     * Separate field changes and nonoverlapping line edits can merge automatically.
     * A successful merge saves the note and a new revision in one transaction.
     *
     * @throws MergeConflictException if changes overlap (409); the response includes the base,
     * current note, draft, and conflicting fields so the caller can resolve them manually.
     * @throws ApiException if the draft, base version, or merged content is invalid (400),
     * or the team, note, or base revision is inaccessible or missing (404).
     * @throws jakarta.persistence.OptimisticLockException if another write wins during persistence;
     * the API maps this concurrent-write conflict to 409.
     */
    @Transactional
    open fun merge(user: UUID, team: UUID, id: UUID, request: MergeNote): NoteResponse {
        teams.authorize(user, team)
        val (title: String, content: String) = validate(request.title, request.content)
        val baseVersion: Long? = request.baseVersion
        if (baseVersion == null || baseVersion < 0) {
            throw ApiException(400, "A nonnegative baseVersion is required")
        }
        val note: Note = find(team, id)
        val base: NoteRevision = findRevision(team, id, baseVersion)
        val mergedTitle: String? = merger.mergeTitle(base.title, note.title, title)
        val mergedContent: String? = merger.mergeContent(base.content, note.content, content)
        val conflicts: List<String> = buildList {
            if (mergedTitle == null) add("title")
            if (mergedContent == null) add("content")
        }
        if (conflicts.isNotEmpty()) {
            throw MergeConflictException(MergeConflictResponse(
                base = base.response(),
                current = note.response(),
                draft = request,
                conflictingFields = conflicts,
                mergedTitle = mergedTitle,
                mergedContent = mergedContent,
            ))
        }
        // Individually valid drafts can merge into content over the limit.
        val validated: Pair<String, String> = validate(mergedTitle, mergedContent)
        return save(note, user, validated.first, validated.second)
    }

    /**
     * Permanently deletes a note when [version] matches its current version.
     * The database also deletes the note's revision history through its cascading foreign key.
     *
     * @throws ApiException if the version is missing or invalid (400), the team or note is
     * inaccessible (404), or the supplied version is stale (409).
     * @throws jakarta.persistence.OptimisticLockException if another write wins during persistence;
     * the API maps this concurrent-write conflict to 409.
     */
    @Transactional
    open fun delete(user: UUID, team: UUID, id: UUID, version: Long?) {
        teams.authorize(user, team)
        val note: Note = find(team, id)
        checkVersion(note, version)
        em.remove(note)
        em.flush()
    }

    private fun find(team: UUID, id: UUID): Note = em.createQuery("from Note where id = :id and teamId = :team", Note::class.java)
        .setParameter("id", id)
        .setParameter("team", team)
        .resultList
        .firstOrNull() ?: throw ApiException(404, "Note not found")

    private fun validate(title: String?, content: String?): Pair<String, String> {
        if (title.isNullOrBlank()) {
            throw ApiException(400, "title is required and cannot be blank")
        }
        if (content == null || content.length > 50000) {
            throw ApiException(400, "content is required and must not exceed 50000 characters")
        }
        return title.trim() to content
    }

    private fun save(note: Note, user: UUID, title: String, content: String): NoteResponse {
        note.title = title
        note.content = content
        note.updatedBy = user
        note.updatedAt = Instant.now()
        // Flush inside the service: the response contains the incremented Hibernate version.
        em.flush()
        recordRevision(note)
        return note.response()
    }

    private fun recordRevision(note: Note) {
        em.persist(NoteRevision(
            noteId = note.id,
            teamId = note.teamId,
            version = note.version,
            title = note.title,
            content = note.content,
            editedBy = note.updatedBy,
            editedAt = note.updatedAt,
        ))
        // The note and snapshot commit or roll back together.
        em.flush()
    }

    private fun findRevision(team: UUID, id: UUID, version: Long): NoteRevision {
        if (version < 0) {
            throw ApiException(400, "A nonnegative revision version is required")
        }
        return em.createQuery(
            "from NoteRevision where noteId = :id and teamId = :team and version = :version",
            NoteRevision::class.java,
        )
            .setParameter("id", id)
            .setParameter("team", team)
            .setParameter("version", version)
            .resultList
            .firstOrNull() ?: throw ApiException(404, "Revision not found")
    }

    private fun checkVersion(note: Note, expected: Long?) {
        if (expected == null || expected < 0) {
            throw ApiException(400, "A nonnegative version is required")
        }
        if (note.version != expected) {
            throw ApiException(409, "Note changed; fetch the latest version and retry")
        }
    }

    private fun Note.response(): NoteResponse = NoteResponse(
        id = id,
        teamId = teamId,
        title = title,
        content = content,
        createdBy = createdBy,
        updatedBy = updatedBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version,
    )

    private fun NoteRevision.response(): RevisionResponse = RevisionResponse(
        noteId = noteId,
        teamId = teamId,
        version = version,
        title = title,
        content = content,
        editedBy = editedBy,
        editedAt = editedAt,
    )
}
