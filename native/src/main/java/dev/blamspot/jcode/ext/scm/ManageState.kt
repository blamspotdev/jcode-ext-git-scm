package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which set of branches the manage page is showing. */
internal enum class BranchTab { Local, Remote }

/** A local branch, with where it tracks and whether it is the one checked out. */
internal data class LocalBranch(val name: String, val current: Boolean, val upstream: String)

/** One line of history. */
internal data class Commit(val hash: String, val author: String, val relative: String, val subject: String)

/**
 * Branches and history, for the page the panel's overflow opens.
 *
 * Its own state rather than the panel's: this is a separate surface with a separate lifetime, and it
 * asks git different questions. What it shares is [Git] — the same three settings on every command,
 * and the same `-C` naming the directory — so the two surfaces cannot disagree about which
 * repository they are talking to.
 */
internal class ManageState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
) {
    private val git = Git(host)

    var repo by mutableStateOf<String?>(null)
        private set
    var branch by mutableStateOf("")
        private set
    var booting by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The one-line result of the last action — "Created x", or why it failed. */
    var message by mutableStateOf<String?>(null)
    var messageIsError by mutableStateOf(false)
        private set

    var tab by mutableStateOf(BranchTab.Local)
    var newBranch by mutableStateOf("")
    var confirm by mutableStateOf<Confirm?>(null)

    val local = mutableStateListOf<LocalBranch>()
    val remote = mutableStateListOf<String>()
    val commits = mutableStateListOf<Commit>()

    // --- boot ---------------------------------------------------------------------------------

    /**
     * Find the repository for the open project.
     *
     * The same walk the panel does, and the same refusal of "/": this page opens on its own and
     * cannot see what the panel decided, so it has to ask again rather than inherit.
     */
    fun boot() {
        scope.launch {
            booting = true
            val path = host.projectInfo()?.path
            val found = path?.let {
                val top = git.run(it, "rev-parse --show-toplevel 2>/dev/null", timeoutMs = 20_000L)
                top.output.lines().lastOrNull { line -> line.isNotBlank() }?.trim()
                    ?.takeIf { root -> top.ok && root.isNotEmpty() && root != "/" }
            }
            repo = found
            booting = false
            if (found != null) refresh()
        }
    }

    private suspend fun refresh() {
        val root = repo ?: return
        branch = git.run(root, "rev-parse --abbrev-ref HEAD").stdout.trim()
        loadBranches()
        loadCommits()
    }

    private suspend fun loadBranches() {
        val root = repo ?: return
        // Every format string is quoted: it reaches git through a shell, and `%(refname:short)`
        // unquoted opens a subshell at the first bracket.
        val locals = git.run(root, "branch --format=" + Git.quote("%(refname:short)\t%(HEAD)\t%(upstream:short)"))
        local.replaceWith(
            if (!locals.ok) emptyList() else locals.stdout.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    val parts = line.split('\t')
                    LocalBranch(
                        name = parts.getOrElse(0) { "" },
                        current = parts.getOrElse(1) { "" } == "*",
                        upstream = parts.getOrElse(2) { "" },
                    )
                }
                .filter { it.name.isNotEmpty() },
        )

        val remotes = git.run(root, "branch -r --format=" + Git.quote("%(refname:short)"))
        remote.replaceWith(
            if (!remotes.ok) emptyList() else remotes.stdout.lines()
                .map { it.trim() }
                // origin/HEAD is a pointer at another branch in this list, not a branch of its own.
                .filter { it.isNotEmpty() && !it.contains("/HEAD") },
        )
    }

    private suspend fun loadCommits() {
        val root = repo ?: return
        val r = git.run(root, "log -n 100 --pretty=format:" + Git.quote("%h%x1f%an%x1f%ar%x1f%s"))
        commits.replaceWith(
            if (!r.ok) emptyList() else r.stdout.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val p = line.split(UNIT_SEPARATOR)
                    Commit(
                        hash = p.getOrElse(0) { "" },
                        author = p.getOrElse(1) { "" },
                        relative = p.getOrElse(2) { "" },
                        subject = p.getOrElse(3) { "" },
                    )
                },
        )
    }

    // --- actions ------------------------------------------------------------------------------

    /** Run one git command, say how it went, and re-read. */
    private fun run(args: String, okMessage: String, timeoutMs: Long = 60_000L) {
        val root = repo ?: return
        if (busy) return
        scope.launch {
            busy = true
            message = "Working…"
            messageIsError = false
            val r = git.run(root, args, timeoutMs)
            busy = false
            messageIsError = !r.ok
            message = if (r.ok) okMessage else r.failure
            refresh()
        }
    }

    fun fetch() = run("fetch --all --prune", "Fetched.", timeoutMs = 180_000L)

    fun create() {
        val name = newBranch.trim()
        if (name.isEmpty()) {
            messageIsError = true
            message = "Enter a branch name."
            return
        }
        newBranch = ""
        run("checkout -b " + Git.quote(name), "Created $name", timeoutMs = 120_000L)
    }

    /**
     * Switch branch, asking first when there is uncommitted work.
     *
     * git will often carry the changes across rather than refuse, which is the surprising outcome:
     * you meant to look at another branch, not to take your edits there.
     */
    fun checkout(name: String) {
        val root = repo ?: return
        scope.launch {
            val dirty = git.run(root, "status --porcelain").stdout.trim()
            if (dirty.isEmpty()) {
                run("checkout " + Git.quote(name), "Checked out $name", timeoutMs = 120_000L)
                return@launch
            }
            confirm = Confirm(
                title = "Uncommitted changes",
                body = "You have uncommitted changes. Checking out \"$name\" may fail, or carry them " +
                    "over to that branch.",
                action = "Checkout anyway",
            ) { run("checkout " + Git.quote(name), "Checked out $name", timeoutMs = 120_000L) }
        }
    }

    fun promptRename(name: String) {
        confirm = Confirm(
            title = "Rename branch",
            body = "Rename \"$name\" to:",
            action = "Rename",
            destructive = false,
            input = name,
            placeholder = "branch name",
        ) { newName ->
            if (newName.isNotEmpty() && newName != name) {
                run("branch -m " + Git.quote(name) + " " + Git.quote(newName), "Renamed to $newName")
            }
        }
    }

    fun promptDelete(name: String) {
        confirm = Confirm(
            title = "Delete branch",
            body = "Delete \"$name\"? This cannot be undone.",
            action = "Delete",
        ) { run("branch -D " + Git.quote(name), "Deleted $name") }
    }

    /** The branch part of `origin/feature`, which is what you check out locally. */
    fun localNameOf(remoteBranch: String): String = remoteBranch.substringAfter('/', remoteBranch)
}
