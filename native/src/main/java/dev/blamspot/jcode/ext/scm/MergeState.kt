package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One stretch of a conflicted file: either lines both sides agree on, or a disagreement.
 *
 * The whole file is kept, not just the conflicts, because saving has to put it back together — a
 * page that only knew about the conflicts could not write the file it was editing.
 */
internal data class MergeSegment(
    val conflict: Boolean,
    val text: List<String>,
    val ours: List<String>,
    val theirs: List<String>,
)

/**
 * Split a file on its conflict markers.
 *
 * The `|||||||` base section of a diff3-style conflict is skipped rather than offered: it is what
 * both sides started from, which explains the conflict but is never the answer to it.
 */
internal fun parseConflicts(raw: String): List<MergeSegment> {
    val lines = raw.split('\n')
    val segments = ArrayList<MergeSegment>()
    var buffer = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].startsWith("<<<<<<<")) {
            buffer.add(lines[i])
            i++
            continue
        }
        if (buffer.isNotEmpty()) {
            segments += MergeSegment(false, buffer, emptyList(), emptyList())
            buffer = ArrayList()
        }
        val ours = ArrayList<String>()
        val theirs = ArrayList<String>()
        i++
        while (i < lines.size && !lines[i].startsWith("=======") && !lines[i].startsWith("|||||||")) {
            ours.add(lines[i])
            i++
        }
        if (i < lines.size && lines[i].startsWith("|||||||")) {
            i++
            while (i < lines.size && !lines[i].startsWith("=======")) i++
        }
        if (i < lines.size && lines[i].startsWith("=======")) i++
        while (i < lines.size && !lines[i].startsWith(">>>>>>>")) {
            theirs.add(lines[i])
            i++
        }
        if (i < lines.size && lines[i].startsWith(">>>>>>>")) i++
        segments += MergeSegment(true, emptyList(), ours, theirs)
    }
    if (buffer.isNotEmpty()) segments += MergeSegment(false, buffer, emptyList(), emptyList())
    return segments
}

/**
 * Where a line stands relative to the merged result — the bar down its left edge.
 *
 * Added and Removed are about the result, not about the file: a side's line is "added" once the
 * result carries it and "removed" while it does not. Conflicted is the third state, for a block
 * still holding exactly what git left there, which is the one that means nothing has been decided.
 */
internal enum class LineMark { None, Conflicted, Added, Removed }

/**
 * One line of the file, in all three versions at once.
 *
 * Null means the version has no line here — one side said three lines where the other said one, so
 * the shorter gets a blank to keep the three columns level. That filler is what makes reading across
 * a row mean anything, and it is why the panes can share a scroll position.
 *
 * [conflict] is the index of the conflict the line belongs to, or -1 for a line all three agree on.
 */
internal data class MergeRow(
    val theirs: String?,
    val mine: String?,
    val merged: String?,
    val theirsNo: Int,
    val mineNo: Int,
    val mergedNo: Int,
    val conflict: Int,
    /** The segment this row came from, which is the unit the merged pane edits. */
    val segment: Int,
)

/**
 * A conflicted file, and what the user has decided about each conflict.
 *
 * The decisions live beside the segments rather than in the text: the file is only rewritten on
 * save, so until then nothing has been changed on disk and closing the tab costs nothing.
 */
