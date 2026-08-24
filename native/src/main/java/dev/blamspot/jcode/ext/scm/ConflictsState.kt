package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A file the merge could not settle by itself. */
internal data class Conflict(val path: String, val code: String)

/**
 * What is left to resolve in a merge in progress.
 *
 * Only the unresolved files. A resolved one has been staged and is no longer a question — leaving it
 * on the list would make finishing look further away than it is, and re-opening it would offer to
 * merge a file with no conflict markers left in it.
 */
internal class ConflictsState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
    view: String,
) {
    private val git = Git(host)
    private val carriedRepo: String = decodeUri(view.substringAfter("conflicts:", ""))
    private var repo: String? = null

    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** What is being merged into what, as the merge itself recorded it. */
    var into by mutableStateOf("")
        private set
    var from by mutableStateOf("")
        private set

    /** True while a merge is actually in progress — MERGE_HEAD is the thing that says so. */
    var merging by mutableStateOf(false)
        private set

    val files = mutableStateListOf<Conflict>()

    fun boot() {
        scope.launch {
            val root = resolveRepo()
            if (root == null) {
                error = "This project isn't a git repository."
                loading = false
                return@launch
            }
            repo = root
            refresh()
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

    fun reload() {
        if (busy) return
        scope.launch {
            busy = true
            refresh()
            busy = false
        }
    }

    private suspend fun refresh() {
        val root = repo ?: return
        into = git.run(root, "rev-parse --abbrev-ref HEAD").stdout.trim()
        // MERGE_HEAD only exists between `merge` and the commit that finishes it, which is exactly
        // the window this page is about. MERGE_MSG names the other side in words git wrote itself.
        val head = git.run(root, "rev-parse --verify --quiet MERGE_HEAD")
        merging = head.ok && head.stdout.isNotBlank()
        from = if (!merging) "" else {
            git.run(root, "name-rev --name-only MERGE_HEAD 2>/dev/null").stdout.trim()
        }
        // `diff --name-only --diff-filter=U` is the unresolved set and nothing else: a file that was
        // conflicted and has since been staged is no longer in it, which is the filter this page is.
        val r = git.run(root, "diff --name-only --diff-filter=U")
        error = if (r.ok) null else r.failure
        files.replaceWith(
            if (!r.ok) emptyList() else r.stdout.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Conflict(it, "U") },
        )
    }

    /** Open one file's three-way editor. Its own tab, because resolving a file is its own sitting. */
    fun open(conflict: Conflict) {
        val root = repo ?: return
        host.openView(
            "merge:" + encodeUri(root) + ":" + encodeUri(conflict.path),
            title = Git.baseName(conflict.path),
        )
    }

    /**
     * Take the whole file from one side.
     *
     * The three-way editor is for a file you have to read; this is for the ones you do not — a
     * lockfile, a generated bundle, anything where "take theirs" is the whole decision.
     */
    fun takeSide(conflict: Conflict, ours: Boolean) {
        val root = repo ?: return
        if (busy) return
        scope.launch {
            busy = true
            val side = if (ours) "--ours" else "--theirs"
            val quoted = Git.quote(conflict.path)
            val r = git.run(root, "checkout $side -- $quoted")
            if (r.ok) git.run(root, "add -- $quoted")
            error = if (r.ok) null else r.failure
            refresh()
            busy = false
        }
    }
}
