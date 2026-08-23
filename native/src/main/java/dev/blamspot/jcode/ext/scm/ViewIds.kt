package dev.blamspot.jcode.ext.scm

/**
 * The ids the panel opens its pages with, and the encoding they carry paths in.
 *
 * A page opens on its own and cannot see what the panel decided, so everything it needs travels in
 * the id: which repository, which file, which side of the diff. That is why the repository root is
 * in there — re-deriving it from the open project is wrong the moment a workspace holds two.
 */

/**
 * Percent-encode one segment, the way `encodeURIComponent` does.
 *
 * JavaScript's encoding specifically, because these ids were designed for pages that decoded them in
 * JavaScript and the format is now shared with the panel: `URLEncoder` would write a space as `+`
 * and the path would not survive the round trip.
 */
internal fun encodeUri(value: String): String = buildString {
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && b.toInt() in 0..127 || c in "-_.!~*'()") {
            append(c)
        } else {
            append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
    }
}

/** Undo [encodeUri]. A segment that is not valid encoding is returned as it came. */
internal fun decodeUri(value: String): String {
    if ('%' !in value) return value
    val bytes = java.io.ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.write(hex)
                i += 3
                continue
            }
        }
        bytes.write(c.code)
        i++
    }
    return runCatching { String(bytes.toByteArray(), Charsets.UTF_8) }.getOrDefault(value)
}

private const val HEX = "0123456789ABCDEF"

/** Which side of a file's history a diff is showing. */
internal enum class DiffMode {
    /** The index against HEAD — what a commit would contain. */
    Staged,

    /** The working tree against the index — what is not staged yet. */
    Working,

    /** A file git has never seen, shown whole as an addition. */
    Untracked,
    ;

    companion object {
        fun from(token: String): DiffMode = when (token) {
            "s" -> Staged
            "u" -> Untracked
            else -> Working
        }
    }
}

/** `diff:<mode>:<repo>:<path>`, already decoded. */
internal data class DiffTarget(val mode: DiffMode, val repo: String, val path: String)

/**
 * Read a `diff:` view id.
 *
 * The legacy three-part form (`diff:<mode>:<path>`, no repository) is still accepted because tabs
 * restored from an earlier session carry it, and reopening one should show a diff rather than an
 * error about a format nobody typed.
 */
internal fun parseDiffView(view: String): DiffTarget? {
    if (!view.startsWith("diff:")) return null
    val rest = view.removePrefix("diff:")
    val mode = DiffMode.from(rest.substringBefore(':'))
    val afterMode = rest.substringAfter(':', "")
    if (afterMode.isEmpty()) return null
    // A repository root is absolute, so it always encodes with a leading %2F: a second colon means
    // the repo is present, and its absence means the legacy form.
    val hasRepo = afterMode.contains(':')
    val repo = if (hasRepo) decodeUri(afterMode.substringBefore(':')) else ""
    val path = decodeUri(if (hasRepo) afterMode.substringAfter(':') else afterMode)
    return DiffTarget(mode, repo, path)
}

/** `stash:<repo>:<ref>` or `merge:<repo>:<path>` — the same two-segment shape. */
internal fun parseRefView(view: String, prefix: String): Pair<String, String>? {
    if (!view.startsWith("$prefix:")) return null
    val rest = view.removePrefix("$prefix:")
    if (!rest.contains(':')) return null
    return decodeUri(rest.substringBefore(':')) to decodeUri(rest.substringAfter(':'))
}
