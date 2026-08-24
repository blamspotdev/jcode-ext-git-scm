package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A file a stash touched. */
internal data class StashFile(val status: String, val path: String)

/**
 * What the file is being compared against.
 *
 * A diff opened from the panel arrives with one of these already chosen, but the choice is the
 * page's to change: "what did I stage?" and "what has changed since `main`?" are the same question
 * asked of different sides, and a page that could only answer the one it was opened with is a
 * viewer rather than a comparison.
 */
internal sealed interface Compare {
    val label: String
    val leftTitle: String
    val rightTitle: String

    /** The working tree against the index — what is not staged yet. */
    data object WorkingVsIndex : Compare {
        override val label = "Working tree ↔ Index"
        override val leftTitle = "Index"
        override val rightTitle = "Working tree"
    }

    /** The index against HEAD — what a commit would contain. */
    data object IndexVsHead : Compare {
        override val label = "Index ↔ HEAD"
        override val leftTitle = "HEAD"
        override val rightTitle = "Index"
    }

    /** Everything since the last commit, staged or not. */
    data object WorkingVsHead : Compare {
        override val label = "Working tree ↔ HEAD"
        override val leftTitle = "HEAD"
        override val rightTitle = "Working tree"
    }

    /** A file git has never seen, shown whole as an addition. */
    data object Untracked : Compare {
        override val label = "New file"
        override val leftTitle = "Nothing"
        override val rightTitle = "New file"
    }

    /** Any branch the repository knows. */
    data class Ref(val name: String) : Compare {
        override val label = "Working tree ↔ $name"
        override val leftTitle = name
        override val rightTitle = "Working tree"
    }
}

/** Side-by-side or one column. Split needs width; inline works anywhere. */
internal enum class DiffLayout { Split, Inline }

/**
 * A diff, for the page that shows one.
 *
 * Serves the stash patch too: a stash is a diff like any other, and the only real difference is
 * that it is not in the working tree — so its rows open no file, and none of the actions apply.
 */
