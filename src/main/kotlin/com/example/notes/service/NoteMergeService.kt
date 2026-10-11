package com.example.notes.service

import com.example.notes.model.LineEdit
import org.springframework.stereotype.Service

/**
 * Combines note fields using a shared base, the current saved value, and a caller's draft.
 *
 * Merge operations are stateless and return null when a field needs manual resolution.
 * [NoteService] handles authorization, validation, conflict responses, and persistence.
 */
@Service
class NoteMergeService {
    /**
     * Merges a title as a whole field without combining individual words or characters.
     * Identical values are accepted; if only one side changed from the base, that side wins.
     *
     * @param base title from the shared revision that both edits started from.
     * @param current title currently saved on the note.
     * @param draft title supplied by the caller.
     * @return the merged title, or null when both sides made different changes.
     * An empty string is a merge result, not a conflict; callers validate it separately.
     */
    fun mergeTitle(base: String, current: String, draft: String): String? = when {
        current == draft -> current
        current == base -> draft
        draft == base -> current
        else -> null
    }

    /**
     * Merges content by accepting identical or one-sided changes, then combining disjoint line edits.
     * Each writer's changes form one span, including unchanged lines between separate edits.
     * Overlapping spans and insertions touching another span's boundary require manual resolution.
     * Lines are split on LF; carriage returns and trailing newlines are preserved.
     *
     * @param base content from the shared revision that both edits started from.
     * @param current content currently saved on the note.
     * @param draft content supplied by the caller.
     * @return the merged content, including an empty string when appropriate, or null on conflict.
     * The caller must validate the merged content's length before saving it.
     */
    fun mergeContent(base: String, current: String, draft: String): String? {
        mergeTitle(base, current, draft)?.let { merged: String -> return merged }
        val original: List<String> = base.split('\n')
        val currentEdit: LineEdit = change(original, current.split('\n'))
        val draftEdit: LineEdit = change(original, draft.split('\n'))
        val edits: List<LineEdit> = listOf(currentEdit, draftEdit).sortedBy { edit: LineEdit -> edit.start }
        val first: LineEdit = edits[0]
        val second: LineEdit = edits[1]
        // Insertions touching another change's boundary are ambiguous, so reject them.
        val hasInsertion: Boolean = first.start == first.end || second.start == second.end
        if (first.end > second.start || (hasInsertion && first.end == second.start)) {
            return null
        }
        return buildList<String> {
            addAll(original.subList(0, first.start))
            addAll(first.replacement)
            addAll(original.subList(first.end, second.start))
            addAll(second.replacement)
            addAll(original.subList(second.end, original.size))
        }.joinToString("\n")
    }

    // One conservative span per writer, computed in linear time. Multiple separated
    // edits by one writer include their unchanged middle lines in the span.
    private fun change(base: List<String>, edited: List<String>): LineEdit {
        var start: Int = 0
        while (start < minOf(base.size, edited.size) && base[start] == edited[start]) {
            start++
        }
        var baseEnd: Int = base.size
        var editedEnd: Int = edited.size
        while (baseEnd > start && editedEnd > start && base[baseEnd - 1] == edited[editedEnd - 1]) {
            baseEnd--
            editedEnd--
        }
        return LineEdit(start, baseEnd, edited.subList(start, editedEnd))
    }
}
