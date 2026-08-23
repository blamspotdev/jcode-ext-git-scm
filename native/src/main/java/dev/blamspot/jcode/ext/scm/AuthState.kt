package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * GitHub credentials and the git identity, for the page that sets both.
 *
 * These are global git settings, not a repository's, so nothing here takes a working directory: the
 * page is reachable with no project open, which is exactly when someone signs in for the first time.
 */
internal class AuthState(
    private val host: NativeHost,
    private val scope: CoroutineScope,
) {
    var user by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set

    var username by mutableStateOf("")
    var token by mutableStateOf("")
    var name by mutableStateOf("")
    var email by mutableStateOf("")

    /** The sign-in card's own line of feedback, separate from the identity card's. */
    var authMessage by mutableStateOf<String?>(null)
        private set
    var authFailed by mutableStateOf(false)
        private set
    var identityMessage by mutableStateOf<String?>(null)
        private set
    var identityFailed by mutableStateOf(false)
        private set

    fun boot() {
        scope.launch {
            load()
            loading = false
        }
    }

    private suspend fun load() {
        user = readGlobal("github.user")
        name = readGlobal("user.name")
        email = readGlobal("user.email")
    }

    private suspend fun readGlobal(key: String): String =
        host.exec("git config --global --get $key 2>/dev/null", timeoutMs = 20_000L).stdout.trim()

    fun signIn() {
        val who = username.trim()
        val secret = token.trim()
        if (who.isEmpty() || secret.isEmpty()) {
            authFailed = true
            authMessage = "Enter your username and token."
            return
        }
        if (busy) return
        scope.launch {
            busy = true
            authFailed = false
            authMessage = "Saving…"
            // Without git in the runtime the config writes silently no-op, and because the command
            // ends in a printf that still exits 0 the sign-in would look like it did nothing. Say so
            // up front instead: a fresh environment set up with bootstrap skipped has no git yet.
            val hasGit = host.exec("command -v git >/dev/null 2>&1 && echo yes").stdout.trim() == "yes"
            if (!hasGit) {
                busy = false
                authFailed = true
                authMessage = "Git isn't installed in this environment yet — install it from " +
                    "Tools → Toolchains (git), then sign in."
                return@launch
            }
            val quotedUser = Git.quote(who)
            val quotedEmail = Git.quote("$who@users.noreply.github.com")
            val command = buildString {
                append("git config --global credential.helper store; ")
                append("git config --global github.user $quotedUser; ")
                append("if [ -z \"\$(git config --global --get user.name)\" ]; then ")
                append("git config --global user.name $quotedUser; fi; ")
                append("if [ -z \"\$(git config --global --get user.email)\" ]; then ")
                append("git config --global user.email $quotedEmail; fi; ")
                append("umask 077; touch ~/.git-credentials; ")
                append("sed -i '/@github\\.com\$/d' ~/.git-credentials 2>/dev/null; ")
                // Through the environment rather than the command line: a token in an argument is a
                // token in `ps` output and in whatever logs the shell keeps.
                append("printf 'https://%s:%s@github.com\\n' \"\$GH_USER\" \"\$GH_TOKEN\" >> ~/.git-credentials")
            }
            val r = host.exec(command, env = mapOf("GH_USER" to who, "GH_TOKEN" to secret))
            busy = false
            if (!r.ok) {
                authFailed = true
                authMessage = r.failure
                return@launch
            }
            // The exit code belongs to the final printf, so it cannot report a git failure earlier in
            // the chain — confirm the username actually landed rather than trusting the zero.
            if (readGlobal("github.user") != who) {
                authFailed = true
                authMessage = "Could not save credentials — is git working in this environment?"
                return@launch
            }
            token = ""
            username = ""
            authMessage = null
            load()
        }
    }

    fun signOut() {
        if (busy) return
        scope.launch {
            busy = true
            host.exec(
                "git config --global --unset github.user 2>/dev/null; " +
                    "sed -i '/@github\\.com\$/d' ~/.git-credentials 2>/dev/null; true",
            )
            busy = false
            authMessage = null
            authFailed = false
            load()
        }
    }

    fun saveIdentity() {
        val who = name.trim()
        val address = email.trim()
        if (who.isEmpty() || address.isEmpty()) {
            identityFailed = true
            identityMessage = "Enter both a name and an email."
            return
        }
        if (busy) return
        scope.launch {
            busy = true
            val r = host.exec(
                "git config --global user.name " + Git.quote(who) +
                    " && git config --global user.email " + Git.quote(address),
            )
            busy = false
            identityFailed = !r.ok
            identityMessage = if (r.ok) "Saved." else r.failure
        }
    }

    fun openTokenPage() =
        host.openUrl("https://github.com/settings/tokens/new?scopes=repo&description=JCode")
}
