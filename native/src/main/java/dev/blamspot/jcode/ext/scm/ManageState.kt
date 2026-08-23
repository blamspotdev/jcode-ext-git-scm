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

/**
 * A local branch, with where it tracks, whether it is checked out, and how far it has drifted.
 *
 * [incoming] and [outgoing] are commits its upstream has that it does not, and the reverse. A branch
 * with no upstream has neither, which is not the same as being level with one.
 */
internal data class LocalBranch(
    val name: String,
    val current: Boolean,
    val upstream: String,
    val incoming: Int = 0,
    val outgoing: Int = 0,
)

/** A branch on a remote, measured against the branch that is checked out. */
internal data class RemoteBranch(val name: String, val incoming: Int = 0, val outgoing: Int = 0)

/** Where a commit sits relative to what the branch tracks. */
internal enum class CommitGroup {
    /** On the upstream and not here yet. */
    Incoming,

    /** Here and not on the upstream yet. */
    Outgoing,

    /** On both — the history the two agree about. */
    Shared,
}

/** One line of history. */
internal data class Commit(
    val hash: String,
    val author: String,
    val relative: String,
    val subject: String,
    val group: CommitGroup = CommitGroup.Shared,
)

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
    var confirm by mutableStateOf<Confirm?>(null)

    /**
     * Which branch the history card is showing. Empty means the one checked out.
     *
     * The card is the only history on the page, so pointing it at another branch is the answer to
     * "what does that branch carry" — rather than a second list somewhere else saying the same kind
     * of thing in a different shape.
     */
    var historyBranch by mutableStateOf("")
        private set

    /** Why the history could not be read — a branch deleted out from under the card, usually. */
    var historyError by mutableStateOf<String?>(null)
        private set

    /** Bumped when the history card should be scrolled into view. */
    var historyRequest by mutableStateOf(0)
        private set

    val local = mutableStateListOf<LocalBranch>()
    val remote = mutableStateListOf<RemoteBranch>()
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
        // `%(upstream:track)` carries the drift, so the whole list still costs one command.
        val locals = git.run(
            root,
            "branch --format=" + Git.quote("%(refname:short)\t%(HEAD)\t%(upstream:short)\t%(upstream:track)"),
        )
        local.replaceWith(
            if (!locals.ok) emptyList() else locals.stdout.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    val parts = line.split('\t')
                    val track = parts.getOrElse(3) { "" }
                    LocalBranch(
                        name = parts.getOrElse(0) { "" },
                        current = parts.getOrElse(1) { "" } == "*",
                        upstream = parts.getOrElse(2) { "" },
                        incoming = count(track, "behind"),
                        outgoing = count(track, "ahead"),
                    )
                }
                .filter { it.name.isNotEmpty() },
        )

        val remotes = git.run(root, "branch -r --format=" + Git.quote("%(refname:short)"))
        val names = if (!remotes.ok) emptyList() else remotes.stdout.lines()
            .map { it.trim() }
            // origin/HEAD points at another ref in this list rather than being one.
            .filter { it.isNotEmpty() && !it.contains("/HEAD") }
        remote.replaceWith(measureRemotes(root, names))
    }

    /**
     * How far each remote branch is from the one checked out.
     *
     * A remote ref has no upstream of its own, so there is nothing to read the drift off — it has to
     * be measured, one `rev-list` per row. They go in a single shell loop rather than one exec
     * apiece, and the count is capped, so a repository with a long remote list cannot stall the
     * page. Rows past the cap still appear; they just carry no counts.
     */
    private suspend fun measureRemotes(root: String, names: List<String>): List<RemoteBranch> {
        if (names.isEmpty()) return emptyList()
        val measured = names.take(REMOTE_MEASURE_LIMIT)
        val g = Git.prefixFor(root)
        val loop = "for r in " + measured.joinToString(" ") { Git.quote(it) } + "; do " +
            "printf '%s\t' \"\$r\"; " + g + "rev-list --left-right --count \"HEAD...\$r\" 2>/dev/null; " +
            "printf '\n'; done"
        val r = git.shell(root, loop, timeoutMs = 60_000L)
        // `--left-right --count A...B` prints what only A has, then what only B has. From HEAD's
        // side that is what we would send, then what we would receive.
        val drift = HashMap<String, Pair<Int, Int>>()
        if (r.ok) {
            r.stdout.lines().forEach { line ->
                val parts = line.split('\t')
                if (parts.size >= 3) {
                    drift[parts[0]] = (parts[2].trim().toIntOrNull() ?: 0) to (parts[1].trim().toIntOrNull() ?: 0)
                }
            }
        }
        return names.map { name ->
            val d = drift[name]
            RemoteBranch(name, d?.first ?: 0, d?.second ?: 0)
        }
    }

    /**
     * Fill the history card.
     *
     * The whole history of the branch you are on, or the last few of one you asked about — the
     * limit follows the subject, so the card never claims to be a full history of a branch it only
     * sampled.
     */
    /**
     * Fill the history card, sorted by which side of the upstream each commit is on.
     *
     * "How far ahead am I" is answered by a number in the branch row; "which commits are those" the
     * history has to answer, and a flat list cannot. So the log is asked three things — what the
     * upstream has and we do not, what we have and it does not, and what both agree about from the
     * merge base back — and the card shows them in that order.
     *
     * A branch with no upstream has no sides, so it gets one plain log.
     */
    private suspend fun loadCommits() {
        val root = repo ?: return
        val target = historyBranch
        val ref = target.ifEmpty { "HEAD" }
        val limit = if (target.isEmpty()) FULL_HISTORY else RECENT_COMMITS
        // Resolved here rather than in the shell: a `$VAR` would have to be quoted against branch
        // names with spaces in them, and every quoting scheme has an input that escapes it.
        val upstream = git.run(root, "rev-parse --abbrev-ref " + Git.quote(ref + "@{upstream}") + " 2>/dev/null")
            .let { if (it.ok) it.stdout.trim() else "" }
        val base = if (upstream.isEmpty()) "" else {
            git.run(root, "merge-base " + Git.quote(ref) + " " + Git.quote(upstream) + " 2>/dev/null")
                .let { if (it.ok) it.stdout.trim() else "" }
        }
        val r = git.shell(root, historyScript(root, ref, upstream, base, limit), timeoutMs = 60_000L)
        historyError = if (r.ok) null else r.failure
        commits.replaceWith(if (r.ok) parseCommits(r.stdout) else emptyList())
    }

    /**
     * The logs, in one command.
     *
     * Each line carries the side it came from as its first field. Three separate execs would be
     * three round trips through proot to draw one card.
     */
    private fun historyScript(root: String, ref: String, upstream: String, base: String, limit: Int): String {
        val g = Git.prefixFor(root)
        val here = Git.quote(ref)
        // `--pretty=format:` omits the trailing newline, so each log needs one after it or the next
        // log's first line arrives glued to this one's last.
        fun log(marker: String, args: String) =
            g + "log -n " + limit + " --pretty=format:" + Git.quote(marker + "%x1f" + LOG_FORMAT) + " " + args + "; echo"
        if (upstream.isEmpty()) return log("=", here)
        val there = Git.quote(upstream)
        val shared = if (base.isEmpty()) "" else "; " + log("=", Git.quote(base))
        return log(">", there + " --not " + here) + "; " + log("<", here + " --not " + there) + shared
    }


    /**
     * Point the history card at [name] and bring it into view.
     *
     * Deciding whether to check a branch out or merge it means knowing what it carries, and the
     * card is already on the page — so it answers, rather than a dialog opening over the list the
     * question was asked from.
     */
    fun showRecent(name: String) {
        if (repo == null) return
        historyBranch = name
        historyRequest++
        scope.launch { loadCommits() }
    }

    /** Point it back at the branch you are on. */
    fun showCurrentHistory() {
        if (historyBranch.isEmpty()) return
        historyBranch = ""
        scope.launch { loadCommits() }
    }

    private fun parseCommits(text: String): List<Commit> = text.lines()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val p = line.split(UNIT_SEPARATOR)
            if (p.size < 5) return@mapNotNull null
            Commit(
                hash = p[1],
                author = p[2],
                relative = p[3],
                subject = p[4],
                group = when (p[0]) {
                    ">" -> CommitGroup.Incoming
                    "<" -> CommitGroup.Outgoing
                    else -> CommitGroup.Shared
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

    /**
     * Ask for a name, then branch and switch to it.
     *
     * A prompt rather than a field kept open above the list: creating a branch is occasional, and a
     * permanently-parked input spent a row of the card on it every time the page was opened to read
     * the list instead.
     */
    fun promptCreate() {
        confirm = Confirm(
            title = "New branch",
            body = "Branch from $branch and switch to it.",
            action = "Create",
            destructive = false,
            input = "",
            placeholder = "new-branch-name",
        ) { name ->
            if (name.isNotEmpty()) {
                run("checkout -b " + Git.quote(name), "Created $name", timeoutMs = 120_000L)
            }
        }
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

    /**
     * Merge [name] into the branch that is checked out.
     *
     * Asked first, always. It rewrites the current branch, and on a touch device a menu tap is easy
     * to make by accident. A merge that conflicts surfaces in the panel's "Merge Changes" like any
     * other, so nothing here has to handle that case.
     */
    fun promptMerge(name: String) {
        confirm = Confirm(
            title = "Merge branch",
            body = "Merge \"$name\" into $branch?",
            action = "Merge",
            destructive = false,
        ) { run("merge " + Git.quote(name), "Merged $name into $branch", timeoutMs = 120_000L) }
    }

    /**
     * Send a branch to its remote without checking it out.
     *
     * `push <remote> <name>` names both ends, so it does not depend on which branch happens to be
     * current. A branch with no upstream gets one, because a push that leaves the branch still
     * untracked would have to be repeated by hand next time.
     */
    fun push(b: LocalBranch) {
        val remote = b.upstream.substringBefore('/', "").ifEmpty { "origin" }
        val args = if (b.upstream.isEmpty()) {
            "push -u " + Git.quote(remote) + " " + Git.quote(b.name)
        } else {
            "push " + Git.quote(remote) + " " + Git.quote(b.name)
        }
        run(args, "Pushed ${b.name}", timeoutMs = 180_000L)
    }

    /**
     * Bring a branch up to date with what it tracks.
     *
     * The current branch takes a plain pull. Any other one is fast-forwarded straight from its
     * upstream with `fetch <remote> <src>:<dst>`, which needs no working-tree switch and refuses
     * rather than merging when it is not a fast-forward — which is the safe outcome for a branch
     * you are not looking at. A branch with no upstream has nothing to pull from, so the menu does
     * not offer this at all.
     */
    fun pull(b: LocalBranch) {
        if (b.current) {
            run("pull --ff-only", "Pulled ${b.name}", timeoutMs = 180_000L)
            return
        }
        val remote = b.upstream.substringBefore('/', "").ifEmpty { return }
        val source = b.upstream.substringAfter('/', "").ifEmpty { return }
        run(
            "fetch " + Git.quote(remote) + " " + Git.quote("$source:${b.name}"),
            "Updated ${b.name}",
            timeoutMs = 180_000L,
        )
    }

    /** The branch part of `origin/feature`, which is what you check out locally. */
    fun localNameOf(remoteBranch: String): String = remoteBranch.substringAfter('/', remoteBranch)
}

/** The number after a word in `%(upstream:track)` — "behind" in "[ahead 1, behind 2]". */
private fun count(track: String, word: String): Int =
    Regex(word + " (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0

/** Enough rows that a normal repository is fully measured, few enough that a huge one is not slow. */
private const val REMOTE_MEASURE_LIMIT = 40

/** What a history line is made of, shared by the page's list and a branch's preview. */
private const val LOG_FORMAT = "%h%x1f%an%x1f%ar%x1f%s"

/** How many commits another branch's sample shows — and says it will show. */
internal const val RECENT_COMMITS = 10

/** How far back the current branch's own history goes. */
private const val FULL_HISTORY = 100