internal class MergeState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
    view: String,
) {
    private val git = Git(host)
    private val target = parseRefView(view, "merge")

    val path: String = target?.second.orEmpty()
    private val carriedRepo: String = target?.first.orEmpty()
    private var repo: String? = null

    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    val segments = mutableStateListOf<MergeSegment>()

    /** The chosen text per segment, positionally — blank for a segment that is not a conflict. */
    val resolutions = mutableStateListOf<String>()

    /** Whether the file still holds markers at all; false means there is nothing here to resolve. */
    var conflicted by mutableStateOf(false)
        private set

    /** Which conflict the navigation is on. Zero-based over the conflicts, not over the segments. */
    var current by mutableStateOf(0)
        private set

    /** Side by side needs width; below that the three panes stack. */
    var split by mutableStateOf(true)

    /** How many conflicts there are, which is what "3 of 7" counts. */
    val conflictCount: Int get() = segments.count { it.conflict }

    /** The index in [segments] of conflict number [n], for scrolling to it. */
    fun segmentOf(n: Int): Int {
        var seen = 0
        segments.forEachIndexed { i, segment ->
            if (segment.conflict) {
                if (seen == n) return i
                seen++
            }
        }
        return 0
    }

    /** The 0-based conflict number of the segment at [index], for labelling it. */
    fun conflictNumberAt(index: Int): Int = segments.take(index).count { it.conflict }

    fun goTo(n: Int) {
        current = n.coerceIn(0, (conflictCount - 1).coerceAtLeast(0))
        editing = segmentOf(current)
    }

    fun boot() {
        scope.launch {
            loading = true
            val root = resolveRepo()
            if (root == null) {
                error = "This project isn't a git repository."
                loading = false
                return@launch
            }
            repo = root
            if (path.isEmpty()) {
                error = "Couldn't read which file to merge."
                loading = false
                return@launch
            }
            val r = host.exec("cat " + Git.quote(path), workdir = root)
            val parsed = parseConflicts(r.stdout)
            segments.replaceWith(parsed)
            // Every segment, not only the conflicts: the merged pane is the result, and the
            // result is editable throughout. A conflict starts on our side, the rest as they are.
            resolutions.replaceWith(
                parsed.map { (if (it.conflict) it.ours else it.text).joinToString("\n") },
            )
            conflicted = parsed.any { it.conflict }
            editing = parsed.indexOfFirst { it.conflict }
            loading = false
        }
    }

    private suspend fun resolveRepo(): String? {
        if (carriedRepo.isNotEmpty()) return carriedRepo
        val project = host.projectInfo()?.path ?: return null
        val top = git.run(project, "rev-parse --show-toplevel 2>/dev/null", timeoutMs = 20_000L)
        return top.output.lines().lastOrNull { it.isNotBlank() }?.trim()
            ?.takeIf { top.ok && it.isNotEmpty() && it != "/" }
    }

    /** Bumped on every edit, so the three panes are rebuilt only when the result actually changed. */
    var revision by mutableStateOf(0)
        private set

    /** The segment open for editing in the merged pane; -1 while none is. */
    var editing by mutableStateOf(-1)
        private set

    /** Open a block for editing, which is what tapping a line in the merged pane does. */
    fun editSegment(index: Int) {
        if (index in segments.indices) editing = index
    }

    /** The merged text of a segment, and the way to change it. */
    fun textOf(segment: Int): String = resolutions.getOrElse(segment) { "" }

    fun edit(segment: Int, text: String) = choose(segment, text)

    fun choose(index: Int, text: String) {
        if (index !in resolutions.indices) return
        resolutions[index] = text
        revision++
    }

    /** The resolution text of conflict number [n], for the field that edits it. */
    fun resolutionOf(n: Int): String = resolutions.getOrElse(segmentOf(n)) { "" }

    fun editCurrent(text: String) = choose(segmentOf(current), text)

    /**
     * What bar a side's line gets.
     *
     * Green once the line is in the merged result, red while it is not. Scanning a side that way
     * shows what you have taken without reading the result and comparing by eye — which is the whole
     * job when a conflict runs to more lines than fit on a screen.
     */
    fun markForSide(row: MergeRow, line: String?): LineMark = when {
        row.conflict < 0 || line == null -> LineMark.None
        isTaken(row.conflict, line) -> LineMark.Added
        else -> LineMark.Removed
    }

    /**
     * What bar a merged line gets.
     *
     * A blank inside a conflict is a line one side offered and the result does not carry, so it is
     * marked removed; a line the result does carry is added. Amber is for a conflict still holding
     * exactly what git left there, which is the one state that means "not decided yet".
     */
    fun markForMerged(row: MergeRow): LineMark = when {
        row.conflict < 0 -> LineMark.None
        row.merged == null -> LineMark.Removed
        untouched(row.conflict) -> LineMark.Conflicted
        else -> LineMark.Added
    }

    /** Whether a conflict still holds the seeded default, i.e. nothing has been decided about it. */
    private fun untouched(n: Int): Boolean = resolutionOf(n) == mineOf(n)

    /**
     * The whole file, three versions side by side.
     *
     * Built rather than shown as hunks because that is the thing TortoiseGitMerge gets right: a
     * conflict is easiest to judge in the middle of the file it is in, not extracted from it. Lines
     * both sides agree on appear in all three columns; a conflict contributes as many rows as its
     * longest side, and the shorter sides get blanks.
     */
    fun buildRows(): List<MergeRow> {
        val out = ArrayList<MergeRow>()
        var t = 0
        var m = 0
        var g = 0
        var conflictIndex = -1
        segments.forEachIndexed { i, segment ->
            if (segment.conflict) conflictIndex++
            // The sides are what the file had; the merged column is what it is being made into,
            // which is the edited text whether or not this block was ever in conflict.
            val theirs = if (segment.conflict) segment.theirs else segment.text
            val mine = if (segment.conflict) segment.ours else segment.text
            val merged = resolutions.getOrElse(i) { "" }
                .let { if (it.isEmpty()) emptyList() else it.split('\n') }
            val height = maxOf(theirs.size, mine.size, merged.size)
            for (k in 0 until height) {
                val a = theirs.getOrNull(k)
                val b = mine.getOrNull(k)
                val c = merged.getOrNull(k)
                out += MergeRow(
                    theirs = a,
                    mine = b,
                    merged = c,
                    theirsNo = if (a != null) ++t else 0,
                    mineNo = if (b != null) ++m else 0,
                    mergedNo = if (c != null) ++g else 0,
                    conflict = if (segment.conflict) conflictIndex else -1,
                    segment = i,
                )
            }
        }
        return out
    }


    /** The lines the merged result currently holds for conflict [n]. */
    fun mergedLinesOf(n: Int): List<String> {
        val text = resolutionOf(n)
        return if (text.isEmpty()) emptyList() else text.split('\n')
    }

    /**
     * Add one line from either side to the merged result.
     *
     * Appended rather than inserted at its own position: composing a block a line at a time is done
     * in the order you pick them, and an insert that guessed the position would move lines you had
     * already placed.
     */
    fun useLine(n: Int, line: String) {
        val lines = mergedLinesOf(n) + line
        choose(segmentOf(n), lines.joinToString("\n"))
    }

    /** Make one line the whole of the merged result for this conflict. */
    fun useOnlyLine(n: Int, line: String) = choose(segmentOf(n), line)

    /** Drop the first copy of a line from the merged result. */
    fun dropLine(n: Int, line: String) {
        val lines = mergedLinesOf(n).toMutableList()
        val at = lines.indexOf(line)
        if (at < 0) return
        lines.removeAt(at)
        choose(segmentOf(n), lines.joinToString("\n"))
    }

    fun clearConflict(n: Int) = choose(segmentOf(n), "")

    /** Whether a side's line has been taken into the result — what its bar reports. */
    fun isTaken(n: Int, line: String?): Boolean = line != null && line in mergedLinesOf(n)

    /** Conflict [n]'s incoming text. */
    fun theirsOf(n: Int): String =
        segments.getOrNull(segmentOf(n))?.theirs?.joinToString("\n").orEmpty()

    /** Conflict [n]'s current text. */
    fun mineOf(n: Int): String =
        segments.getOrNull(segmentOf(n))?.ours?.joinToString("\n").orEmpty()

    /** Both sides, in the order git wrote them, with a newline between when both have content. */
    fun bothOf(n: Int): String {
        val mine = mineOf(n)
        val theirs = theirsOf(n)
        return mine + (if (mine.isNotEmpty() && theirs.isNotEmpty()) "\n" else "") + theirs
    }

    /**
     * Write the reassembled file and stage it.
     *
     * Through base64 rather than a heredoc: the resolved text is arbitrary — quotes, backslashes,
     * `$`, a line that happens to read like a terminator — and every shell-quoting scheme has some
     * input that escapes it. base64 has none.
     */
    fun save() {
        val root = repo ?: return
        if (busy) return
        scope.launch {
            busy = true
            message = "Saving…"
            failed = false
            val merged = segments.mapIndexed { i, segment ->
                resolutions.getOrElse(i) { segment.text.joinToString("\n") }
            }.joinToString("\n")
            val encoded = java.util.Base64.getEncoder().encodeToString(merged.toByteArray(Charsets.UTF_8))
            val write = host.exec(
                "printf %s " + Git.quote(encoded) + " | base64 -d > " + Git.quote(path),
                workdir = root,
                timeoutMs = 120_000L,
            )
            if (!write.ok) {
                busy = false
                failed = true
                message = write.failure
                return@launch
            }
            val add = git.run(root, "add -- " + Git.quote(path))
            busy = false
            failed = !add.ok
            message = if (add.ok) "Resolved and staged. Close this tab and commit from Source Control."
            else add.failure
        }
    }
}
