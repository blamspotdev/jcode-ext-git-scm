package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.handCursor

/**
 * Connect to GitHub, and say who your commits are from.
 *
 * Two things on one page because they are one errand: nobody connects without also wanting their
 * commits attributed, and git refuses to commit at all until the identity is set. Connecting
 * settles both — the account is asked for the name and address, so the identity card shows a result
 * rather than an empty form.
 */
@Composable
internal fun AuthPage(state: AuthState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        item {
            PageHeader(
                icon = rememberVectorPainter(ScmIcons.GitHub),
                title = "Source Control",
                subtitle = "Connect with a username and token; your commit identity follows",
            )
        }
        if (state.loading) {
            item { Note("Reading your git configuration…", spinner = true) }
        } else {
            item { if (state.user.isEmpty()) ConnectCard(state) else ConnectedCard(state) }
            item { IdentityCard(state) }
        }
    }
}

@Composable
private fun ConnectedCard(state: AuthState) {
    Card(title = "GitHub") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Avatar(state.user)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.user,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Muted("Connected · credentials saved for push and pull")
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            CompactOutlinedButton(
                text = "Disconnect",
                onClick = { state.disconnect() },
                enabled = !state.busy,
            )
            state.authMessage?.let { StatusText(it, state.authFailed) }
        }
    }
}

/** The first letter of the username, which is as much of an avatar as an offline page can draw. */
@Composable
private fun Avatar(user: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = user.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ConnectCard(state: AuthState) {
    Card(title = "Connect to GitHub") {
        Muted(
            "Your username and a Personal Access Token — repo to push and pull, user:email to read " +
                "the name and address for your commits. Stored via git's credential helper so push " +
                "and pull just work.",
        )
        FieldLabel("Username")
        CompactField(
            value = state.username,
            onValueChange = { state.username = it },
            placeholder = "GitHub username",
            literal = true,
        )
        FieldLabel("Token")
        CompactField(
            value = state.token,
            onValueChange = { state.token = it },
            placeholder = "ghp_… or github_pat_…",
            literal = true,
            password = true,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            CompactFilledButton(
                text = "Connect",
                onClick = { state.connect() },
                enabled = !state.busy,
            )
            state.authMessage?.let { StatusText(it, state.authFailed) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Muted("Create a token at")
            Text(
                text = "github.com/settings/tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { state.openTokenPage() }.handCursor(),
            )
        }
    }
}

/**
 * The author on your commits — shown, not asked for.
 *
 * Connecting reads the name and the verified address off the account, so the usual case is a card
 * with the answer already in it. The fields are still here behind Edit: the lookup can come back
 * empty on a narrow token or an offline environment, and some people commit under a different name
 * than the one on their GitHub profile.
 */
@Composable
private fun IdentityCard(state: AuthState) {
    Card(title = "Git identity") {
        if (state.editingIdentity) {
            Muted(
                if (state.user.isEmpty()) "The author name and email on your commits. Also editable in Settings → Source Control."
                else "Overrides what GitHub reported. Also editable in Settings → Source Control.",
            )
            FieldLabel("Name")
            CompactField(
                value = state.name,
                onValueChange = { state.name = it },
                placeholder = "Your name",
            )
            FieldLabel("Email")
            CompactField(
                value = state.email,
                onValueChange = { state.email = it },
                placeholder = "you@example.com",
                literal = true,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                CompactFilledButton(
                    text = "Save",
                    onClick = { state.saveIdentity() },
                    enabled = !state.busy,
                )
                CompactOutlinedButton(
                    text = "Cancel",
                    onClick = { state.cancelEditIdentity() },
                    enabled = !state.busy,
                )
                state.identityMessage?.let { StatusText(it, state.identityFailed) }
            }
            return@Card
        }
        if (state.name.isEmpty() && state.email.isEmpty()) {
            Muted("Not set yet. Connect above and GitHub fills this in, or set it by hand.")
        } else {
            Muted(
                if (state.user.isEmpty()) "The author name and email on your commits."
                else "Taken from your GitHub account when you connected.",
            )
            Text(
                text = state.name.ifEmpty { "No name set" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Muted(state.email.ifEmpty { "No email set" })
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            CompactOutlinedButton(
                text = "Edit",
                onClick = { state.editIdentity() },
                enabled = !state.busy,
            )
            state.identityMessage?.let { StatusText(it, state.identityFailed) }
        }
    }
}
