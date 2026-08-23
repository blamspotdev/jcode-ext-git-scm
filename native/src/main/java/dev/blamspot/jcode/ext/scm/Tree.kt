package dev.blamspot.jcode.ext.scm

/** A folder in the changed-files tree, or a file sitting in one. */
internal sealed interface TreeRow {
    val depth: Int

    data class Folder(val label: String, val key: String, override val depth: Int, val collapsed: Boolean) : TreeRow
    data class File(val entry: FileEntry, override val depth: Int) : TreeRow
}

private class Node(val name: String, val path: String) {
    val folders = LinkedHashMap<String, Node>()
    val files = mutableListOf<FileEntry>()
}

/**
 * Lay the changed files out as a tree.
 *
 * Single-child folder chains are compressed the way VS Code compresses them — `a/b/c` is one row
 * reading "a/b/c" rather than three rows each holding one child. On a drawer this is the difference
 * between seeing your files and scrolling past the path to them.
 */
internal fun buildTreeRows(files: List<FileEntry>, collapsed: Set<String>): List<TreeRow> {
    val root = Node("", "")
    for (f in files) {
        val parts = f.path.split('/')
        var node = root
        var acc = ""
        for (i in 0 until parts.size - 1) {
            val seg = parts[i]
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            node = node.folders.getOrPut(seg) { Node(seg, acc) }
        }
        node.files += f
    }

    val out = mutableListOf<TreeRow>()

    fun walk(folder: Node, depth: Int) {
        folder.folders.values.sortedBy { it.name.lowercase() }.forEach { sub ->
            var node = sub
            var label = sub.name
            var key = sub.path
            while (node.files.isEmpty() && node.folders.size == 1) {
                val only = node.folders.values.first()
                label = "$label/${only.name}"
                node = only
                key = only.path
            }
            val isClosed = key in collapsed
            out += TreeRow.Folder(label, key, depth, isClosed)
            if (!isClosed) walk(node, depth + 1)
        }
        folder.files.sortedBy { Git.baseName(it.path).lowercase() }.forEach { f ->
            out += TreeRow.File(f, depth)
        }
    }

    walk(root, 0)
    return out
}
