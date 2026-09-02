package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray

/** One of the user's GitHub repositories, as the API describes it. */
internal data class RemoteRepo(
    val name: String,
    val owner: String,
    val cloneUrl: String,
    val description: String,
    val private: Boolean,
)

/** Which of the two screens this page is showing. */
internal enum class CloneScreen { Form, Remote }

/**
 * Cloning a repository, and browsing the ones you already have on GitHub.
 *
 * One state for both because they are one flow with two ways in: the browser exists to fill in the
 * form. Tapping a repository does not clone it — it hands the form a URL and a name for you to
 * confirm, which is why the two screens share a state rather than being two pages that call
 * each other.
 */
internal class CloneState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
    /** The view this page was opened as, so it can close its own tab when the clone is adopted. */
    private val view: String,
    startOn: CloneScreen,
) {
    var screen by mutableStateOf(startOn)
        private set

    /** True when the browser was the way in, so the form offers a way back to the list. */
    private val browsable = startOn == CloneScreen.Remote

    // --- the form -------------------------------------------------------------------------------

    var url by mutableStateOf("")
        private set
    var name by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    var log by mutableStateOf<String?>(null)
        private set
    var intent by mutableStateOf<String?>(null)

    /** The last six commits on the URL in the box, when it names something that answers. */
    val peek = mutableStateListOf<Commit>()
    var peeking by mutableStateOf(false)
        private set

    /**
     * Whether the host binds a staging directory.
     *
     * Cached only once it is a definite yes or no: a transient failure while the runtime is still
     * booting must not permanently route a staging-capable host down the legacy path.
     */
    private var staging: Boolean? = null

    /** Where the clone will land, spelled out under the fields. */
    var destination by mutableStateOf("")
        private set

    /**
     * The last name this page filled in by itself.
     *
     * The field is auto-owned only while it is empty or still holds this — a name the user typed is
     * never overwritten by a later edit to the URL.
     */
    private var autoName = ""

    fun boot() {
        scope.launch {
            probeSources()
            // Nothing legitimate lives in /sources between clones (adoption happens the moment one
            // finishes), so leftovers from an interrupted session are swept rather than surfaced.
            if (staging == true) host.exec("find $SOURCES -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null")
            updateDestination()
            if (screen == CloneScreen.Remote) loadRepos()
        }
    }

    private suspend fun probeSources(): Boolean {
        staging?.let { return it }
        val answer = host.exec("test -d $SOURCES && echo yes || echo no").stdout.trim()
        if (answer == "yes" || answer == "no") staging = answer == "yes"
        return staging ?: false
    }

    fun editUrl(value: String) {
        url = value
        val current = name.trim()
        if (current.isEmpty() || current == autoName) {
            autoName = if (WEB_URL.matches(value.trim())) repoNameOf(value.trim()) else ""
            name = autoName
        }
        updateDestination()
    }

    fun editName(value: String) {
        name = value
        updateDestination()
    }

    private fun updateDestination() {
        val folder = name.trim().takeIf { it.isNotEmpty() }?.let(::sanitizeName) ?: "…"
        destination = "Clones into " + (if (staging == false) WORKSPACE else SOURCES) + "/" + folder
    }

    /**
     * Show what is at the far end of the URL before committing to downloading it.
     *
     * A bare, depth-6, tree-filtered clone: commits and nothing else, thrown away immediately.
     * Servers without partial-clone support ignore the filter rather than refusing. Any failure just
     * hides the section — the peek never blocks or gates cloning.
     */
    suspend fun loadPeek(candidate: String) {
        val target = candidate.trim()
        if (!WEB_URL.matches(target)) {
            peek.clear()
            return
        }
        peeking = true
        val tmp = "/root/.jcode-peek"
        val quoted = Git.quote(tmp)
        val prefix = Git.prefixFor(null)
        val command = "rm -rf $quoted && " +
            prefix + "clone -q --bare --depth 6 --filter=tree:0 --no-tags " +
            Git.quote(target) + " " + quoted + " && " +
            prefix + "--git-dir=$quoted log -n 6 --pretty=format:" +
            Git.quote("%h%x1f%an%x1f%ar%x1f%s") + "; rm -rf $quoted"
        val r = host.exec(command, timeoutMs = 60_000L)
        peeking = false
        peek.clear()
        if (!r.ok) return
        r.stdout.lines().filter { it.contains(UNIT_SEPARATOR) }.take(6).forEach { line ->
            val p = line.split(UNIT_SEPARATOR)
            peek += Commit(
                hash = p.getOrElse(0) { "" },
                author = p.getOrElse(1) { "" },
                relative = p.getOrElse(2) { "" },
                subject = p.getOrElse(3) { "" },
            )
        }
    }

    /**
     * Ask how the repository should open, before anything is downloaded.
     *
     * A repository that ships its own `.jcode` keeps what it declares; this pick only decides for
     * the ones that declare nothing. Asking first means cancelling costs nothing.
     */
    fun startClone() {
        val target = url.trim()
        if (target.isEmpty()) {
            failed = true
            message = "Enter a repository URL."
            return
        }
        if (busy) return
        scope.launch {
            val folder = sanitizeName(name.trim().ifEmpty { repoNameOf(target) }.ifEmpty { "repo" })
            if (probeSources()) intent = folder else clone(folder, "")
        }
    }

    fun cancelIntent() {
        intent = null
    }

    fun confirmIntent(type: String) {
        val folder = intent ?: return
        intent = null
        scope.launch { clone(folder, type) }
    }

    private suspend fun clone(folder: String, type: String) {
        busy = true
        failed = false
        message = "Cloning…"
        log = null
        val result = if (staging == true) stagedClone(folder, type) else legacyClone(folder)
        busy = false
        message = result.message
        failed = result.failed
    }

    private data class Outcome(val message: String, val failed: Boolean)

    /**
     * Clone into the staging directory, then hand the finished tree to the workbench.
     *
     * The tree is assembled at a dot-named sibling and moved into place last (a same-filesystem
     * rename), so a crash mid-copy never leaves a half-populated directory where the finished clone
     * should appear.
     */
    private suspend fun stagedClone(folder: String, type: String): Outcome {
        val target = "$SOURCES/$folder"
        val tmp = "/root/.jcode-clone-$folder"
        val stage = "$SOURCES/.stage-$folder"
        val finalize = "rm -rf " + Git.quote(stage) + " && cp -r " + Git.quote(tmp) + " " + Git.quote(stage) +
            " && rm -rf " + Git.quote(tmp) + " && mv " + Git.quote(stage) + " " + Git.quote(target)
        val command = "rm -rf " + Git.quote(tmp) + " " + Git.quote(target) + " && " +
            Git.prefixFor(null) + "clone --progress " + Git.quote(url.trim()) + " " + Git.quote(tmp) +
            " && { " + derefSymlinks(tmp) + "; " + finalize + "; }"
        val r = host.exec(command, workdir = SOURCES, timeoutMs = 600_000L)
        log = r.output.ifBlank { null }
        if (!r.ok) {
            host.exec("rm -rf " + Git.quote(tmp) + " " + Git.quote(stage) + " " + Git.quote(target))
            return Outcome("Clone failed.", failed = true)
        }
        // What `.jcode` the repository ships wins; the pick fills in only where it declares nothing.
        // The same rule the workbench applies when it is the one deciding.
        if (type.isNotEmpty() && !declaresType(target, folder)) stampType(target, folder, type)
        message = "Adding…"
        host.addFolder(target)
        // addFolder is one-way, so the answer is read from the filesystem: adoption *moves* the
        // folder out of staging, and a folder still sitting there means the workbench refused it.
        return if (awaitAdoption(target)) {
            host.closeView(view)
            Outcome("Opened '$folder'.", failed = false)
        } else {
            Outcome(
                "JCode didn't take the folder — it's still staged at $target. Any unsaved editor " +
                    "changes have to be saved first.",
                failed = true,
            )
        }
    }

    /**
     * Older host with no staging directory: the clone lands in the projects root directly.
     *
     * The registration names that root explicitly, because this host's `openFolder` only registers
     * projects and a blank destination would file the clone under whatever workspace happens to be
     * open.
     */
    private suspend fun legacyClone(folder: String): Outcome {
        val target = "$WORKSPACE/$folder"
        val tmp = "/root/.jcode-clone-$folder"
        if (host.exec("test -e " + Git.quote(target) + " && echo yes", workdir = WORKSPACE).stdout.trim() == "yes") {
            return Outcome("A folder named '$folder' already exists.", failed = true)
        }
        val command = "rm -rf " + Git.quote(tmp) + " && " +
            Git.prefixFor(null) + "clone --progress " + Git.quote(url.trim()) + " " + Git.quote(tmp) +
            " && { " + derefSymlinks(tmp) + "; cp -r " + Git.quote(tmp) + " " + Git.quote(target) +
            " && rm -rf " + Git.quote(tmp) + "; }"
        val r = host.exec(command, workdir = WORKSPACE, timeoutMs = 600_000L)
        log = r.output.ifBlank { null }
        if (!r.ok) {
            host.exec("rm -rf " + Git.quote(tmp) + " " + Git.quote(target), workdir = WORKSPACE)
            return Outcome("Clone failed.", failed = true)
        }
        host.openFolder(target)
        host.closeView(view)
        return Outcome("Cloned — opening…", failed = false)
    }

    /** Poll for the staged folder to disappear, which is what adoption does to it. */
    private suspend fun awaitAdoption(target: String): Boolean {
        repeat(ADOPTION_TRIES) {
            kotlinx.coroutines.delay(ADOPTION_POLL_MS)
            if (host.exec("test -d " + Git.quote(target) + " || echo gone").stdout.trim() == "gone") return true
        }
        return false
    }

    private suspend fun declaresType(dir: String, folder: String): Boolean =
        host.exec(
            "grep -qs '^[[:space:]]*type:' " + Git.quote("$dir/.jcode/$folder.yaml") + " && echo yes",
        ).stdout.trim() == "yes"

    private suspend fun stampType(dir: String, folder: String, type: String) {
        val config = Git.quote("$dir/.jcode/$folder.yaml")
        host.exec(
            "mkdir -p " + Git.quote("$dir/.jcode") + " && printf '%s\\n' " +
                Git.quote("name: $folder") + " " + Git.quote("type: $type") + " > " + config,
        )
    }

    // --- the GitHub browser ----------------------------------------------------------------------

    var loadingRepos by mutableStateOf(false)
        private set
    var reposError by mutableStateOf<String?>(null)
        private set
    var reposLog by mutableStateOf<String?>(null)
        private set

    /** Empty when there are no stored credentials, which is its own screen rather than an error. */
    var githubUser by mutableStateOf("")
        private set

    val repos = mutableStateListOf<RemoteRepo>()
    val owners = mutableStateListOf<String>()
    var owner by mutableStateOf("")

    fun loadRepos() {
        scope.launch {
            loadingRepos = true
            reposError = null
            reposLog = null
            repos.clear()
            owners.clear()
            githubUser = host.exec("git config --global --get github.user 2>/dev/null").stdout.trim()
            val credential = host.exec("grep -m1 github.com ~/.git-credentials 2>/dev/null").stdout.trim()
            val token = credential.takeIf { it.startsWith("https://") }
                ?.removePrefix("https://")?.substringBefore('@')?.substringAfter(':', "")
                .orEmpty()
            if (githubUser.isEmpty() || token.isEmpty()) {
                githubUser = ""
                loadingRepos = false
                return@launch
            }
            val r = host.exec(
                "curl -fsS -H \"Authorization: token \$GH_TOKEN\" -H \"User-Agent: JCode\" " +
                    "-H \"Accept: application/vnd.github+json\" " +
                    "\"https://api.github.com/user/repos?per_page=100&sort=updated\"",
                env = mapOf("GH_TOKEN" to token),
                timeoutMs = 30_000L,
            )
            loadingRepos = false
            if (!r.ok) {
                val hasCurl = host.exec("command -v curl >/dev/null 2>&1 && echo yes").stdout.trim() == "yes"
                reposError = if (hasCurl) "Failed to load repositories."
                else "curl isn't installed in this environment — install it from Tools → Toolchains."
                reposLog = r.output.ifBlank { null }
                return@launch
            }
            val parsed = runCatching { JSONArray(r.stdout) }.getOrNull() ?: run {
                reposError = "Could not parse the GitHub response."
                return@launch
            }
            for (i in 0 until parsed.length()) {
                val o = parsed.optJSONObject(i) ?: continue
                val repoName = o.optString("name").ifBlank { continue }
                repos += RemoteRepo(
                    name = repoName,
                    owner = o.optJSONObject("owner")?.optString("login").orEmpty(),
                    cloneUrl = o.optString("clone_url"),
                    description = o.optString("description").takeIf { it != "null" }.orEmpty(),
                    private = o.optBoolean("private"),
                )
            }
            // The signed-in user first, then everyone else alphabetically: an organisation you
            // belong to is a tab, but your own repositories are what you came for.
            owners += repos.map { it.owner }.filter { it.isNotEmpty() }.distinct()
                .sortedWith(compareBy({ it != githubUser }, { it.lowercase() }))
            if (owner !in owners) owner = owners.firstOrNull().orEmpty()
        }
    }

    /** The repositories on the open tab, by name. */
    fun reposFor(login: String): List<RemoteRepo> =
        repos.filter { it.owner == login }.sortedBy { it.name.lowercase() }

    /** Fill the form in from a listed repository — reviewed and confirmed, not cloned on the tap. */
    fun prepare(repo: RemoteRepo) {
        url = repo.cloneUrl
        name = sanitizeName(repo.name)
        autoName = name
        message = null
        failed = false
        log = null
        updateDestination()
        screen = CloneScreen.Form
    }

    /** Only offered when the browser is where the form was reached from. */
    val canGoBack: Boolean get() = browsable

    fun backToList() {
        screen = CloneScreen.Remote
    }

    fun openConnect() = host.openView("github")
}