internal class DiffState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
    private val view: String,
) {
    private val git = Git(host)

    var title by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set

    /** Whether a row opens the real file. False for a stash, which has no working-tree counterpart. */
    var openable by mutableStateOf(false)
        private set

    /** Whether staging and reverting mean anything here — see [canAct]. */
    private var actionable by mutableStateOf(false)

    var compare by mutableStateOf<Compare>(Compare.WorkingVsIndex)
        private set
    var layout by mutableStateOf(DiffLayout.Split)
    var wrap by mutableStateOf(false)
    var showPicker by mutableStateOf(false)
    var confirm by mutableStateOf<Confirm?>(null)

    /** Every hunk, in order. Rendering walks [items]; this is what the actions act on. */
    val hunks = mutableStateListOf<Hunk>()

    /** Hunks, aligned rows and between-hunk gaps, flattened for a single lazy list. */
    val items = mutableStateListOf<DiffItem>()

    val refs = mutableStateListOf<String>()

    var added by mutableStateOf(0)
        private set
    var removed by mutableStateOf(0)
        private set

    /** True once the whole file is being shown, so the gap rows have nothing left to offer. */
    var expanded by mutableStateOf(false)
        private set

    /**
     * Bumped whenever the diff is replaced.
     *
     * The list is a different list after a stage, a revert or a change of comparison — often a
     * shorter one — so whatever it was scrolled to is gone. Without a signal to start again the
     * view keeps its old offset and opens part-way down a diff the reader has not seen.
     */
    var generation by mutableStateOf(0)
        private set

    /** Files a stash touched — shown above its patch, absent for a file diff. */
    val files = mutableStateListOf<StashFile>()

    private var parsed: ParsedDiff = ParsedDiff(emptyList(), emptyList())
    private var repo: String? = null
    private var path: String = ""
    private var stashRef: String? = null

    /** Staging and reverting are only unambiguous against the index; elsewhere they are hidden. */
    val canAct: Boolean get() = actionable && compare == Compare.WorkingVsIndex && !busy

    /** Under the title: for a stash, which entry it is; for a file, what it is being compared with. */
    val subtitle: String
        get() = stashRef?.let { "Stashed changes · $it" } ?: compare.label

    fun boot() {
        scope.launch {
            val stash = parseRefView(view, "stash")
            if (stash != null) {
                loadStash(stash.first, stash.second)
                loading = false
                return@launch
            }
            val target = parseDiffView(view)
            if (target == null) {
                error = "Couldn't read which diff to show."
                loading = false
                return@launch
            }
            path = target.path
            title = target.path
            compare = when (target.mode) {
                DiffMode.Staged -> Compare.IndexVsHead
                DiffMode.Untracked -> Compare.Untracked
                DiffMode.Working -> Compare.WorkingVsIndex
            }
            val root = resolveRepo(target.repo)
            if (root == null) {
                error = "This project isn't a git repository."
                loading = false
                return@launch
            }
            repo = root
            openable = true
            actionable = compare != Compare.Untracked
            loadRefs(root)
            reload()
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

    private suspend fun loadRefs(root: String) {
        val r = git.run(
            root,
            "for-each-ref --format=" + Git.quote("%(refname:short)") + " refs/heads refs/remotes",
            timeoutMs = 20_000L,
        )
        refs.replaceWith(
            if (!r.ok) emptyList() else r.stdout.lines().map { it.trim() }
                // origin/HEAD points at another ref in this list rather than being one.
                .filter { it.isNotEmpty() && !it.contains("/HEAD") },
        )
    }

    // --- loading ---------------------------------------------------------------------------------

    fun choose(next: Compare) {
        showPicker = false
        if (next == compare) return
        compare = next
        actionable = stashRef == null && next != Compare.Untracked
        message = null
        scope.launch {
            busy = true
            reload()
            busy = false
        }
    }

    fun expandContext() {
        if (expanded) return
        expanded = true
        scope.launch {
            busy = true
            reload()
            busy = false
        }
    }

    private suspend fun reload() {
        val root = repo ?: return
        val quoted = Git.quote(path)
        // Three lines of context is git's default and what a reader wants by default; expanding asks
        // for a number no file reaches, which is how "show me the rest of it" is spelled.
        val context = if (expanded) "-U100000" else "-U3"
        val args = when (val c = compare) {
            Compare.WorkingVsIndex -> "diff $context --no-color -- $quoted"
            Compare.IndexVsHead -> "diff --cached $context --no-color -- $quoted"
            Compare.WorkingVsHead -> "diff HEAD $context --no-color -- $quoted"
            // --no-index against /dev/null shows a file git has never seen as one big addition.
            Compare.Untracked -> "diff --no-index $context --no-color -- /dev/null $quoted"
            is Compare.Ref -> "diff " + Git.quote(c.name) + " $context --no-color -- $quoted"
        }
        // `diff --no-index` exits 1 when the files differ, which is the normal case here, so the
        // text is what matters rather than the code.
        apply(parseDiff(git.run(root, args, timeoutMs = 120_000L).output))
    }

    private fun apply(next: ParsedDiff) {
        parsed = next
        generation++
        hunks.replaceWith(next.hunks)
        added = next.added
        removed = next.removed
        items.replaceWith(flatten(next.hunks))
    }

    /**
     * Hunks, their rows, and the unchanged stretches between them, as one list.
     *
     * The gap count comes out of the hunk headers rather than another git call: the end of one hunk
     * and the start of the next name exactly how many lines nobody touched.
     */
    private fun flatten(list: List<Hunk>): List<DiffItem> {
        val out = ArrayList<DiffItem>()
        var previousEnd = 1
        list.forEachIndexed { index, hunk ->
            val gap = hunk.newStart - previousEnd
            if (gap > 0) out += DiffItem.Unchanged(Gap(gap))
            out += DiffItem.HunkStart(index, hunk)
            align(hunk).forEach { out += DiffItem.Row(it) }
            previousEnd = hunk.newStart + hunk.newCount
        }
        return out
    }

    private suspend fun loadStash(carriedRepo: String, ref: String) {
        title = ref
        stashRef = ref
        val root = resolveRepo(carriedRepo) ?: run {
            error = "This project isn't a git repository."
            return
        }
        repo = root
        openable = false
        actionable = false
        val quoted = Git.quote(ref)
        // A stash is a commit, so its subject is the name it was saved under — which is the thing
        // the user chose and can recognise. "stash@{0}" is a position in a list, useful for saying
        // which one but useless for saying which; it moves to the subtitle rather than leading.
        val subject = git.run(root, "log -1 --format=%s $quoted", timeoutMs = 20_000L)
        if (subject.ok) title = subject.stdout.trim().ifBlank { ref }
        // --include-untracked arrived in git 2.32; an older git rejects the flag rather than the
        // request, so the same command without it is the fallback, not a different answer.
        var names = git.run(root, "stash show --name-status --include-untracked $quoted")
        if (!names.ok) names = git.run(root, "stash show --name-status $quoted")
        files.replaceWith(
            names.stdout.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 2) null else StashFile(parts[0].trim().take(1), parts.last().trim())
                },
        )
        var patch = git.run(root, "stash show -p --include-untracked --no-color $quoted")
        if (!patch.ok) patch = git.run(root, "stash show -p --no-color $quoted")
        apply(parseDiff(patch.output))
    }

    // --- acting on a hunk ------------------------------------------------------------------------

    fun stage(hunk: Hunk) = applyPatch(hunk, reverse = false, extra = "--cached", okMessage = "Staged hunk.")

    fun promptRevert(hunk: Hunk) {
        confirm = Confirm(
            title = "Revert hunk",
            body = "Undo these changes in the working file? This cannot be undone.",
            action = "Revert",
        ) { applyPatch(hunk, reverse = true, extra = "", okMessage = "Reverted hunk.") }
    }

    /**
     * Hand one hunk back to git.
     *
     * Through base64 rather than a heredoc: a patch is arbitrary text by definition — it is
     * somebody's source — and every shell-quoting scheme has an input that escapes it. It is also
     * the one place whitespace has to survive byte for byte, which a shell is glad to mangle.
     */
    private fun applyPatch(hunk: Hunk, reverse: Boolean, extra: String, okMessage: String) {
        val root = repo ?: return
        if (busy) return
        scope.launch {
            busy = true
            message = null
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(parsed.patchFor(hunk).toByteArray(Charsets.UTF_8))
            val flags = listOf(extra, if (reverse) "-R" else "").filter { it.isNotEmpty() }.joinToString(" ")
            val r = host.exec(
                "printf %s " + Git.quote(encoded) + " | base64 -d | " +
                    Git.prefixFor(root) + "apply $flags --whitespace=nowarn -",
                workdir = root,
                timeoutMs = 60_000L,
            )
            messageIsError = !r.ok
            message = if (r.ok) okMessage else r.failure
            if (r.ok) reload()
            busy = false
        }
    }

    /** Open the working-tree file at [line]; the repo root plus the repo-relative path. */
    fun openAt(line: Int) {
        val root = repo ?: return
        if (!openable || path.isEmpty()) return
        host.openFile(root.trimEnd('/') + "/" + path, line.takeIf { it > 0 })
    }

    /** Where "Open file" lands: the first changed line, not the top of the file. */
    val firstChangedLine: Int
        get() = hunks.firstOrNull()?.newStart ?: 1
}
