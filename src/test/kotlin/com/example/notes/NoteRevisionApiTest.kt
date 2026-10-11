package com.example.notes

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.narayana.jta.QuarkusTransaction
import com.example.notes.service.NoteService
import com.example.notes.model.UpdateNote
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@QuarkusTest
class NoteRevisionApiTest {
    private val alice = "00000000-0000-0000-0000-000000000001"
    private val bob = "00000000-0000-0000-0000-000000000002"
    private val charlie = "00000000-0000-0000-0000-000000000003"
    private val engineering = "10000000-0000-0000-0000-000000000001"
    private val design = "10000000-0000-0000-0000-000000000002"
    private val path = "/api/teams/$engineering/notes"

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var notes: NoteService

    @Test
    fun `transaction rollback removes a flushed edit and its revision together`() {
        val id: String = create("Original content")
        val url: String = "$path/$id"
        var savedInsideTransaction: Boolean = false
        try {
            assertThrows(RuntimeException::class.java) {
                QuarkusTransaction.requiringNew().run {
                    notes.update(UUID.fromString(bob), UUID.fromString(engineering), UUID.fromString(id),
                        UpdateNote("Rolled back title", "Rolled back content", 0))
                    savedInsideTransaction = true
                    // NoteService has flushed both the edit and snapshot; force the outer transaction to fail.
                    throw IllegalStateException("Deliberate rollback after snapshot persistence")
                }
            }

            assertTrue(savedInsideTransaction, "The update must succeed before testing its rollback")
            asUser().get(url).then().statusCode(200)
                .body("title", equalTo("Planning"))
                .body("content", equalTo("Original content"))
                .body("version", equalTo(0))
                .body("updatedBy", equalTo(alice))
            asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(1))
            asUser().get("$url/revisions/1").then().statusCode(404)
        } finally {
            cleanup(id)
        }
    }

    private fun asUser(user: String = alice) = given().header("X-User-Id", user).contentType(ContentType.JSON)

    private fun create(content: String = "Budget: pending\nStaffing: pending"): String = asUser()
        .body(mapOf("title" to "Planning", "content" to content))
        .post(path).then().statusCode(201).extract().jsonPath().getString("id")

    private fun cleanup(id: String) {
        val current = asUser().get("$path/$id")
        if (current.statusCode == 200) {
            asUser().queryParam("version", current.jsonPath().getLong("version"))
                .delete("$path/$id").then().statusCode(204)
        }
    }

    @Test
    fun `history captures creation merged edits and restoration and is deleted with note`() {
        val id = create()
        val url = "$path/$id"
        try {
            asUser().get("$url/revisions/0").then().statusCode(200)
                .body("editedBy", equalTo(alice)).body("content", equalTo("Budget: pending\nStaffing: pending"))
            asUser().body(mapOf("title" to "Planning", "content" to "Budget: approved\nStaffing: pending", "version" to 0))
                .put(url).then().statusCode(200)
            asUser(bob).body(mapOf("title" to "Team planning", "content" to "Budget: pending\nStaffing: hired", "baseVersion" to 0))
                .post("$url/merge").then().statusCode(200)
                .body("version", equalTo(2)).body("title", equalTo("Team planning"))
                .body("content", equalTo("Budget: approved\nStaffing: hired"))
            asUser().get("$url/revisions?page=0&size=2").then().statusCode(200)
                .body("total", equalTo(3)).body("items.version", contains(2, 1))
            asUser().get("$url/revisions?page=1&size=2").then().statusCode(200)
                .body("items.version", contains(0))
            asUser().get("$url/revisions/2").then().statusCode(200)
                .body("editedBy", equalTo(bob)).body("content", equalTo("Budget: approved\nStaffing: hired"))

            val original = asUser().get("$url/revisions/0").jsonPath()
            asUser().body(mapOf("title" to original.getString("title"), "content" to original.getString("content"), "version" to 2))
                .put(url).then().statusCode(200).body("version", equalTo(3))
            asUser().get("$url/revisions/2").then().statusCode(200)
                .body("content", equalTo("Budget: approved\nStaffing: hired"))
            asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(4))
        } finally {
            cleanup(id)
        }
        asUser().get("$url/revisions").then().statusCode(404)
        val count = em.createNativeQuery("select count(*) from note_revision where note_id = :id")
            .setParameter("id", UUID.fromString(id)).singleResult as Number
        assertEquals(0L, count.toLong())
    }

    @Test
    fun `overlapping edits return context and manual resolution creates only one revision`() {
        val id = create("Agenda")
        val url = "$path/$id"
        try {
            asUser().body(mapOf("title" to "Alice title", "content" to "Budget", "version" to 0))
                .put(url).then().statusCode(200)
            asUser(bob).body(mapOf("title" to "Bob title", "content" to "Staffing", "baseVersion" to 0))
                .post("$url/merge").then().statusCode(409)
                .body("code", equalTo("MERGE_CONFLICT"))
                .body("conflictingFields", contains("title", "content"))
                .body("base.content", equalTo("Agenda"))
                .body("current.version", equalTo(1)).body("current.content", equalTo("Budget"))
                .body("draft.content", equalTo("Staffing"))
                .body("mergedTitle", nullValue()).body("mergedContent", nullValue())
            asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(2))
            asUser(bob).body(mapOf("title" to "Team title", "content" to "Budget and staffing", "version" to 1))
                .put(url).then().statusCode(200).body("version", equalTo(2))
            asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(3))
            asUser().body(mapOf("title" to "Stale", "content" to "Lost", "version" to 1))
                .put(url).then().statusCode(409)
            asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(3))
        } finally {
            cleanup(id)
        }
    }

    @Test
    fun `history and merge enforce team isolation and validate requests`() {
        val id = create()
        val url = "$path/$id"
        val draft = mapOf("title" to "Planning", "content" to "Draft", "baseVersion" to 0)
        try {
            for ((user, route) in listOf(charlie to url, bob to "/api/teams/$design/notes/$id")) {
                asUser(user).get("$route/revisions").then().statusCode(404)
                asUser(user).get("$route/revisions/0").then().statusCode(404)
                asUser(user).body(draft).post("$route/merge").then().statusCode(404)
            }
            given().get("$url/revisions").then().statusCode(401)
            asUser().get("$url/revisions?size=101").then().statusCode(400)
            asUser().get("$url/revisions?page=-1").then().statusCode(400)
            asUser().get("$url/revisions/-1").then().statusCode(400)
            asUser().get("$url/revisions/99").then().statusCode(404)
            asUser().body(draft - "baseVersion").post("$url/merge").then().statusCode(400)
            asUser().body(draft + ("baseVersion" to -1)).post("$url/merge").then().statusCode(400)
            asUser().body(draft + ("baseVersion" to 99)).post("$url/merge").then().statusCode(404)
            asUser().body(draft + ("title" to " ")).post("$url/merge").then().statusCode(400)
            asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(1))
        } finally {
            cleanup(id)
        }
    }

    @Test
    fun `combined content exceeding limit is rejected without a revision`() {
        val id = create("a\nb\nc")
        val url = "$path/$id"
        try {
            asUser().body(mapOf("title" to "Planning", "content" to "${"a".repeat(30000)}\nb\nc", "version" to 0))
                .put(url).then().statusCode(200)
            asUser(bob).body(mapOf("title" to "Planning", "content" to "a\nb\n${"c".repeat(30000)}", "baseVersion" to 0))
                .post("$url/merge").then().statusCode(400)
            asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(2))
            asUser().get(url).then().statusCode(200).body("version", equalTo(1))
        } finally {
            cleanup(id)
        }
    }

    @Test
    fun `concurrent saves produce one winner and no orphan revision`() {
        val id = create()
        val url = "$path/$id"
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val results = listOf(alice, bob).map { user ->
                executor.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    asUser(user).body(mapOf("title" to user, "content" to user, "version" to 0)).put(url)
                })
            }.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(listOf(200, 409), results.map { it.statusCode }.sorted())
            val winner = results.single { it.statusCode == 200 }.jsonPath().getString("title")
            asUser().get(url).then().statusCode(200).body("title", equalTo(winner)).body("version", equalTo(1))
            asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(2))
            asUser().get("$url/revisions/1").then().statusCode(200).body("title", equalTo(winner))
        } finally {
            executor.shutdownNow()
            cleanup(id)
        }
    }
}
