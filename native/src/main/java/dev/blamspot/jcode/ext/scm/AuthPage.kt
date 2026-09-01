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
 * Sign in to GitHub, and say who your commits are from.
 *
 * Two things on one page because they are one errand: nobody signs in without also wanting their
 * commits attributed, and git refuses to commit at all until the identity is set.
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
                subtitle = "Sign in to GitHub and set your commit identity",
            )
        }
        if (state.loading) {
            item { Note("Reading your git configuration…", spinner = true) }
        } else {
            item { if (state.user.isEmpty()) SignInCard(state) else SignedInCard(state) }
            item { IdentityCard(state) }
        }
    }
}

@Composable
private fun SignedInCard(state: AuthState) {
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
                Muted("Signed in · credentials saved for push / pull")
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            CompactOutlinedButton(
                text = "Sign out",
                onClick = { state.signOut() },
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
private fun SignInCard(state: AuthState) {
    Card(title = "Sign in to GitHub") {
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
                text = "Sign in",
                onClick = { state.signIn() },
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

@Composable
private fun IdentityCard(state: AuthState) {
    Card(title = "Git identity") {
        Muted("The author name and email on your commits. Also editable in Settings → Source Control.")
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
                text = "Save identity",
                onClick = { state.saveIdentity() },
                enabled = !state.busy,
            )
            state.identityMessage?.let { StatusText(it, state.identityFailed) }
        }
    }
}