private const val SOURCES = "/sources"
private const val WORKSPACE = "/workspace"

/** Adoption is a move plus a workbench round trip; a couple of seconds covers it. */
private const val ADOPTION_TRIES = 20
private const val ADOPTION_POLL_MS = 150L

private val WEB_URL = Regex("^https?://\\S+$")

/**
 * Dereference the symlinks proot's `--link2symlink` leaves in a fresh clone.
 *
 * `stat()` on them gives EPERM but `open()` still reads them, so each is copied to a regular file.
 * The order matters: the backing files are named `.l2s.<original>`, and deleting those before the
 * dereference orphans — then deletes — whatever they carried. That ordering destroyed every
 * pack-transferred clone's object store.
 */
private fun derefSymlinks(dir: String): String {
    val quoted = Git.quote(dir)
    return "find $quoted -type l 2>/dev/null | while IFS= read -r l; do " +
        "if cat \"\$l\" > \"\$l.deref\" 2>/dev/null && [ -s \"\$l.deref\" ]; then " +
        "rm -f \"\$l\"; mv \"\$l.deref\" \"\$l\"; else rm -f \"\$l.deref\" \"\$l\"; fi; done; " +
        "find $quoted -name '.l2s.*' -delete 2>/dev/null; " +
        "find $quoted -xtype l -delete 2>/dev/null"
}

/**
 * The folder name a URL implies: its last path segment, `.git` stripped.
 *
 * The same default the clone itself would use, so the field shows what would happen anyway rather
 * than proposing something new.
 */
private fun repoNameOf(url: String): String =
    url.trimEnd('/').removeSuffix(".git").substringAfterLast('/')

/**
 * Must match the workbench's own `sanitizedFolderName` so the clone target lines up with what gets
 * registered. Leading dots go too: a dot-named folder is refused by `addFolder`, and `ls` would hide
 * it anyway, so a repository called ".dotfiles" stages as "dotfiles".
 */
internal fun sanitizeName(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .replace(Regex("^[.-]+|-+$"), "")
        .ifEmpty { "project" }
