package com.example.notes

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification

@QuarkusTest
class NoteApiTest {
    private val alice: String = "00000000-0000-0000-0000-000000000001"
    private val bob: String = "00000000-0000-0000-0000-000000000002"
    private val charlie: String = "00000000-0000-0000-0000-000000000003"
    private val engineering: String = "10000000-0000-0000-0000-000000000001"
    private val design: String = "10000000-0000-0000-0000-000000000002"
    private val path: String = "/api/teams/$engineering/notes"

    private val createdUrls: MutableList<String> = mutableListOf()

    @AfterEach
    fun cleanUpNotes(): Unit {
        for (url: String in createdUrls) {
            val response: Response = asUser().get(url)
            if (response.statusCode == 200) {
                asUser().queryParam("version", response.jsonPath().getLong("version"))
                    .delete(url).then().statusCode(204)
            } else {
                response.then().statusCode(404)
            }
        }
    }

    @Test
    fun `a teammate can read and list a created note`() {
        val url: String = createNote()
        val id: String = url.substringAfterLast('/')

        asUser(bob).get(url).then()
            .statusCode(200)
            .body("title", equalTo("Planning"))
        asUser().queryParam("size", 100).get(path).then()
            .statusCode(200)
            .body("items.id", hasItem(id))
    }

    @Test
    fun `nonmembers cannot read change delete or list team notes`() {
        val url: String = createNote()
        val edit: Map<String, Any> = mapOf("title" to "Unauthorized", "content" to "Hidden", "version" to 0)

        asUser(charlie).get(url).then().statusCode(404)
        asUser(charlie).get(path).then().statusCode(404)
        asUser(charlie).body(edit).put(url).then().statusCode(404)
        asUser(charlie).queryParam("version", 0).delete(url).then().statusCode(404)
        asUser().get(url).then().statusCode(200).body("title", equalTo("Planning"))
    }

    @Test
    fun `membership in two teams does not allow moving a note between team routes`() {
        val url: String = createNote()
        val id: String = url.substringAfterLast('/')
        val otherTeamUrl: String = "/api/teams/$design/notes/$id"
        val edit: Map<String, Any> = mapOf("title" to "Unauthorized", "content" to "Hidden", "version" to 0)

        asUser(bob).get(otherTeamUrl).then().statusCode(404)
        asUser(bob).body(edit).put(otherTeamUrl).then().statusCode(404)
        asUser(bob).queryParam("version", 0).delete(otherTeamUrl).then().statusCode(404)
        asUser(bob).get("/api/teams/$design/notes").then()
            .statusCode(200)
            .body("items.id", not(hasItem(id)))
        asUser().get(url).then().statusCode(200).body("title", equalTo("Planning"))
    }

    @Test
    fun `stale updates and deletes preserve the winning edit`() {
        val url: String = createNote()
        val winningEdit: Map<String, Any> = mapOf("title" to "Revised", "content" to "Second draft", "version" to 0)
        val staleEdit: Map<String, Any> = mapOf("title" to "Stale", "content" to "Lost update", "version" to 0)

        asUser(bob).body(winningEdit).put(url).then()
            .statusCode(200)
            .body("version", equalTo(1))
            .body("updatedBy", equalTo(bob))
        asUser().body(staleEdit).put(url).then().statusCode(409)
        asUser().queryParam("version", 0).delete(url).then().statusCode(409)
        asUser().get(url).then().statusCode(200).body("content", equalTo("Second draft"))
    }

    @Test
    fun `deleting with the current version removes the note`() {
        val url: String = createNote()

        asUser().queryParam("version", 0).delete(url).then().statusCode(204)
        asUser().get(url).then().statusCode(404)
    }

    @Test
    fun `missing malformed and unknown identities are rejected`() {
        given().get(path).then().statusCode(401)
        asUser("not-a-uuid").get(path).then().statusCode(401)
        asUser("00000000-0000-0000-0000-000000000099").get(path).then().statusCode(401)
    }

    @Test
    fun `invalid note input is rejected`() {
        asUser().body(mapOf("title" to " ", "content" to "x")).post(path).then().statusCode(400)
        asUser().body(mapOf("title" to "x")).post(path).then().statusCode(400)
        asUser().body(mapOf("title" to "x", "content" to "x".repeat(50001))).post(path).then().statusCode(400)
        asUser().body("{broken").post(path).then().statusCode(400)
    }

    @Test
    fun `null request bodies are rejected as invalid requests`() {
        val url: String = createNote()

        asUser().body("null").post(path).then().statusCode(400)
        asUser().body("null").put(url).then().statusCode(400)
        asUser().body("null").post("$url/merge").then().statusCode(400)
    }

