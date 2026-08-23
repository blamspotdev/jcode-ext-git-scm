package dev.blamspot.jcode.ext.scm

import dev.blamspot.jcode.ext.api.NativeExecResult
import dev.blamspot.jcode.ext.api.NativeHost

/**
 * Git, as run inside JCode's Linux runtime.
 *
 * Every invocation carries the same three settings, and each is load-bearing:
 *
 *  - `safe.directory='*'` — commands run as root over files the user owns, which git otherwise
 *    refuses to touch.
 *  - `core.quotePath=false` — paths come back verbatim instead of C-escaped, so a non-ASCII
 *    filename is a filename rather than a puzzle.
 *  - `core.createObject=rename` — git normally finalises objects with link()+unlink(), which proot's
 *    `--link2symlink` emulates as a symlink onto an `.l2s.*` backing file; the clone pipeline's
 *    cleanup then destroyed the object store, and every pack-transferred clone died with
 *    "fatal: bad object HEAD". rename() produces plain files, so no git write leaves l2s artifacts.
 */
internal class Git(private val host: NativeHost) {

    suspend fun run(workdir: String?, args: String, timeoutMs: Long = 60_000L): NativeExecResult =
        host.exec(PREFIX + args, workdir = workdir, timeoutMs = timeoutMs)

    /** A plain command in [workdir] — for the handful of things that are not git. */
    suspend fun shell(workdir: String?, command: String, timeoutMs: Long = 60_000L): NativeExecResult =
        host.exec(command, workdir = workdir, timeoutMs = timeoutMs)

    companion object {
        private const val PREFIX =
            "git -c safe.directory='*' -c core.quotePath=false -c core.createObject=rename "

        /** One shell word. Paths come from git and from the user's workspace; neither is trusted input. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /** The last path segment, tolerating a trailing slash. */
        fun baseName(path: String): String {
            val trimmed = path.trimEnd('/')
            val i = trimmed.lastIndexOf('/')
            return if (i >= 0) trimmed.substring(i + 1) else trimmed
        }
    }
}

/** A file in the status, as one row of the panel. */
internal data class FileEntry(
    /** The status letter to badge it with: M, A, D, R, ?, or ! for a conflict. */
    val code: String,
    val path: String,
    /** What to show — a rename reads as "old → new" while [path] stays the file to act on. */
    val display: String,
    val untracked: Boolean,
)

internal data class RepoInfo(val root: String, val name: String)

internal data class StashEntry(val ref: String, val desc: String)

/** A parsed `git status --porcelain=v1 -b`. */
internal data class GitStatus(
    val branch: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val staged: List<FileEntry> = emptyList(),
    val unstaged: List<FileEntry> = emptyList(),
    val conflicts: List<FileEntry> = emptyList(),
)

/**
 * Undo git's C-style quoting of a path.
 *
 * Only reached when `core.quotePath=false` did not apply — an older git, or a path git quotes
 * regardless. A JSON string literal has the same escapes, so parsing it as one is exact rather than
 * approximate; a path that will not parse falls back to stripping the quotes.
 */
internal fun unquoteGitPath(p: String): String {
    if (p.length < 2 || p.first() != '"' || p.last() != '"') return p
    return runCatching { jsonUnescape(p.substring(1, p.length - 1)) }
        .getOrElse { p.substring(1, p.length - 1) }
}

private fun jsonUnescape(body: String): String = buildString {
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '\\') {
            append(c)
            i++
            continue
        }
        i++
        if (i >= body.length) break
        when (val e = body[i]) {
            'n' -> append('\n')
            't' -> append('\t')
            'r' -> append('\r')
            'b' -> append('\b')
            'f' -> append('')
            '"' -> append('"')
            '\\' -> append('\\')
            '/' -> append('/')
            'u' -> {
                val hex = body.substring(i + 1, minOf(i + 5, body.length))
                append(hex.toIntOrNull(16)?.toChar() ?: e)
                i += 4
            }
            else -> append(e)
        }
        i++
    }
}

/**
 * Parse `git status --porcelain=v1 -b -uall`.
 *
 * Unmerged states (DD AU UD UA DU AA UU) become [GitStatus.conflicts] rather than appearing under
 * both Staged and Changes, because a conflicted file is one thing to resolve, not two things to
 * stage.
 */
internal fun parseStatus(text: String): GitStatus {
    var branch = ""
    var ahead = 0
    var behind = 0
    val staged = mutableListOf<FileEntry>()
    val unstaged = mutableListOf<FileEntry>()
    val conflicts = mutableListOf<FileEntry>()

    for (line in text.split('\n')) {
        if (line.isEmpty()) continue
        if (line.length >= 3 && line.startsWith("## ")) {
            val b = line.substring(3)
            if (b.startsWith("No commits yet on ")) {
                branch = b.substring(18).trim().split(Regex("\\s"))[0]
                continue
            }
            val d = b.indexOf("...")
            if (d >= 0) {
                branch = b.substring(0, d)
                val rest = b.substring(d + 3)
                Regex("ahead (\\d+)").find(rest)?.let { ahead = it.groupValues[1].toIntOrNull() ?: 0 }
                Regex("behind (\\d+)").find(rest)?.let { behind = it.groupValues[1].toIntOrNull() ?: 0 }
            } else {
                branch = b.trim().split(Regex("\\s"))[0]
            }
            continue
        }
        if (line.length < 3) continue
        val x = line[0]
        val y = line[1]
        var path = line.substring(3)
        var display: String
        val arrow = path.indexOf(" -> ")
        if (arrow >= 0) {
            val parts = path.split(" -> ").map(::unquoteGitPath)
            display = parts[0] + " → " + parts[1]
            path = parts[1]
        } else {
            path = unquoteGitPath(path)
            display = path
        }

        if (x == '?' && y == '?') {
            unstaged += FileEntry("?", path, display, untracked = true)
            continue
        }
        if (x == 'U' || y == 'U' || (x == 'D' && y == 'D') || (x == 'A' && y == 'A')) {
            conflicts += FileEntry("!", path, display, untracked = false)
            continue
        }
        if (x != ' ' && x != '?') staged += FileEntry(x.toString(), path, display, untracked = false)
        if (y != ' ' && y != '?') unstaged += FileEntry(y.toString(), path, display, untracked = false)
    }
    return GitStatus(branch, ahead, behind, staged, unstaged, conflicts)
}

/**
 * One status letter per path for the Explorer badge.
 *
 * The staged letter wins ("AM" reads as a new file), worktree and untracked letters fill the gaps,
 * and a conflict overrides everything as 'U'. Conflicts come first so a set truncated by the
 * workbench never drops them.
 */
internal fun decorationsFrom(status: GitStatus): List<Pair<String, String>> {
    val byPath = LinkedHashMap<String, String>()
    status.conflicts.forEach { byPath[it.path] = "U" }
    status.staged.forEach { byPath.getOrPut(it.path) { it.code } }
    status.unstaged.forEach { byPath.getOrPut(it.path) { if (it.untracked) "?" else it.code } }
    return byPath.map { (path, status) -> path to status }
}
