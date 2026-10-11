package com.example.notes

import java.time.Instant

import jakarta.persistence.PersistenceException

import com.example.notes.model.RevisionResponse

import com.example.notes.model.RevisionPage

import com.example.notes.model.NotePage

import com.example.notes.entity.Note
import com.example.notes.entity.NoteRevision
import com.example.notes.exception.MergeConflictException
import com.example.notes.model.MergeNote
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.NullSource
import com.example.notes.exception.ApiException
import com.example.notes.model.CreateNote
import com.example.notes.model.NoteResponse
import com.example.notes.model.UpdateNote
import com.example.notes.service.NoteMergeService
import com.example.notes.service.NoteService
import com.example.notes.service.TeamService
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.util.UUID

/** Direct service tests; database transactions and version increments remain integration-tested. */
class NoteServiceTest {
    private val em: EntityManager = mock(EntityManager::class.java)
    private val teams: TeamService = mock(TeamService::class.java)
    private val service: NoteService = NoteService(em, NoteMergeService(), teams)
    private val user: UUID = UUID.randomUUID()
    private val team: UUID = UUID.randomUUID()
    private val note: Note = Note(teamId = team, title = "Original", content = "Draft", createdBy = user, updatedBy = user)

    @Test
    fun `create trims title preserves content and records an attributed snapshot`() {
        val response: NoteResponse = service.create(user, team, CreateNote("  Planning  ", " draft\n"))

        assertEquals("Planning", response.title)
        assertEquals(" draft\n", response.content)
        verify(teams).authorize(user, team)
        val saved: ArgumentCaptor<Any> = ArgumentCaptor.forClass(Any::class.java)
        verify(em, times(2)).persist(saved.capture())
        val revision: NoteRevision = saved.allValues.filterIsInstance<NoteRevision>().single()
        assertEquals(response.id, revision.noteId)
        assertEquals(team, revision.teamId)
        assertEquals(user, revision.editedBy)
        assertEquals(response.title, revision.title)
        assertEquals(response.content, revision.content)
        assertEquals(response.version, revision.version)
    }

    @Test
    fun `blank title is rejected without writing`() {
        val error: ApiException = assertThrows(ApiException::class.java) {
            service.create(user, team, CreateNote("  ", "content"))
        }

        assertEquals(400, error.status)
        verifyNoInteractions(em)
    }

    @Test
    fun `oversized content is rejected without writing`() {
        val error: ApiException = assertThrows(ApiException::class.java) {
            service.create(user, team, CreateNote("Title", "x".repeat(50001)))
        }

        assertEquals(400, error.status)
        verifyNoInteractions(em)
    }

    @Test
    fun `content at the maximum length is accepted`() {
        val content: String = "x".repeat(50000)

        assertEquals(content, service.create(user, team, CreateNote("Title", content)).content)
    }

    @Test
    fun `denied access stops note creation before storage is touched`() {
        doThrow(ApiException(404, "Team not found")).`when`(teams).authorize(user, team)

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.create(user, team, CreateNote("Title", "content"))
        }

