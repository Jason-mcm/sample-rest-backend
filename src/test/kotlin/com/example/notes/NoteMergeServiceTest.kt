package com.example.notes

import com.example.notes.service.NoteMergeService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NoteMergeServiceTest {
    private val merger = NoteMergeService()

    @Test
    fun `unchanged and identical edits resolve without conflicts`() {
        assertEquals("new", merger.mergeTitle("old", "old", "new"))
        assertEquals("new", merger.mergeTitle("old", "new", "old"))
        assertEquals("new", merger.mergeTitle("old", "new", "new"))
        assertNull(merger.mergeTitle("old", "alice", "bob"))
        assertEquals("", merger.mergeContent("old", "", "old"))
    }

    @Test
    fun `separate line changes merge in either order and preserve line endings`() {
        val base = "one\r\ntwo\r\nthree\r\n"
        val alice = "ONE\r\ntwo\r\nthree\r\n"
        val bob = "one\r\ntwo\r\nTHREE\r\n"
        val expected = "ONE\r\ntwo\r\nTHREE\r\n"
        assertEquals(expected, merger.mergeContent(base, alice, bob))
        assertEquals(expected, merger.mergeContent(base, bob, alice))
        assertEquals("ONE\nTWO", merger.mergeContent("one\ntwo", "ONE\ntwo", "one\nTWO"))
    }

    @Test
    fun `separate insertion and deletion merge without losing unchanged lines`() {
        assertEquals("a\nNEW\nb\nc", merger.mergeContent("a\nb\nc\nd", "a\nNEW\nb\nc\nd", "a\nb\nc"))
        assertEquals("A\nb\nc\nNEW", merger.mergeContent("a\nb\nc", "A\nb\nc", "a\nb\nc\nNEW"))
    }

    @Test
    fun `overlaps same position insertions and ambiguous boundaries require a decision`() {
        assertNull(merger.mergeContent("one", "alice", "bob"))
        assertNull(merger.mergeContent("a\nb", "a\nx\nb", "a\ny\nb"))
        assertNull(merger.mergeContent("a\nb", "a\nx\nb", "a\nB"))
        assertNull(merger.mergeContent("a\nb\nc", "A\nb\nC", "a\nB\nc"))
        assertNull(merger.mergeContent("a\nb", "", "a\nB"))
    }
    @Test
    fun `disjoint replacements merge symmetrically at every pair of line positions`() {
        val lines: List<String> = listOf("α", "", "same", "same", "😀", "last", "")
        val base: String = lines.joinToString("\n")
        for (left: Int in lines.indices) {
            for (right: Int in lines.indices.filter { index: Int -> index != left }) {
                val current: String = lines.mapIndexed { index: Int, line: String -> if (index == left) "LEFT" else line }.joinToString("\n")
                val draft: String = lines.mapIndexed { index: Int, line: String -> if (index == right) "RIGHT" else line }.joinToString("\n")
                val expected: String = lines.mapIndexed { index: Int, line: String ->
                    when (index) { left -> "LEFT"; right -> "RIGHT"; else -> line }
                }.joinToString("\n")

                assertEquals(expected, merger.mergeContent(base, current, draft), "positions $left and $right")
                assertEquals(expected, merger.mergeContent(base, draft, current), "reversed positions $left and $right")
            }
        }
    }

    @Test
    fun `empty content and identical deletions are successful merges`() {
        assertEquals("", merger.mergeContent("", "", ""))
        assertEquals("new", merger.mergeContent("", "", "new"))
        assertEquals("", merger.mergeContent("old", "", ""))
        assertEquals("a\nc", merger.mergeContent("a\nb\nc", "a\nc", "a\nc"))
    }

    @Test
    fun `deletions on adjacent lines merge without restoring deleted text`() {
        assertEquals("a\nd", merger.mergeContent("a\nb\nc\nd", "a\nc\nd", "a\nb\nd"))
    }

    @Test
    fun `different insertions into an empty document conflict`() {
        assertNull(merger.mergeContent("", "Alice", "Bob"))
    }
}
