package dev.blamspot.jcode.ext.scm

/**
 * A unified diff, taken apart far enough to be compared rather than merely read.
 *
 * git prints a diff for a terminal: one column, a `+` or `-` at the front of each line, and a header
 * repeating the path. Rendering that verbatim is honest but not useful — you cannot see which
 * *word* in a line changed, you cannot see where a deleted line used to be, and you cannot act on
 * one hunk. So the text is parsed once into this shape, and everything the page does afterwards is
 * a read of it.
 */

/** What a line of a unified diff is. */
internal enum class DiffLineKind { Context, Add, Delete, Hunk, Meta }

/**
 * One line inside a hunk, with both line numbers.
 *
 * Both, because a diff is a comparison: a deleted line has a number in the old file and none in the
 * new one, and a reader who cannot see the old number cannot say where the deletion was.
 * [PatchLine.text] excludes git's leading `+`/`-`/space — the prefix is the [kind].
 */
internal data class PatchLine(
    val kind: DiffLineKind,
    val text: String,
    /** 1-based line in the old file, or 0 for an added line that never existed there. */
    val oldNo: Int,
    /** 1-based line in the new file, or 0 for a deleted line. */
    val newNo: Int,
)

/** One `@@` block, kept whole because staging and reverting act on exactly this much. */
internal data class Hunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<PatchLine>,
) {
    /** The hunk as git would print it — what `git apply` has to be handed back. */
    fun toPatch(): String = buildString {
        append(header).append('\n')
        lines.forEach { line ->
            append(
                when (line.kind) {
                    DiffLineKind.Add -> '+'
                    DiffLineKind.Delete -> '-'
                    else -> ' '
                },
            )
            append(line.text).append('\n')
        }
    }
}

/** A parsed diff: the file header git printed, and the hunks under it. */
internal data class ParsedDiff(
    /** `diff --git`, `index`, `---`, `+++` — dropped from the view, kept for rebuilding a patch. */
    val fileHeader: List<String>,
    val hunks: List<Hunk>,
) {
    val added: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.Add } }
    val removed: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.Delete } }
    val isEmpty: Boolean get() = hunks.isEmpty()

    /**
     * A patch containing just [hunk], for `git apply`.
     *
     * The file header is reused verbatim rather than rebuilt: it carries the blob hashes git wrote,
     * and a header assembled by hand is a header that disagrees with the index in some case nobody
     * tested.
     */
    fun patchFor(hunk: Hunk): String =
        fileHeader.joinToString("\n", postfix = "\n") + hunk.toPatch()
}

private val HUNK_HEADER = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

private val FILE_HEADER = Regex(
    "^(?:diff --git|index |new file|deleted file|old mode|new mode|similarity |rename |copy |--- |\\+\\+\\+ )",
)

/**
 * Read unified-diff text.
 *
 * Done once into a list the renderer only reads, because a diff runs to thousands of lines and the
 * list recomposes; matching a regex per line per frame is the difference between a page that
 * scrolls and one that stutters.
 */
internal fun parseDiff(text: String): ParsedDiff {
    val body = text.removeSuffix("\n")
    if (body.isBlank()) return ParsedDiff(emptyList(), emptyList())

    val fileHeader = ArrayList<String>()
    val hunks = ArrayList<Hunk>()

    var header: String? = null
    var oldStart = 0
    var oldCount = 0
    var newStart = 0
    var newCount = 0
    var oldNo = 0
    var newNo = 0
    var lines = ArrayList<PatchLine>()

    fun flush() {
        header?.let { hunks += Hunk(it, oldStart, oldCount, newStart, newCount, lines) }
        header = null
        lines = ArrayList()
    }

    for (raw in body.split('\n')) {
        val match = HUNK_HEADER.find(raw)
        if (match != null) {
            flush()
            header = raw
            oldStart = match.groupValues[1].toIntOrNull() ?: 1
            oldCount = match.groupValues[2].toIntOrNull() ?: 1
            newStart = match.groupValues[3].toIntOrNull() ?: 1
            newCount = match.groupValues[4].toIntOrNull() ?: 1
            oldNo = oldStart
            newNo = newStart
            continue
        }
        if (header == null) {
            if (FILE_HEADER.containsMatchIn(raw)) fileHeader += raw
            continue
        }
        when {
            // git's "\ No newline at end of file" belongs to the patch but is not a line of either
            // file; it carries no number and must survive into a rebuilt patch unchanged.
            raw.startsWith("\\") -> lines += PatchLine(DiffLineKind.Meta, raw.drop(1), 0, 0)
            raw.startsWith("+") -> lines += PatchLine(DiffLineKind.Add, raw.drop(1), 0, newNo++)
            raw.startsWith("-") -> lines += PatchLine(DiffLineKind.Delete, raw.drop(1), oldNo++, 0)
            else -> lines += PatchLine(DiffLineKind.Context, raw.drop(1), oldNo++, newNo++)
        }
    }
    flush()
    return ParsedDiff(fileHeader, hunks)
}

