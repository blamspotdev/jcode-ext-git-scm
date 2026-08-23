package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a line of a unified diff is. */
internal enum class DiffLineKind { Context, Add, Delete, Hunk, Meta }

/**
 * One rendered row.
 *
 * [gutter] is the line number in the *new* file, which is the one you can open — a removed line has
 * no number there, so it shows none. [openLine] is where tapping the row should land, which for a
 * removed line is the nearest following line that still exists.
 */
internal data class DiffLine(
    val kind: DiffLineKind,
    val gutter: String,
    val openLine: Int,
    val text: String,
)

/** A file a stash touched. */
internal data class StashFile(val status: String, val path: String)

/**
 * Turn unified-diff text into rows.
 *
 * Written out rather than regex-per-line at render time because a diff can be thousands of lines and
 * the list recomposes: parsing once into a list the renderer only reads is the difference between a
 * page that scrolls and one that stutters.
 */
internal fun parseDiff(text: String): List<DiffLine> {
    val body = text.removeSuffix("\n")
    if (body.isBlank()) return emptyList()
    val out = ArrayList<DiffLine>()
    var newLine = 0
    for (line in body.split('\n')) {
        val hunk = HUNK_HEADER.find(line)
        when {
            hunk != null -> {
                newLine = hunk.groupValues[1].toIntOrNull() ?: 1
                out += DiffLine(DiffLineKind.Hunk, "", newLine, line)
            }
            META_LINE.containsMatchIn(line) -> out += DiffLine(DiffLineKind.Meta, "", 0, line)
            line.startsWith("+") -> {
                out += DiffLine(DiffLineKind.Add, newLine.toString(), newLine, line)
                newLine++
            }
            line.startsWith("-") -> out += DiffLine(DiffLineKind.Delete, "", newLine, line)
            else -> {
                out += DiffLine(DiffLineKind.Context, newLine.toString(), newLine, line)
                newLine++
            }
        }
    }
    return out
}

private val HUNK_HEADER = Regex("^@@ -\\d+(?:,\\d+)? \\+(\\d+)")

private val META_LINE = Regex(
    "^(?:diff --git|index |new file|deleted file|old mode|new mode|similarity |rename |copy |--- |\\+\\+\\+ |Binary )",
)

/**
 * A diff, for the page that shows one.
 *
 * Serves both the file diff and a stash's patch: they differ in the command that produces the text
 * and in whether a row can be tapped — a stash is not in the working tree, so there is no file to
 * open at that line.
 */
internal class DiffState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
    private val view: String,
) {
    private val git = Git(host)

    var title by mutableStateOf("")
        private set
    var subtitle by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Whether a row opens the real file. False for a stash, which has no working-tree counterpart. */
    var openable by mutableStateOf(false)
        private set

    val lines = mutableStateListOf<DiffLine>()
    val files = mutableStateListOf<StashFile>()

    private var repo: String? = null
    private var path: String = ""

    /** Where "Open file" lands: the first changed line, not the top of the file. */
    var firstChangedLine by mutableStateOf(1)
        private set

    fun boot() {
        scope.launch {
            loading = true
            val stash = parseRefView(view, "stash")
            if (stash != null) loadStash(stash.first, stash.second) else loadFileDiff()
            loading = false
        }
    }

    private suspend fun resolveRepo(carried: String): String? {
        if (carried.isNotEmpty()) return carried
        // Only when the id carried none: a page that re-derives the repository guesses wrong in a
        // workspace holding more than one.
        val project = host.projectInfo()?.path ?: return null
        val top = git.run(project, "rev-parse --show-toplevel 2>/dev/null", timeoutMs = 20_000L)
        return top.output.lines().lastOrNull { it.isNotBlank() }?.trim()
            ?.takeIf { top.ok && it.isNotEmpty() && it != "/" }
    }

    private suspend fun loadFileDiff() {
        val target = parseDiffView(view) ?: run {
            error = "Couldn't read which diff to show."
            return
        }
        path = target.path
        title = target.path
        subtitle = when (target.mode) {
            DiffMode.Staged -> "Staged changes"
            DiffMode.Untracked -> "New file"
            DiffMode.Working -> "Working-tree changes"
        }
        val root = resolveRepo(target.repo) ?: run {
            error = "This project isn't a git repository."
            return
        }
        repo = root
        openable = true
        val quoted = Git.quote(target.path)
        val args = when (target.mode) {
            DiffMode.Staged -> "diff --cached --no-color -- $quoted"
            // --no-index against /dev/null shows a file git has never seen as one big addition.
            DiffMode.Untracked -> "diff --no-index --no-color -- /dev/null $quoted"
            DiffMode.Working -> "diff --no-color -- $quoted"
        }
        val r = git.run(root, args)
        // `diff --no-index` exits 1 when the files differ, which is the normal case here, so the
        // text is what matters rather than the code.
        lines.replaceWith(parseDiff(r.output))
        firstChangedLine = lines.firstOrNull { it.kind == DiffLineKind.Hunk }?.openLine ?: 1
    }

    private suspend fun loadStash(carriedRepo: String, ref: String) {
        title = ref
        subtitle = "Stashed changes"
        val root = resolveRepo(carriedRepo) ?: run {
            error = "This project isn't a git repository."
            return
        }
        repo = root
        openable = false
        val quoted = Git.quote(ref)
        // --include-untracked arrived in git 2.32; an older git rejects the flag rather than the
        // request, so the same command without it is the fallback, not a different answer.
        var names = git.run(root, "stash show --name-status --include-untracked $quoted")
        if (!names.ok) names = git.run(root, "stash show --name-status $quoted")
        files.replaceWith(
            names.stdout.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 2) null
                    else StashFile(parts[0].trim().take(1), parts.last().trim())
                },
        )
        var patch = git.run(root, "stash show -p --include-untracked --no-color $quoted")
        if (!patch.ok) patch = git.run(root, "stash show -p --no-color $quoted")
        lines.replaceWith(parseDiff(patch.output))
    }

    /** Open the working-tree file at [line]; the repo root plus the repo-relative path. */
    fun openAt(line: Int) {
        val root = repo ?: return
        if (!openable || path.isEmpty()) return
        host.openFile(root.trimEnd('/') + "/" + path, line.takeIf { it > 0 })
    }
}
