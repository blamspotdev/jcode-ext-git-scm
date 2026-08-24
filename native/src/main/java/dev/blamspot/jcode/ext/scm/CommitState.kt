package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the commit is being compared against.
 *
 * Two different questions, and both come up when deciding whether to merge a branch: what the commit
 * itself did, and what still separates it from where you are.
 */
internal enum class CommitCompare {
    /** Against its parent — the change the commit introduced. */
    Parent,

    /** Against the working tree — everything that has happened since, committed or not. */
    Current,
    ;

    val label: String get() = if (this == Parent) "vs previous" else "vs current"
}

/** One file the comparison touched, and its patch once someone has asked for it. */
internal data class CommitFile(val status: String, val path: String)

/**
 * One commit, its files, and the patch for whichever of them you opened.
 *
 * The patches are fetched a file at a time rather than up front. The commit that scaffolds a project
 * touches every file in it, and rendering all of those to show the one you came for costs the whole
 * page — so opening this costs a single `--name-status`, and a diff is read only when a row is
 * opened.
 */
internal class CommitState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
    view: String,
) {
    private val git = Git(host)
    private val target = parseRefView(view, "commit")
    private val hash: String = target?.second.orEmpty()
    private val carriedRepo: String = target?.first.orEmpty()
    private var repo: String? = null

    var subject by mutableStateOf("")
        private set
    var author by mutableStateOf("")
        private set
    var relative by mutableStateOf("")
        private set
    var shortHash by mutableStateOf("")
        private set

    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var compare by mutableStateOf(CommitCompare.Parent)
        private set
    var wrap by mutableStateOf(false)
    var layout by mutableStateOf(DiffLayout.Split)

    val files = mutableStateListOf<CommitFile>()

    /** Which rows are open. A set rather than one index: two files side by side is the point. */
    val opened = mutableStateListOf<String>()

    /** Flat paths, or folders you can fold away — the same choice the drawer panel offers. */
    var tree by mutableStateOf(false)

    /** Folders currently folded, by path. */
    val collapsedFolders = mutableStateListOf<String>()

    fun toggleFolder(key: String) {
        if (!collapsedFolders.remove(key)) collapsedFolders.add(key)
    }

    /** Parsed patches, keyed by path, per comparison — switching sides asks a different question. */
    private val patches = mutableStateMapOf<String, List<AlignedRow>>()
    private val pending = mutableStateListOf<String>()

    fun rowsFor(path: String): List<AlignedRow>? = patches[key(path)]
    fun isLoading(path: String): Boolean = key(path) in pending

    fun boot() {
        scope.launch {
            val root = resolveRepo()
            if (root == null) {
                error = "This project isn't a git repository."
                loading = false
                return@launch
            }
            if (hash.isEmpty()) {
                error = "Couldn't read which commit to show."
                loading = false
                return@launch
            }
            repo = root
            val meta = git.run(
                root,
                "show -s --format=" + Git.quote("%h%x1f%an%x1f%ar%x1f%s") + " " + Git.quote(hash),
                timeoutMs = 20_000L,
            )
            if (meta.ok) {
                val p = meta.stdout.trim().split(UNIT_SEPARATOR)
                shortHash = p.getOrElse(0) { hash.take(7) }
                author = p.getOrElse(1) { "" }
                relative = p.getOrElse(2) { "" }
                subject = p.getOrElse(3) { "" }
            } else {
                shortHash = hash.take(7)
            }
            loadFiles()
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

    fun choose(next: CommitCompare) {
        if (next == compare) return
        compare = next
        // Both the list and every open patch are answers to the old question; drop them rather than
        // leave a file list from one comparison over patches from the other.
        opened.clear()
        scope.launch { loadFiles() }
    }

    private suspend fun loadFiles() {
        val root = repo ?: return
        val r = when (compare) {
            // `show` rather than `diff <hash>^ <hash>`: a root commit has no parent, and show
            // reports its files as additions instead of failing on the missing ref.
            CommitCompare.Parent -> git.run(root, "show --name-status --format= " + Git.quote(hash))
            CommitCompare.Current -> git.run(root, "diff --name-status " + Git.quote(hash))
        }
        error = if (r.ok) null else r.failure
        files.replaceWith(
            if (!r.ok) emptyList() else r.stdout.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 2) null else CommitFile(parts[0].trim().take(1), parts.last().trim())
                },
        )
    }

    /** Open a file's patch, or close it. The first open is what fetches it. */
    fun toggle(path: String) {
        if (path in opened) {
            opened.remove(path)
            return
        }
        opened.add(path)
        if (patches.containsKey(key(path)) || key(path) in pending) return
        val root = repo ?: return
        val cacheKey = key(path)
        pending.add(cacheKey)
        scope.launch {
            val quoted = Git.quote(path)
            val r = when (compare) {
                CommitCompare.Parent ->
                    git.run(root, "show --format= --no-color " + Git.quote(hash) + " -- " + quoted)
                CommitCompare.Current ->
                    git.run(root, "diff --no-color " + Git.quote(hash) + " -- " + quoted)
            }
            pending.remove(cacheKey)
            patches[cacheKey] = if (!r.ok) emptyList() else parseDiff(r.output).hunks.flatMap { align(it) }
        }
    }

    /** Keyed by comparison as well as path: the same file has a different patch on each side. */
    private fun key(path: String) = compare.name + ":" + path
}
