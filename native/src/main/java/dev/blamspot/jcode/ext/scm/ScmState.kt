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

/** What a commit does after it commits. */
internal enum class CommitVariant { Plain, Amend, Push, Sync }

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

    /** The open project's guest path — where a repository would be created if there isn't one. */
    var projectPath by mutableStateOf<String?>(null)
        private set

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
        private set

    /** True once the user has worked the tree/list toggle, after which the setting stops applying. */
    private var viewChosen = false

    fun toggleViewMode() {
        viewChosen = true
        viewMode = if (viewMode == ViewMode.Tree) ViewMode.List else ViewMode.Tree
    }

    var commitMessage by mutableStateOf("")

    val collapsedFolders = mutableStateListOf<String>()

    /** Section headers the user has folded away, by title. */
    val collapsedSections = mutableStateListOf<String>()

    /** Local branches, read when the branch menu is opened rather than on every status. */
    val branches = mutableStateListOf<String>()

    /** A destructive action waiting on a yes — discard-all and the stash operations. */
    var confirm by mutableStateOf<Confirm?>(null)

    /**
     * Shown when git refuses a commit for want of a `user.name`/`user.email`.
     *
     * A fresh runtime has no identity, and the failure git prints for it is a wall of advice ending
     * in two config commands. Asking for the two fields where the commit was attempted is the whole
     * of that advice, minus the wall.
     */
    var needsIdentity by mutableStateOf(false)

    /** Whether the commit box offers to draft a message — off unless the user opted in. */
    var generateEnabled by mutableStateOf(false)
        private set

    /** Set while an agent CLI is drafting a commit message; that runs long and outside [busy]. */
    var generating by mutableStateOf(false)
        private set

    /** What the panel is asking about before it does something irreversible. */
    data class Confirm(val title: String, val body: String, val action: String, val onConfirm: () -> Unit)

    val canCommit: Boolean get() = staged.isNotEmpty() && commitMessage.isNotBlank() && !busy

    // --- settings -----------------------------------------------------------------------------

    private var settings: Map<String, String> = emptyMap()

    /**
     * Re-read this extension's settings.
     *
     * The default view is applied only while the user has not touched the toggle themselves: a
     * setting called "default" that overrode a live choice on every settings change would be a
     * setting that fought the panel.
     */
    fun loadSettings(applyDefaultView: Boolean = false) {
        scope.launch {
            settings = host.config()
            generateEnabled = settings["scm.commitMsg.enabled"] == "true"
            if (applyDefaultView) {
                viewMode = if (settings["scm.defaultView"] == "tree") ViewMode.Tree else ViewMode.List
            }
        }
    }

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
            settings = host.config()
            generateEnabled = settings["scm.commitMsg.enabled"] == "true"
            if (!viewChosen) {
                viewMode = if (settings["scm.defaultView"] == "tree") ViewMode.Tree else ViewMode.List
            }
            projectPath = host.projectInfo()?.path
            val folders = host.workspaceFolders().ifEmpty { listOfNotNull(projectPath) }
            val found = LinkedHashMap<String, RepoInfo>()
            for (folder in folders) {
                val top = git.run(folder, "rev-parse --show-toplevel 2>/dev/null", timeoutMs = 20_000L)
                val root = top.output.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
                // "/" is refused rather than merely improbable: the walk upward ends at the
                // filesystem root, so a stray .git anywhere near it makes every project in the
                // workspace look like one enormous repository whose status never finishes.
                if (top.ok && root.isNotEmpty() && root != "/" && root !in found) {
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

    /**
     * Create a repository in the open project.
     *
     * Offered from the empty state because telling someone to go and run `git init` is the panel
     * declining to do the one thing it is for. Re-detects afterwards rather than assuming success:
     * `init` can fail on a read-only or missing directory, and the output says why.
     */
    fun initRepo() {
        val path = projectPath ?: return
        if (busy) return
        scope.launch {
            busy = true
            val r = git.run(path, "init", timeoutMs = 60_000L)
            busy = false
            if (!r.ok) {
                log = r.failure
                return@launch
            }
            boot()
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
        if (!r.ok) {
            branch = "HEAD"
            staged.clear()
            unstaged.clear()
            conflicts.clear()
            error = r.failure
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
        val r = git.run(active.root, "stash list --format=" + Git.quote("%gd:%gs"), timeoutMs = 20_000L)
        val parsed = if (!r.ok) emptyList() else r.stdout.lines()
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

    /**
     * Run one git command, then re-read status.
     *
     * A failure is always said out loud, even the silent kind. Git usually explains itself on stderr,
     * but when the workbench cannot run the command at all there is no stdout, no stderr and no exit
     * code — only [NativeExecResult.error] — and a panel that read that as "nothing to report" told
     * the user their changes were stashed when nothing had happened at all.
     */
    private fun mutate(args: String, timeoutMs: Long = 60_000L, onDone: (suspend (NativeExecResult) -> Unit)? = null) {
        val active = repo ?: return
        if (busy) return
        scope.launch {
            busy = true
            val r = git.run(active.root, args, timeoutMs)
            busy = false
            onDone?.invoke(r)
            if (!r.ok) log = r.failure
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

    fun pull() = mutate("pull --ff-only", timeoutMs = 180_000L) { r -> if (r.ok) log = r.output.trim().ifBlank { null } }
    fun push() = mutate("push", timeoutMs = 180_000L) { r -> if (r.ok) log = r.output.trim().ifBlank { null } }

    /**
     * Update the remote-tracking refs without touching the working tree — what ahead/behind reads.
     *
     * Also the panel's refresh, which is why it takes the stashes with it. [mutate] re-reads the
     * status on its way back, so between them this button leaves nothing on screen stale except
     * which repositories exist, and that only changes when the workspace does.
     */
    fun fetch() = mutate("fetch --all --prune", timeoutMs = 180_000L) { r ->
        if (r.ok) log = r.output.trim().ifBlank { null }
        refreshStashes()
    }

    /**
     * Commit, then optionally carry on.
     *
     * Amend is the one variant that does not need a message: it reuses the previous commit's.
     */
    fun commitVariant(variant: CommitVariant) {
        val active = repo ?: return
        if (busy) return
        if (variant != CommitVariant.Amend && commitMessage.isBlank()) return
        scope.launch {
            busy = true
            val args = when (variant) {
                CommitVariant.Amend ->
                    if (commitMessage.isBlank()) "commit --amend --no-edit"
                    else "commit --amend -m " + Git.quote(commitMessage)
                else -> "commit -m " + Git.quote(commitMessage)
            }
            var r = git.run(active.root, args, timeoutMs = 120_000L)
            val parts = mutableListOf(if (r.ok) r.output.trim() else r.failure)
            if (r.ok) {
                commitMessage = ""
                if (variant == CommitVariant.Sync) {
                    r = git.run(active.root, "pull --ff-only", timeoutMs = 180_000L)
                    parts += if (r.ok) r.output.trim() else r.failure
                }
                if (variant == CommitVariant.Push || variant == CommitVariant.Sync) {
                    r = git.run(active.root, "push", timeoutMs = 180_000L)
                    parts += if (r.ok) r.output.trim() else r.failure
                }
            }
            busy = false
            val text = parts.filter { it.isNotEmpty() }.joinToString("\n\n")
            // Git's "please tell me who you are" is a setup step, not an error to read: swap the
            // wall of text for the two fields that end it.
            needsIdentity = IDENTITY_ERROR.containsMatchIn(text)
            log = if (needsIdentity) null else text.ifBlank { null }
            refreshStatus()
        }
    }

    /** Write the global git identity, so the next commit has an author. */
    fun saveIdentity(name: String, email: String) {
        val n = name.trim()
        val e = email.trim()
        if (n.isEmpty() || e.isEmpty() || busy) return
        scope.launch {
            busy = true
            // No repository: an identity is global, and asking git to enter one first is a
            // reason for this to fail that has nothing to do with what it is setting.
            val r = git.run(
                null,
                "config --global user.name " + Git.quote(n) +
                    " && " + Git.prefixFor(null) + "config --global user.email " + Git.quote(e),
            )
            busy = false
            if (r.ok) {
                needsIdentity = false
                log = "Identity saved — commit again."
            } else {
                log = r.failure
            }
        }
    }

    /**
     * Throw away every change in the working tree.
     *
     * Two commands, not one: `restore` returns tracked files, `clean` removes untracked ones, and
     * doing only the first leaves the mess the user asked to be rid of.
     */
    fun discardAll() {
        val count = unstaged.size
        confirm = Confirm(
            title = "Discard all changes",
            body = "Discard " + count + " change" + (if (count == 1) "" else "s") +
                " in the working tree, including untracked files? This cannot be undone.",
            action = "Discard all",
        ) {
            mutate("restore -- . 2>/dev/null; " + Git.prefixFor(repo?.root) + "clean -fdq")
        }
    }

    /**
     * Draft a commit message from the diff with an agent CLI.
     *
     * The staged diff when there is one — that is what a commit would contain — otherwise the whole
     * working tree, with untracked files rendered as additions against /dev/null so a brand-new file
     * is described by what is in it rather than by its name alone.
     */
    fun generateCommitMessage() {
        val active = repo ?: return
        if (generating || busy) return
        if (staged.isEmpty() && unstaged.isEmpty()) {
            log = "No changes to describe — make or stage some changes first."
            return
        }
        val tool = settings["scm.commitMsg.tool"].orEmpty().ifBlank { "claude" }
        val model = settings["scm.commitMsg.model"].orEmpty().trim()
        val custom = settings["scm.commitMsg.customCommand"].orEmpty().trim()
        if (tool == "custom" && custom.isEmpty()) {
            log = "Set a command in Settings → Source Control → \"Generate commit message · custom command\"."
            return
        }
        val instruction = (
            if (settings["scm.commitMsg.detail"] == "detailed") {
                "Write a git commit message: a concise imperative subject line of about 50 characters, " +
                    "then a blank line, then a short body of bullet points saying what changed and why."
            } else {
                "Write a single-line git commit message: one concise imperative subject of about 50 " +
                    "characters, no body."
            }
            ) + " Base it only on the diff piped to your stdin. Output ONLY the commit message text — " +
            "no code fences, no quotes, no preamble."

        val modelArg = if (model.isNotEmpty()) " --model " + Git.quote(model) else ""
        val toolCmd = when (tool) {
            "custom" -> custom
            "opencode" -> "opencode run" + modelArg + " " + Git.quote(instruction)
            else -> "claude -p" + modelArg + " " + Git.quote(instruction)
        }
        val g = Git.prefixFor(active.root)
        val collect = "S=\"\$(" + g + "diff --cached)\"; if [ -n \"\$S\" ]; then printf '%s\\n' \"\$S\"; " +
            "else " + g + "diff; " + g + "ls-files --others --exclude-standard -z | " +
            "xargs -0 -r -I {} " + g + "diff --no-index --no-color -- /dev/null {} 2>/dev/null; fi"

        scope.launch {
            generating = true
            val r = git.shell(active.root, "{ " + collect + " ; } | " + toolCmd, timeoutMs = 180_000L)
            generating = false
            val raw = r.output
            if (!r.ok || raw.isBlank()) {
                log = raw.trim().ifBlank {
                    "Could not run \"" + tool + "\". Is it installed and signed in inside the runtime?"
                }
                return@launch
            }
            val message = raw.replace(FENCE_OPEN, "").replace(FENCE_CLOSE, "").trim()
            if (message.isEmpty()) log = "The agent returned an empty message." else commitMessage = message
        }
    }

    // --- the extension's own pages --------------------------------------------------------------
    //
    // Drawn by the web build still: the panel is native, the diff, merge, stash, sign-in and manage
    // pages are pages of a WebView. Handing off by view id keeps them reachable from here.

    /**
     * Open a file's diff, or its merge editor when it is conflicted.
     *
     * The repo root travels in the view id: the page opens on its own and cannot see this panel's
     * idea of which repository is active, and re-guessing it is wrong in a multi-repo workspace.
     */
    fun openDiff(entry: FileEntry, staged: Boolean) {
        val root = repo?.root ?: return
        if (entry.code == "!") {
            host.openView("merge:" + encodeUri(root) + ":" + encodeUri(entry.path))
            return
        }
        val mode = if (staged) "s" else if (entry.untracked) "u" else "w"
        host.openView("diff:" + mode + ":" + encodeUri(root) + ":" + encodeUri(entry.path))
    }

    fun openStash(entry: StashEntry) {
        val root = repo?.root ?: return
        host.openView("stash:" + encodeUri(root) + ":" + encodeUri(entry.ref))
    }

    fun openGitHub() = host.openView("github")
    fun openManage() = host.openView("manage")

    // --- stash ---------------------------------------------------------------------------------

    fun stashPush() {
        confirm = Confirm(
            title = "Stash changes",
            body = "Save all working-tree changes — staged, unstaged and untracked — to a new stash.",
            action = "Stash",
        ) {
            val message = commitMessage.trim()
            mutate(
                "stash push --include-untracked" + if (message.isNotEmpty()) " -m " + Git.quote(message) else "",
            ) { r ->
                if (r.ok) commitMessage = ""
                // Only claim it worked when it worked: mutate reports the failure itself.
                if (r.ok) log = r.output.trim().ifBlank { "Changes stashed." }
                refreshStashes()
            }
        }
    }

    fun stashApply(entry: StashEntry) = confirmStash(
        "Apply stash", "Apply " + entry.ref + " onto the working tree? It stays in the stash list.", "Apply",
        "stash apply " + Git.quote(entry.ref),
    )

    fun stashPop(entry: StashEntry) = confirmStash(
        "Pop stash", "Apply " + entry.ref + " onto the working tree and remove it from the list?", "Pop",
        "stash pop " + Git.quote(entry.ref),
    )

    fun stashDrop(entry: StashEntry) = confirmStash(
        "Drop stash", "Delete " + entry.ref + "? Its changes are lost.", "Drop",
        "stash drop " + Git.quote(entry.ref),
    )

    /** Restore the newest stash, the one action worth reaching without opening the list. */
    fun stashPopLatest() {
        val latest = stashes.firstOrNull() ?: return
        stashPop(latest)
    }

    private fun confirmStash(title: String, body: String, action: String, args: String) {
        confirm = Confirm(title, body, action) {
            mutate(args) { r ->
                if (r.ok) log = r.output.trim().ifBlank { null }
                refreshStashes()
            }
        }
    }

    // --- branches ------------------------------------------------------------------------------

    /**
     * Read the local branches, for the branch menu.
     *
     * The format string is quoted because it goes through a shell, and `%(refname:short)` unquoted
     * opens a subshell at the first bracket — the command dies of a syntax error and the menu shows
     * no branches at all, which looks exactly like a repository that has none.
     */
    fun loadBranches() {
        val active = repo ?: return
        scope.launch {
            val r = git.run(active.root, "branch --format=" + Git.quote("%(refname:short)"), timeoutMs = 20_000L)
            branches.replaceWith(
                if (!r.ok) emptyList()
                else r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
    }

    fun switchBranch(name: String) = mutate("checkout " + Git.quote(name), timeoutMs = 120_000L)

    fun createBranch(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        mutate("checkout -b " + Git.quote(clean), timeoutMs = 120_000L)
    }

    fun toggleSection(title: String) {
        if (!collapsedSections.remove(title)) collapsedSections.add(title)
    }

    fun openFile(entry: FileEntry) {
        val root = repo?.root ?: return
        host.openFile("$root/${entry.path}")
    }

    fun toggleFolder(key: String) {
        if (!collapsedFolders.remove(key)) collapsedFolders.add(key)
    }
}

/** What git says when it has no author to attribute a commit to. */
private val IDENTITY_ERROR = Regex("who you are|user\\.email|empty ident|Author identity", RegexOption.IGNORE_CASE)

private val FENCE_OPEN = Regex("^\\s*```[a-z]*\\s*\\n?", RegexOption.IGNORE_CASE)
private val FENCE_CLOSE = Regex("\\n?```\\s*$")

/**
 * Percent-encode one segment of a view id, the way `encodeURIComponent` does.
 *
 * The pages that read these ids are the web build's and decode them in JavaScript, so the encoding
 * has to be JavaScript's — `URLEncoder` would write a space as `+` and the path would not survive.
 */
private fun encodeUri(value: String): String = buildString {
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && b.toInt() in 0..127 || c in "-_.!~*'()") {
            append(c)
        } else {
            append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
                .append("0123456789ABCDEF"[b.toInt() and 0xF])
        }
    }
}

private fun <T> androidx.compose.runtime.snapshots.SnapshotStateList<T>.replaceWith(items: List<T>) {
    clear()
    addAll(items)
}