        assertEquals(404, error.status)
        verifyNoInteractions(em)
    }

    @Test
    fun `invalid pagination is rejected before querying notes`() {
        val error: ApiException = assertThrows(ApiException::class.java) { service.list(user, team, -1, 20) }

        assertEquals(400, error.status)
        verifyNoInteractions(em)
    }

    @Test
    fun `missing note is reported as not found`() {
        givenNotes(emptyList())

        val error: ApiException = assertThrows(ApiException::class.java) { service.get(user, team, note.id) }

        assertEquals(404, error.status)
    }

    @Test
    fun `stale update leaves the note unchanged and writes no revision`() {
        note.version = 2
        givenNotes(listOf(note))

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.update(user, team, note.id, UpdateNote("Changed", "Changed", 1))
        }

        assertEquals(409, error.status)
        assertEquals("Original", note.title)
        assertEquals("Draft", note.content)
        verify(em, never()).persist(any())
        verify(em, never()).flush()
    }

    @Test
    fun `update requires a version`() {
        givenNotes(listOf(note))

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.update(user, team, note.id, UpdateNote("Changed", "Changed", null))
        }

        assertEquals(400, error.status)
        verify(em, never()).flush()
    }

    @Test
    fun `stale delete never removes the note`() {
        note.version = 2
        givenNotes(listOf(note))

        val error: ApiException = assertThrows(ApiException::class.java) { service.delete(user, team, note.id, 1) }

        assertEquals(409, error.status)
        verify(em, never()).remove(any())
    }

    @Test
    fun `matching version permits deletion`() {
        givenNotes(listOf(note))

        service.delete(user, team, note.id, 0)

        verify(em).remove(note)
        verify(em).flush()
    }

    @ParameterizedTest
    @ValueSource(strings = ["list", "get", "update", "delete", "revisions", "revision", "merge"])
    fun `every operation checks access before reading or writing notes`(operation: String) {
        doThrow(ApiException(404, "Team not found")).`when`(teams).authorize(user, team)

        val error: ApiException = assertThrows(ApiException::class.java) {
            when (operation) {
                "list" -> service.list(user, team, 0, 20)
                "get" -> service.get(user, team, note.id)
                "update" -> service.update(user, team, note.id, UpdateNote("Title", "Content", 0))
                "delete" -> service.delete(user, team, note.id, 0)
                "revisions" -> service.revisions(user, team, note.id, 0, 20)
                "revision" -> service.revision(user, team, note.id, 0)
                "merge" -> service.merge(user, team, note.id, MergeNote("Title", "Content", 0))
                else -> fail("Unknown operation")
            }
        }

        assertEquals(404, error.status)
        verifyNoInteractions(em)
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", " ", "\t\n", "\u2003"])
    fun `missing and whitespace titles are invalid`(title: String?) {
        val error: ApiException = assertThrows(ApiException::class.java) {
            service.create(user, team, CreateNote(title, ""))
        }

        assertEquals(400, error.status)
        verifyNoInteractions(em)
    }

    @Test
    fun `missing content differs from valid empty content`() {
        val error: ApiException = assertThrows(ApiException::class.java) {
            service.create(user, team, CreateNote("Title", null))
        }
        assertEquals(400, error.status)
        verifyNoInteractions(em)

        assertEquals("", service.create(user, team, CreateNote("Title", "")).content)
    }

    @Test
    fun `content limit counts UTF16 units for supplementary Unicode characters`() {
        val maximum: String = "😀".repeat(25000)
        assertEquals(maximum, service.create(user, team, CreateNote("Unicode", maximum)).content)

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.create(user, team, CreateNote("Unicode", maximum + "x"))
        }
        assertEquals(400, error.status)
    }

    @ParameterizedTest
    @CsvSource("-1,20", "100001,20", "0,0", "0,-1", "0,101", "2147483647,100")
    fun `pagination rejects bounds before computing offsets`(page: Int, size: Int) {
        val error: ApiException = assertThrows(ApiException::class.java) { service.list(user, team, page, size) }

        assertEquals(400, error.status)
        verifyNoInteractions(em)
    }

    @Test
    fun `list preserves total count when requested page is empty`() {
        val query: TypedQuery<Note> = queryFor(Note::class.java)
        `when`(query.setParameter("team", team)).thenReturn(query)
        `when`(query.setFirstResult(10000000)).thenReturn(query)
        `when`(query.setMaxResults(100)).thenReturn(query)
        `when`(query.resultList).thenReturn(emptyList())
        givenCount(3)

        val result: NotePage = service.list(user, team, 100000, 100)

        assertEquals(3L, result.total)
        assertEquals(100000, result.page)
        assertEquals(100, result.size)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `successful update keeps creation metadata and snapshots the saved version`() {
        givenNotes(listOf(note))
        val editor: UUID = UUID.randomUUID()
        val createdAt: Instant = note.createdAt
        // Model Hibernate's flush result without claiming to test its versioning implementation.
        doAnswer { note.version = 1L; null }.`when`(em).flush()

        val result: NoteResponse = service.update(editor, team, note.id, UpdateNote("  Revised  ", "New", 0))

        assertEquals("Revised", result.title)
        assertEquals("New", result.content)
        assertEquals(user, result.createdBy)
        assertEquals(createdAt, result.createdAt)
        assertEquals(editor, result.updatedBy)
        assertEquals(1L, result.version)
        val revision: NoteRevision = capturedRevision()
        assertEquals(result.version, revision.version)
        assertEquals(result.updatedAt, revision.editedAt)
        assertEquals(editor, revision.editedBy)
        assertEquals(result.title, revision.title)
        assertEquals(result.content, revision.content)
    }

    @Test
    fun `optimistic lock failure propagates without attempting a revision`() {
        givenNotes(listOf(note))
        val failure: OptimisticLockException = OptimisticLockException("Concurrent update")
        doThrow(failure).`when`(em).flush()

        assertSame(failure, assertThrows(OptimisticLockException::class.java) {
            service.update(user, team, note.id, UpdateNote("Revised", "New", 0))
        })
        verify(em, never()).persist(any())
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = [-1])
    fun `delete requires a nonnegative version`(version: Long?) {
        givenNotes(listOf(note))

        val error: ApiException = assertThrows(ApiException::class.java) { service.delete(user, team, note.id, version) }

        assertEquals(400, error.status)
        verify(em, never()).remove(any())
        verify(em, never()).flush()
    }

    @Test
    fun `future update version is a conflict too`() {
        givenNotes(listOf(note))

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.update(user, team, note.id, UpdateNote("Changed", "Changed", Long.MAX_VALUE))
        }

        assertEquals(409, error.status)
        assertEquals("Original", note.title)
        verify(em, never()).flush()
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = [-1])
    fun `merge requires a nonnegative base version before reading notes`(version: Long?) {
        val error: ApiException = assertThrows(ApiException::class.java) {
            service.merge(user, team, note.id, MergeNote("Title", "Content", version))
        }

        assertEquals(400, error.status)
        verifyNoInteractions(em)
    }

    @Test
    fun `missing base revision cannot be merged`() {
        givenNotes(listOf(note))
        givenRevisions(emptyList())

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.merge(user, team, note.id, MergeNote("Title", "Content", 0))
        }

        assertEquals(404, error.status)
        verify(em, never()).persist(any())
    }

    @Test
    fun `merge combines a current title edit with a draft content edit`() {
        val base: NoteRevision = baseRevision()
        note.title = "Current title"
        givenNotes(listOf(note))
        givenRevisions(listOf(base))

        val result: NoteResponse = service.merge(user, team, note.id, MergeNote(base.title, "Draft edit", 0))

        assertEquals("Current title", result.title)
        assertEquals("Draft edit", result.content)
        assertEquals(result.content, capturedRevision().content)
        assertEquals("Original", base.title)
        assertEquals("Draft", base.content)
    }

    @Test
    fun `title conflict still returns automatically merged content and performs no writes`() {
        val base: NoteRevision = baseRevision()
        note.title = "Current title"
        givenNotes(listOf(note))
        givenRevisions(listOf(base))
        val draft: MergeNote = MergeNote("Different title", "Draft edit", 0)

        val error: MergeConflictException = assertThrows(MergeConflictException::class.java) {
            service.merge(user, team, note.id, draft)
        }

        assertEquals(listOf("title"), error.response.conflictingFields)
        assertNull(error.response.mergedTitle)
        assertEquals("Draft edit", error.response.mergedContent)
        assertEquals(base.title, error.response.base.title)
        assertEquals(note.title, error.response.current.title)
        assertEquals(draft, error.response.draft)
        assertEquals("Draft", note.content)
        verify(em, never()).persist(any())
        verify(em, never()).flush()
    }

    @Test
    fun `content conflict preserves the resolved title for manual resolution`() {
        val base: NoteRevision = baseRevision()
        note.content = "Current edit"
        givenNotes(listOf(note))
        givenRevisions(listOf(base))

        val error: MergeConflictException = assertThrows(MergeConflictException::class.java) {
            service.merge(user, team, note.id, MergeNote("New title", "Different edit", 0))
        }

        assertEquals(listOf("content"), error.response.conflictingFields)
        assertEquals("New title", error.response.mergedTitle)
        assertNull(error.response.mergedContent)
        assertEquals("Original", note.title)
        verify(em, never()).persist(any())
    }

    @Test
    fun `oversized merged result is rejected before changing the entity`() {
        val base: NoteRevision = baseRevision(content = "a\nb\nc")
        note.content = "${"x".repeat(30000)}\nb\nc"
        val originalContent: String = note.content
        givenNotes(listOf(note))
        givenRevisions(listOf(base))

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.merge(user, team, note.id, MergeNote(note.title, "a\nb\n${"y".repeat(30000)}", 0))
        }

        assertEquals(400, error.status)
        assertEquals(originalContent, note.content)
        verify(em, never()).flush()
    }

    @Test
    fun `exact revision maps attribution and timestamp`() {
        givenNotes(listOf(note))
        val revision: NoteRevision = baseRevision()
        givenRevisions(listOf(revision))

        val result: RevisionResponse = service.revision(user, team, note.id, 0)

        assertEquals(note.id, result.noteId)
        assertEquals(team, result.teamId)
        assertEquals(revision.title, result.title)
        assertEquals(revision.content, result.content)
        assertEquals(revision.editedBy, result.editedBy)
        assertEquals(revision.editedAt, result.editedAt)
    }

    @Test
    fun `negative revision version is invalid`() {
        givenNotes(listOf(note))

        val error: ApiException = assertThrows(ApiException::class.java) { service.revision(user, team, note.id, -1) }

        assertEquals(400, error.status)
    }

    @Test
    fun `revision pages apply offsets and map snapshots`() {
        givenNotes(listOf(note))
        val revision: NoteRevision = baseRevision()
        val query: TypedQuery<NoteRevision> = queryFor(NoteRevision::class.java)
        `when`(query.setParameter("id", note.id)).thenReturn(query)
        `when`(query.setParameter("team", team)).thenReturn(query)
        `when`(query.setFirstResult(2)).thenReturn(query)
        `when`(query.setMaxResults(2)).thenReturn(query)
        `when`(query.resultList).thenReturn(listOf(revision))
        val count: TypedQuery<Long> = givenCount(3)
        `when`(count.setParameter("id", note.id)).thenReturn(count)

        val result: RevisionPage = service.revisions(user, team, note.id, 1, 2)

        assertEquals(3L, result.total)
        assertEquals(1, result.page)
        assertEquals(2, result.size)
        assertEquals(listOf(0L), result.items.map { item -> item.version })
        assertEquals(revision.content, result.items.single().content)
    }

    @ParameterizedTest
    @CsvSource("-1,20", "100001,20", "0,0", "0,101")
    fun `revision pagination validates both bounds`(page: Int, size: Int) {
        givenNotes(listOf(note))

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.revisions(user, team, note.id, page, size)
        }

        assertEquals(400, error.status)
        verify(em, never()).createQuery(anyString(), eq(NoteRevision::class.java))
    }

    @Test
    fun `get exposes the persisted note metadata`() {
        note.version = 7
        givenNotes(listOf(note))

        val result: NoteResponse = service.get(user, team, note.id)

        assertEquals(note.id, result.id)
        assertEquals(team, result.teamId)
        assertEquals(note.title, result.title)
        assertEquals(note.content, result.content)
        assertEquals(note.createdBy, result.createdBy)
        assertEquals(note.updatedBy, result.updatedBy)
        assertEquals(note.createdAt, result.createdAt)
        assertEquals(note.updatedAt, result.updatedAt)
        assertEquals(7L, result.version)
    }

    @ParameterizedTest
    @ValueSource(strings = ["update", "delete", "revisions", "revision", "merge"])
    fun `operations on missing notes fail without writing`(operation: String) {
        givenNotes(emptyList())

        val error: ApiException = assertThrows(ApiException::class.java) {
            when (operation) {
                "update" -> service.update(user, team, note.id, UpdateNote("Title", "Content", 0))
                "delete" -> service.delete(user, team, note.id, 0)
                "revisions" -> service.revisions(user, team, note.id, 0, 20)
                "revision" -> service.revision(user, team, note.id, 0)
                "merge" -> service.merge(user, team, note.id, MergeNote("Title", "Content", 0))
                else -> fail("Unknown operation")
            }
        }

        assertEquals(404, error.status)
        verify(em, never()).persist(any())
        verify(em, never()).remove(any())
        verify(em, never()).flush()
    }

    @Test
    fun `snapshot persistence failure is propagated rather than reporting success`() {
        givenNotes(listOf(note))
        val failure: PersistenceException = PersistenceException("Snapshot write failed")
        doThrow(failure).`when`(em).persist(any())

        assertSame(failure, assertThrows(PersistenceException::class.java) {
            service.update(user, team, note.id, UpdateNote("Revised", "Content", 0))
        })
        // Rollback is a transaction interceptor responsibility, not provided by these mocks.
    }

    @Test
    fun `negative update version is rejected before modifying the entity`() {
        givenNotes(listOf(note))

        val error: ApiException = assertThrows(ApiException::class.java) {
            service.update(user, team, note.id, UpdateNote("Revised", "Content", -1))
        }

        assertEquals(400, error.status)
        assertEquals("Original", note.title)
        verify(em, never()).flush()
    }

    private fun baseRevision(content: String = note.content): NoteRevision = NoteRevision(
        noteId = note.id, teamId = team, version = 0, title = note.title,
        content = content, editedBy = user, editedAt = note.createdAt,
    )

    private fun capturedRevision(): NoteRevision {
        val saved: ArgumentCaptor<Any> = ArgumentCaptor.forClass(Any::class.java)
        verify(em).persist(saved.capture())
        return saved.value as NoteRevision
    }

    private fun givenRevisions(revisions: List<NoteRevision>): Unit {
        val query: TypedQuery<NoteRevision> = queryFor(NoteRevision::class.java)
        `when`(query.setParameter("id", note.id)).thenReturn(query)
        `when`(query.setParameter("team", team)).thenReturn(query)
        `when`(query.setParameter("version", 0L)).thenReturn(query)
        `when`(query.resultList).thenReturn(revisions)
    }

    private fun givenCount(count: Long): TypedQuery<Long> {
        val query: TypedQuery<Long> = queryFor(Long::class.javaObjectType)
        `when`(query.setParameter("team", team)).thenReturn(query)
        `when`(query.singleResult).thenReturn(count)
        return query
    }

    private fun <T> queryFor(type: Class<T>): TypedQuery<T> {
        @Suppress("UNCHECKED_CAST")
        val query: TypedQuery<T> = mock(TypedQuery::class.java) as TypedQuery<T>
        `when`(em.createQuery(anyString(), eq(type))).thenReturn(query)
        return query
    }

    private fun givenNotes(notes: List<Note>): Unit {
        @Suppress("UNCHECKED_CAST")
        val query: TypedQuery<Note> = mock(TypedQuery::class.java) as TypedQuery<Note>
        `when`(em.createQuery(anyString(), eq(Note::class.java))).thenReturn(query)
        `when`(query.setParameter("id", note.id)).thenReturn(query)
        `when`(query.setParameter("team", team)).thenReturn(query)
        `when`(query.resultList).thenReturn(notes)
    }
}
