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
            resolutions.replaceWith(parsed.map { if (it.conflict) it.ours.joinToString("\n") else "" })
            conflicted = parsed.any { it.conflict }
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

    fun choose(index: Int, text: String) {
        if (index in resolutions.indices) resolutions[index] = text
    }

    /** Both sides, in the order git wrote them, with a newline between when both have content. */
    fun both(segment: MergeSegment): String {
        val ours = segment.ours.joinToString("\n")
        val theirs = segment.theirs.joinToString("\n")
        return ours + (if (ours.isNotEmpty() && theirs.isNotEmpty()) "\n" else "") + theirs
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
                if (segment.conflict) resolutions.getOrElse(i) { "" } else segment.text.joinToString("\n")
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
