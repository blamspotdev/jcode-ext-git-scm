package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeDecoration
import dev.blamspot.jcode.ext.api.NativeExecResult
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How the changed-files lists are laid out. */
internal enum class ViewMode { List, Tree }

/**
 * Everything the panel knows, and every git action it can take.
 *
 * Compose state rather than a WebView's DOM, which is the point of the port: the panel's contents
 * survive a drawer switch because the composition does, with no persistent view to keep alive on
 * the side.
 */
internal class ScmState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
) {
    private val git = Git(host)

    var repos by mutableStateOf<List<RepoInfo>>(emptyList())
        private set
    var repo by mutableStateOf<RepoInfo?>(null)
        private set
    var branch by mutableStateOf("HEAD")
        private set
    var ahead by mutableStateOf(0)
        private set
    var behind by mutableStateOf(0)
        private set

    val staged = mutableStateListOf<FileEntry>()
    val unstaged = mutableStateListOf<FileEntry>()
    val conflicts = mutableStateListOf<FileEntry>()

    var busy by mutableStateOf(false)
        private set
    var booting by mutableStateOf(true)
        private set

    /** Set when the repository cannot be read at all; the panel shows this instead of empty lists. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Multi-line git output worth showing in full — a push, a failed merge, an init. */
    var log by mutableStateOf<String?>(null)

    var viewMode by mutableStateOf(ViewMode.List)
    var commitMessage by mutableStateOf("")

    val collapsedFolders = mutableStateListOf<String>()

    val canCommit: Boolean get() = staged.isNotEmpty() && commitMessage.isNotBlank() && !busy

    // --- boot ---------------------------------------------------------------------------------

    /**
     * Find the repositories in the open workspace and load the first one.
     *
     * Every project folder is asked for its own toplevel rather than assuming the project root is
     * the repository: a workspace holds several projects, and a project may sit inside a repository
     * rather than being one.
     */
    fun boot() {
        scope.launch {
            booting = true
            error = null
            val folders = host.workspaceFolders().ifEmpty {
                listOfNotNull(host.projectInfo()?.path)
            }
            val found = LinkedHashMap<String, RepoInfo>()
            for (folder in folders) {
                val top = git.run(folder, "rev-parse --show-toplevel 2>/dev/null", timeoutMs = 20_000L)
                val root = top.output.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (top.exitCode == 0 && root.isNotEmpty() && root !in found) {
                    found[root] = RepoInfo(root, Git.baseName(root))
                }
            }
            repos = found.values.toList()
            repo = repos.firstOrNull()
            booting = false
            repo?.let { active ->
                injectIgnored(active.root)
                refreshAll()
            }
        }
    }

    fun selectRepo(info: RepoInfo) {
        if (info.root == repo?.root) return
        repo = info
        commitMessage = ""
        collapsedFolders.clear()
        scope.launch {
            injectIgnored(info.root)
            refreshAll()
        }
    }

    /**
     * Feed the repo's own .gitignore to the Explorer as its injected hide list.
     *
     * Blank lines, comments and negations are skipped — a negation re-includes a path, and this list
     * only says what to hide.
     */
    private suspend fun injectIgnored(root: String) {
        val r = git.shell(root, "cat .gitignore 2>/dev/null", timeoutMs = 15_000L)
        val patterns = r.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
        host.setHiddenInjected(patterns)
    }

    // --- refresh ------------------------------------------------------------------------------

    suspend fun refreshAll() {
        refreshStatus()
        refreshStashes()
    }

    /**
     * Read the working tree.
     *
     * `-uall` lists untracked files individually rather than collapsing whole directories, so the
     * tree view and per-file staging work file by file.
     *
     * A failing status is never fed to the parser. A corrupt or unborn HEAD exits non-zero and
     * writes to stderr, and the porcelain parser would read that error text as file rows — each pair
     * of characters a status code, the rest a path. Surface it and let the user retry.
     */
    suspend fun refreshStatus() {
        val active = repo ?: return
        val r = git.run(active.root, "status --porcelain=v1 -b -uall")
        if (r.exitCode != 0) {
            branch = "HEAD"
            staged.clear()
            unstaged.clear()
            conflicts.clear()
            error = r.output.trim().ifBlank { "git status failed." }
            return
        }
        error = null
        // stdout only: a stray git warning on stderr must never land in the file lists.
        val s = parseStatus(r.stdout)
        branch = s.branch.ifBlank { "HEAD" }
        ahead = s.ahead
        behind = s.behind
        staged.replaceWith(s.staged)
        unstaged.replaceWith(s.unstaged)
        conflicts.replaceWith(s.conflicts)
        pushDecorations(active.root, s)
    }

    val stashes = mutableStateListOf<StashEntry>()

    suspend fun refreshStashes() {
        val active = repo ?: return
        val r = git.run(active.root, "stash list --format=%gd:%gs", timeoutMs = 20_000L)
        val parsed = if (r.exitCode != 0) emptyList() else r.stdout.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val i = line.indexOf(':')
                if (i > 0) StashEntry(line.substring(0, i), line.substring(i + 1).trim())
                else StashEntry(line.trim(), "")
            }
        stashes.replaceWith(parsed)
    }

    private fun pushDecorations(root: String, status: GitStatus) {
        host.setExplorerDecorations(
            root,
            decorationsFrom(status).map { (path, letter) -> NativeDecoration(path, letter) },
        )
    }

    /**
     * Re-read status a beat after the workspace changed on disk.
     *
     * Debounced because a build or a checkout emits a burst of file events, and each one would
     * otherwise start its own `git status` over the same tree.
     */
    private var decorationJob: Job? = null

    fun scheduleRefresh() {
        decorationJob?.cancel()
        decorationJob = scope.launch {
            delay(400)
            refreshStatus()
        }
    }

    // --- mutations ----------------------------------------------------------------------------

    /** Run one git command, then re-read status. Output is shown only when there is something to say. */
    private fun mutate(args: String, timeoutMs: Long = 60_000L, onDone: (suspend (NativeExecResult) -> Unit)? = null) {
        val active = repo ?: return
        if (busy) return
        scope.launch {
            busy = true
            val r = git.run(active.root, args, timeoutMs)
            busy = false
            onDone?.invoke(r)
            val text = r.output.trim()
            if (r.exitCode != 0 && text.isNotEmpty()) log = text
            refreshStatus()
        }
    }

    fun stage(entry: FileEntry) = mutate("add -- " + Git.quote(entry.path))
    fun unstage(entry: FileEntry) = mutate("restore --staged -- " + Git.quote(entry.path))
    fun stageAll() = mutate("add -A")
    fun unstageAll() = mutate("reset -q")

    /**
     * Throw a file's changes away — `clean` for a file git has never seen, `restore` otherwise.
     * Untracked and tracked are different operations, and using the wrong one silently does nothing.
     */
    fun discard(entry: FileEntry) = mutate(
        if (entry.untracked) "clean -fdq -- " + Git.quote(entry.path)
        else "restore -- " + Git.quote(entry.path),
    )

    fun commit() {
        if (!canCommit) return
        val message = commitMessage
        mutate("commit -m " + Git.quote(message)) { r ->
            // Clear the box only on success, so a rejected commit does not lose what was typed.
            if (r.exitCode == 0) commitMessage = ""
            log = r.output.trim().ifBlank { null }
        }
    }

    fun pull() = mutate("pull --ff-only", timeoutMs = 180_000L) { r -> log = r.output.trim().ifBlank { null } }
    fun push() = mutate("push", timeoutMs = 180_000L) { r -> log = r.output.trim().ifBlank { null } }

    fun openFile(entry: FileEntry) {
        val root = repo?.root ?: return
        host.openFile("$root/${entry.path}")
    }

    fun toggleFolder(key: String) {
        if (!collapsedFolders.remove(key)) collapsedFolders.add(key)
    }
}

private fun <T> androidx.compose.runtime.snapshots.SnapshotStateList<T>.replaceWith(items: List<T>) {
    clear()
    addAll(items)
}
