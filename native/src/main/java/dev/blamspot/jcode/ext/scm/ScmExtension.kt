package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.NativeHost

/**
 * Source Control, native.
 *
 * The entry point JCode instantiates by name and splices into its own composition. Everything here
 * runs in JCode's process against JCode's Compose runtime — hence the `compileOnly` dependency
 * rules in the build script.
 */
class ScmExtension : JCodeNativeExtension {

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        val scope = rememberCoroutineScope()
        val state = remember(host, scope) { ScmState(host, scope) }

        // Repo detection is the first thing and belongs to the surface's lifetime, not to a
        // recomposition: the panel is rebuilt whenever the drawer switches tools.
        LaunchedEffect(state) { state.boot() }

        // The workbench tells the plugin when the tree changed underneath it — a build wrote
        // outputs, a terminal committed, another tool checked a branch out. Without this the panel
        // shows whatever was true when it was opened.
        DisposableEffect(state) {
            val handle = host.onEvent { name, json ->
                when (name) {
                    "filesChanged" -> state.scheduleRefresh()
                    "explorerAction" -> handleExplorerAction(state, json)
                }
            }
            onDispose { handle.close() }
        }

        when (params[JCodeNativeExtension.Params.VIEW]) {
            // The drawer, and for now the only surface: the sign-in, manage, clone, diff, merge and
            // stash pages are still the web build's. A route this plugin does not draw yet must fall
            // through to the panel rather than render nothing.
            else -> ScmPanel(state)
        }
    }

    /**
     * An Explorer context-menu tap addressed to this extension.
     *
     * Handled here rather than polled: the workbench pushes it, and the panel may not be the surface
     * that has it — the tap can arrive while a different drawer tool is showing.
     */
    private fun handleExplorerAction(state: ScmState, json: String) {
        // Deliberately not a JSON parser dependency: the payload is two fields and pulling in a
        // library to read them would be the plugin's only bundled dependency.
        val actionId = jsonField(json, "actionId")
        val path = jsonField(json, "path")
        if (actionId == "addToGitignore" && !path.isNullOrBlank()) {
            state.scheduleRefresh()
        }
    }
}

/** The value of one flat string field, or null. Enough for the two-field event payloads. */
internal fun jsonField(json: String, key: String): String? {
    val needle = "\"$key\""
    val at = json.indexOf(needle)
    if (at < 0) return null
    var i = json.indexOf(':', at + needle.length)
    if (i < 0) return null
    i++
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != '"') return null
    i++
    val sb = StringBuilder()
    while (i < json.length && json[i] != '"') {
        if (json[i] == '\\' && i + 1 < json.length) {
            i++
            sb.append(json[i])
        } else {
            sb.append(json[i])
        }
        i++
    }
    return sb.toString()
}