    @Test
    fun `invalid pagination is rejected`() {
        asUser().queryParam("page", -1).get(path).then().statusCode(400)
        asUser().queryParam("size", 101).get(path).then().statusCode(400)
    }

    @Test
    fun `nonmembers cannot create notes`() {
        asUser(charlie).body(mapOf("title" to "x", "content" to "x")).post(path).then().statusCode(404)
    }

    @Test
    fun `long titles can be created updated and retrieved`() {
        val title: String = "x".repeat(1000)
        val id: String = asUser().body(mapOf("title" to title, "content" to ""))
            .post(path).then()
            .statusCode(201)
            .body("title", equalTo(title))
            .extract().jsonPath().getString("id")
        val url: String = "$path/$id"
        createdUrls.add(url)

        asUser().get(url).then().statusCode(200).body("title", equalTo(title))
        val updatedTitle: String = "y".repeat(2000)
        asUser().body(mapOf("title" to updatedTitle, "content" to "", "version" to 0))
            .put(url).then().statusCode(200).body("title", equalTo(updatedTitle))
        asUser().get(url).then().statusCode(200).body("title", equalTo(updatedTitle))
    }

    @Test
    fun `team discovery reflects membership`() {
        asUser().get("/api/teams").then().statusCode(200).body("id", contains(engineering))
        asUser(bob).get("/api/teams").then().statusCode(200)
            .body("id", contains(design, engineering))
            .body("name", contains("Design", "Engineering"))
        asUser(charlie).get("/api/teams").then().statusCode(200).body("id", contains(design))
    }
    @Test
    fun `unparseable pagination returns the framework parameter conversion error`() {
        // Quarkus Spring Web rejects parameter conversion with 404 before invoking the controller.
        // Parsed integers outside the supported bounds are separately validated as 400.
        asUser().queryParam("page", "abc").get(path).then().statusCode(404)
        asUser().queryParam("size", "99999999999999999999").get(path).then().statusCode(404)
    }

    @Test
    fun `missing and negative delete versions leave note and history intact`() {
        val url: String = createNote()

        asUser().delete(url).then().statusCode(400)
        asUser().queryParam("version", -1).delete(url).then().statusCode(400)
        asUser().get(url).then().statusCode(200).body("version", equalTo(0))
        asUser().get("$url/revisions").then().statusCode(200).body("total", equalTo(1))
    }

    @Test
    fun `empty request bodies return validation errors`() {
        val url: String = createNote()

        asUser().post(path).then().statusCode(400)
        asUser().put(url).then().statusCode(400)
        asUser().post("$url/merge").then().statusCode(400)
    }

    @Test
    fun `validation errors have a stable JSON response`() {
        asUser().body(mapOf("title" to " ", "content" to "x")).post(path).then()
            .statusCode(400)
            .contentType(ContentType.JSON)
            .body("status", equalTo(400))
            .body("message", equalTo("title is required and cannot be blank"))
    }

    @Test
    fun `unchanged saves still create attributed revisions and advance the version`() {
        val url: String = createNote()

        asUser(bob).body(mapOf("title" to "Planning", "content" to "First draft", "version" to 0))
            .put(url).then().statusCode(200).body("version", equalTo(1)).body("updatedBy", equalTo(bob))
        asUser().body(mapOf("title" to "Planning", "content" to "First draft", "baseVersion" to 0))
            .post("$url/merge").then().statusCode(200).body("version", equalTo(2))
        asUser().get("$url/revisions").then().statusCode(200)
            .body("items.version", contains(2, 1, 0))
            .body("items.editedBy", contains(alice, bob, alice))
    }

    @Test
    fun `pages beyond the last note or revision are empty but keep the total`() {
        val url: String = createNote()

        asUser().queryParam("page", 100000).queryParam("size", 100).get(path).then()
            .statusCode(200).body("items", empty<Any>()).body("total", greaterThanOrEqualTo(1))
        asUser().queryParam("page", 100000).queryParam("size", 100).get("$url/revisions").then()
            .statusCode(200).body("items", empty<Any>()).body("total", equalTo(1))
    }

    private fun createNote(): String {
        val id: String = asUser()
            .body(mapOf("title" to "Planning", "content" to "First draft"))
            .post(path).then()
            .statusCode(201)
            .header("Location", containsString(path))
            .body("version", equalTo(0))
            .extract().jsonPath().getString("id")
        val url: String = "$path/$id"
        createdUrls.add(url)
        return url
    }

    private fun asUser(user: String = alice): RequestSpecification =
        given().header("X-User-Id", user).contentType(ContentType.JSON)
}
