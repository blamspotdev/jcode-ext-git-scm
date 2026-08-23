package dev.blamspot.jcode.ext.scm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
            authMessage = "Signing in…"
            val identity = resolveIdentity(secret, who)
            val quotedUser = Git.quote(who)
            val command = buildString {
                append("git config --global credential.helper store; ")
                append("git config --global github.user $quotedUser; ")
                append("if [ -z \"\$(git config --global --get user.name)\" ]; then ")
                append("git config --global user.name \"\$GH_NAME\"; fi; ")
                append("if [ -z \"\$(git config --global --get user.email)\" ]; then ")
                append("git config --global user.email \"\$GH_EMAIL\"; fi; ")
                append("umask 077; touch ~/.git-credentials; ")
                append("sed -i '/@github\\.com\$/d' ~/.git-credentials 2>/dev/null; ")
                // Through the environment rather than the command line: a token in an argument is a
                // token in `ps` output and in whatever logs the shell keeps.
                append("printf 'https://%s:%s@github.com\\n' \"\$GH_USER\" \"\$GH_TOKEN\" >> ~/.git-credentials")
            }
            val r = host.exec(
                command,
                env = mapOf(
                    "GH_USER" to who,
                    "GH_TOKEN" to secret,
                    "GH_NAME" to identity.name,
                    "GH_EMAIL" to identity.email,
                ),
            )
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

    /**
     * Who GitHub says the token belongs to.
     *
     * Asked rather than assumed. This used to write the typed username into `user.name` and
     * `<login>@users.noreply.github.com` into `user.email` — a guess at the first and wrong at the
     * second: GitHub's noreply address has carried the account's numeric id since 2017, and a push
     * from an account with "keep my email private" set is rejected without it.
     *
     * Everything here degrades rather than failing. With no curl, no network, or a token too narrow
     * to read the address list, sign-in still completes on whatever could be resolved — the point of
     * the button is to save credentials, and the identity is editable on this very page.
     */
    private suspend fun resolveIdentity(token: String, typedLogin: String): Identity {
        val account = fetch("https://api.github.com/user", token)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val login = account?.text("login") ?: typedLogin
        val id = account?.optLong("id", 0L) ?: 0L
        val noreply = if (id > 0L) "$id+$login@users.noreply.github.com"
        else "$login@users.noreply.github.com"
        return Identity(
            name = account?.text("name") ?: login,
            // A verified primary address first, the profile's public one next, and GitHub's own
            // stand-in last — a private account has no other address that will push.
            email = primaryEmail(token) ?: account?.text("email") ?: noreply,
        )
    }

    /** The address on the account, when the token is allowed to read the list at all. */
    private suspend fun primaryEmail(token: String): String? {
        val body = fetch("https://api.github.com/user/emails", token) ?: return null
        val list = runCatching { JSONArray(body) }.getOrNull() ?: return null
        var verified: String? = null
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            if (!entry.optBoolean("verified")) continue
            val address = entry.text("email") ?: continue
            if (entry.optBoolean("primary")) return address
            if (verified == null) verified = address
        }
        return verified
    }

    private suspend fun fetch(url: String, token: String): String? {
        val r = host.exec(
            "curl -fsS -H \"Authorization: token \$GH_TOKEN\" -H \"User-Agent: JCode\" " +
                "-H \"Accept: application/vnd.github+json\" " + Git.quote(url),
            env = mapOf("GH_TOKEN" to token),
            timeoutMs = 30_000L,
        )
        return r.stdout.takeIf { r.ok && it.isNotBlank() }
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

    /**
     * A token pre-scoped for what this page does with it.
     *
     * `repo` to push and pull; `user:email` so the account's verified address can be read and put on
     * your commits. Without the second one sign-in still works and falls back to GitHub's noreply
     * address, which pushes — it is just not the address you would have chosen.
     */
    fun openTokenPage() =
        host.openUrl("https://github.com/settings/tokens/new?scopes=repo,user:email&description=JCode")
}

/** A git identity, as GitHub reports it. */
private data class Identity(val name: String, val email: String)

/**
 * A field's text, or null.
 *
 * `optString` cannot express "absent": a JSON null comes back as the four characters "null", and
 * GitHub returns null for a name or an email the account keeps private — which is exactly the case
 * this has to tell apart.
 */
private fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