// --- alignment -----------------------------------------------------------------------------------

/** One side of an aligned row: absent where the other side has a line this one does not. */
internal data class Cell(
    val kind: DiffLineKind,
    val number: Int,
    val text: String,
    /** The part of [text] that actually differs from the opposite cell, or null when whole-line. */
    val changed: IntRange? = null,
)

/** A row of the comparison: an old line, a new line, or a pair that replaced one another. */
internal data class AlignedRow(val left: Cell?, val right: Cell?)

/** What sits between two hunks: lines neither side changed and git did not print. */
internal data class Gap(val lines: Int)

/** Either a row of the diff or the gap before it — the flat list the renderer walks. */
internal sealed interface DiffItem {
    data class HunkStart(val index: Int, val hunk: Hunk) : DiffItem
    data class Row(val row: AlignedRow) : DiffItem
    data class Unchanged(val gap: Gap) : DiffItem
}

/**
 * Pair a hunk's lines into rows.
 *
 * A run of deletions immediately followed by a run of additions is a replacement — the two runs are
 * paired off so the versions sit opposite each other, which is the whole point of a side-by-side
 * view. Anything left over on either side gets a blank across from it. A deletion with no addition
 * after it, or an addition with no deletion before it, is a one-sided row.
 */
internal fun align(hunk: Hunk): List<AlignedRow> {
    val rows = ArrayList<AlignedRow>()
    var i = 0
    val lines = hunk.lines
    while (i < lines.size) {
        val line = lines[i]
        if (line.kind == DiffLineKind.Context) {
            rows += AlignedRow(
                Cell(DiffLineKind.Context, line.oldNo, line.text),
                Cell(DiffLineKind.Context, line.newNo, line.text),
            )
            i++
            continue
        }
        if (line.kind == DiffLineKind.Meta) {
            rows += AlignedRow(Cell(DiffLineKind.Meta, 0, line.text), Cell(DiffLineKind.Meta, 0, line.text))
            i++
            continue
        }
        val deletes = ArrayList<PatchLine>()
        while (i < lines.size && lines[i].kind == DiffLineKind.Delete) deletes += lines[i++]
        val adds = ArrayList<PatchLine>()
        while (i < lines.size && lines[i].kind == DiffLineKind.Add) adds += lines[i++]

        val pairs = maxOf(deletes.size, adds.size)
        for (n in 0 until pairs) {
            val d = deletes.getOrNull(n)
            val a = adds.getOrNull(n)
            val span = if (d != null && a != null) changedSpans(d.text, a.text) else null
            rows += AlignedRow(
                d?.let { Cell(DiffLineKind.Delete, it.oldNo, it.text, span?.first) },
                a?.let { Cell(DiffLineKind.Add, it.newNo, it.text, span?.second) },
            )
        }
    }
    return rows
}

/**
 * Which part of two lines actually differs.
 *
 * The common head and tail are trimmed off and everything between is the change. That is not a
 * minimal edit script, but for one line it lands on the right answer nearly always — `title: Draft`
 * against `title: Release` highlights `Draft` and `Release` — and it costs one pass instead of a
 * quadratic one, on a list that recomposes.
 *
 * Returns null when the two lines share too little to be a rewrite of each other: highlighting
 * almost the whole of both says nothing that the row colour has not already said.
 */
internal fun changedSpans(old: String, new: String): Pair<IntRange, IntRange>? {
    if (old == new) return null
    var head = 0
    val limit = minOf(old.length, new.length)
    while (head < limit && old[head] == new[head]) head++
    // Back up to a word boundary so a shared prefix does not cut a word in half — "Draft" against
    // "Drop" would otherwise highlight "aft"/"op" and read as noise.
    while (head > 0 && old[head - 1].isLetterOrDigit()) head--

    var tail = 0
    while (tail < limit - head && old[old.length - 1 - tail] == new[new.length - 1 - tail]) tail++
    while (tail > 0 && old[old.length - tail].isLetterOrDigit()) tail--

    val oldEnd = old.length - tail
    val newEnd = new.length - tail
    if (oldEnd <= head && newEnd <= head) return null
    val shared = head + tail
    if (shared * 4 < maxOf(old.length, new.length)) return null
    return (head until oldEnd) to (head until newEnd)
}
