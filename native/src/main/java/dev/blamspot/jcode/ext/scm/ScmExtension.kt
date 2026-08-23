package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope

/**
 * Source Control, native.
 *
 * The entry point JCode instantiates by name and splices into its own composition. Everything here
 * runs in JCode's process against JCode's Compose runtime — hence the `compileOnly` dependency
 * rules in the build script.
 *
 * One extension, several surfaces: the drawer panel plus the pages it opens. Each page owns its own
 * state and asks git its own questions, because a page opens on its own — restored with a session,
 * or reached from another page — and cannot see what the panel decided. Only the branch the route
 * selects is built, so a diff page does not quietly run the panel's whole boot behind it.
 */
class ScmExtension : JCodeNativeExtension {

    @Composable
    override fun Content(host: NativeHost, params: Map<String, String>) {
        val scope = rememberCoroutineScope()
        val view = params[JCodeNativeExtension.Params.VIEW].orEmpty()
        when {
            view == "github" -> {
                val auth = remember(host, scope) { AuthState(host, scope) }
                LaunchedEffect(auth) { auth.boot() }
                AuthPage(auth)
            }

            view == "manage" -> {
                val manage = remember(host, scope) { ManageState(host, scope) }
                LaunchedEffect(manage) { manage.boot() }
                ManagePage(manage)
            }

            view == "clone" || view == "remoteRepo" -> {
                val clone = remember(host, scope, view) {
                    CloneState(
                        host = host,
                        scope = scope,
                        view = view,
                        startOn = if (view == "clone") CloneScreen.Form else CloneScreen.Remote,
                    )
                }
                LaunchedEffect(clone) { clone.boot() }
                ClonePage(clone)
            }

            view.startsWith("commit:") -> {
                val commit = remember(host, scope, view) { CommitState(host, scope, view) }
                LaunchedEffect(commit) { commit.boot() }
                CommitPage(commit)
            }

            view.startsWith("merge:") -> {
                val merge = remember(host, scope, view) { MergeState(host, scope, view) }
                LaunchedEffect(merge) { merge.boot() }
                MergePage(merge)
            }

            view.startsWith("diff:") || view.startsWith("stash:") -> {
                val diff = remember(host, scope, view) { DiffState(host, scope, view) }
                LaunchedEffect(diff) { diff.boot() }
                DiffPage(diff)
            }

            // The drawer, and anything unrecognised: a route this plugin cannot draw falls through
            // to the panel rather than rendering nothing.
            else -> Panel(host, scope)
        }
    }

    @Composable
    private fun Panel(host: NativeHost, scope: CoroutineScope) {
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
                    "config" -> state.loadSettings()
                }
            }
            onDispose { handle.close() }
        }

        ScmPanel(state)
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
