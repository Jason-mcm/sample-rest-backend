package com.example.notes

import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class RevisionMigrationTest {
    @Inject
    lateinit var dataSource: AgroalDataSource

    @Test
    fun `upgrade preserves existing note as a baseline revision`() {
        val schema = "revision_upgrade_test"
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("2").load().migrate()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""
                        INSERT INTO $schema.note
                          (id, team_id, title, content, created_by, updated_by, created_at, updated_at, version)
                        VALUES (
                          '20000000-0000-0000-0000-000000000001',
                          '10000000-0000-0000-0000-000000000001',
                          'Existing note', 'Existing content',
                          '00000000-0000-0000-0000-000000000001',
                          '00000000-0000-0000-0000-000000000002',
                          '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z', 7
                        )
                    """.trimIndent())
                }
            }
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .load().migrate()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM $schema.note_revision").use { result ->
                        assertTrue(result.next())
                        assertEquals(7L, result.getLong("version"))
                        assertEquals("Existing content", result.getString("content"))
                        assertEquals("00000000-0000-0000-0000-000000000002", result.getString("edited_by"))
                        assertEquals("2026-01-02T00:00:00Z", result.getTimestamp("edited_at").toInstant().toString())
                        assertFalse(result.next())
                    }
                }
            }
        } finally {
            // This schema exists only inside the disposable integration-test database.
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }
}
