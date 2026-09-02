package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.handCursor
import kotlinx.coroutines.delay

/**
 * Clone a repository, or pick one of yours off GitHub to clone.
 *
 * The browser leads into the form rather than cloning on the tap: the name and the destination are
 * still yours to change, and a repository list is a place to look around, not a row of triggers.
 */
@Composable
internal fun ClonePage(state: CloneState, modifier: Modifier = Modifier) {
    when (state.screen) {
        CloneScreen.Form -> CloneForm(state, modifier)
        CloneScreen.Remote -> RemoteList(state, modifier)
    }
    state.intent?.let { folder -> CloneIntentDialog(state, folder) }
}

@Composable
private fun CloneForm(state: CloneState, modifier: Modifier) {
    // Debounced by the effect itself: a keystroke cancels the pending one, so only a URL that has
    // been still for a moment is ever fetched, and a superseded fetch is cancelled rather than raced.
    LaunchedEffect(state.url) {
        delay(PeekDelayMs)
        state.loadPeek(state.url)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        item {
            PageHeader(
                icon = rememberVectorPainter(ScmIcons.GitHub),
                title = "Clone a repository",
                subtitle = "Clone a Git repository into a new project",
            )
        }
        item {
            Card(title = "") {
                FieldLabel("Repository URL")
                CompactField(
                    value = state.url,
                    onValueChange = state::editUrl,
                    placeholder = "https://github.com/owner/repo.git",
                    literal = true,
                )
                FieldLabel("Folder name")
                CompactField(
                    value = state.name,
                    onValueChange = state::editName,
                    placeholder = "(optional — taken from the URL)",
                    literal = true,
                )
                Muted(state.destination)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    CompactFilledButton(
                        text = "Clone",
                        onClick = { state.startClone() },
                        enabled = !state.busy,
                    )
                    if (state.canGoBack) {
                        CompactOutlinedButton(
                            text = "Cancel",
                            onClick = { state.backToList() },
                            enabled = !state.busy,
                        )
                    }
                    state.message?.let { StatusText(it, state.failed) }
                }
                state.log?.let { LogBlock(it) }
            }
        }
        if (state.peeking || state.peek.isNotEmpty()) {
            item {
                Card(title = "Latest commits") {
                    if (state.peek.isEmpty()) {
                        Muted("Looking…")
                    } else {
                        state.peek.forEachIndexed { i, c ->
                            if (i > 0) RowDivider()
                            PeekRow(c)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeekRow(commit: Commit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = commit.hash,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = commit.subject,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Muted("${commit.author} · ${commit.relative}")
    }
}

/**
 * How the repository should open, asked before a byte is downloaded.
 *
 * Three ways out rather than a yes and a no, because there is no default that is right often enough
 * to pick silently — and cancelling here has cost nothing yet.
 */
@Composable
private fun CloneIntentDialog(state: CloneState, folder: String) {
    AlertDialog(
        onDismissRequest = { state.cancelIntent() },
        title = { Text("Clone '$folder'") },
        text = {
            Muted(
                "Add $folder as a project, or open it as a workspace — its top-level folders " +
                    "become projects. A repository that ships its own .jcode keeps what it declares.",
            )
        },
        confirmButton = {
            CompactFilledButton(text = "Add as Project", onClick = { state.confirmIntent("project") })
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                CompactOutlinedButton(text = "Cancel", onClick = { state.cancelIntent() })
                CompactOutlinedButton(
                    text = "Open as Workspace",
                    onClick = { state.confirmIntent("workspace") },
                )
            }
        },
    )
}

@Composable
private fun RemoteList(state: CloneState, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        item {
            PageHeader(
                icon = rememberVectorPainter(ScmIcons.GitHub),
                title = "Remote repositories",
                subtitle = "Clone one of your GitHub repositories",
            )
        }
        when {
            state.loadingRepos -> item { Note("Loading your repositories…", spinner = true) }

            state.githubUser.isEmpty() -> item {
                Card(title = "Connect to GitHub") {
                    Muted("Connect to browse and clone your repositories.")
                    CompactFilledButton(text = "Connect", onClick = { state.openConnect() })
                }
            }

            state.reposError != null -> item {
                Card(title = "") {
                    StatusText(state.reposError.orEmpty(), isError = true)
                    state.reposLog?.let { LogBlock(it) }
                    CompactOutlinedButton(text = "Retry", onClick = { state.loadRepos() })
                }
            }

            state.repos.isEmpty() -> item {
                Card(title = "") { Muted("No repositories found for @${state.githubUser}.") }
            }

            else -> {
                if (state.owners.size > 1) item { OwnerTabs(state) }
                items(state.reposFor(state.owner), key = { it.owner + "/" + it.name }) { repo ->
                    RepoRow(state, repo)
                }
            }
        }
    }
}

/** One tab per account the listing covers; absent when everything belongs to one. */
@Composable
private fun OwnerTabs(state: CloneState) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        state.owners.forEach { login ->
            val selected = login == state.owner
            Surface(
                shape = RoundedCornerShape(Radius.pill),
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable { state.owner = login }
                    .handCursor(),
            ) {
                Text(
                    text = if (login == state.githubUser) "You" else login,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = Space.md, vertical = Space.s),
                )
            }
        }
    }
}

@Composable
private fun RepoRow(state: CloneState, repo: RemoteRepo) {
    Card(title = "", modifier = Modifier.clickable { state.prepare(repo) }.handCursor()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = repo.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (repo.private) PrivateTag()
        }
        if (repo.description.isNotEmpty()) Muted(repo.description)
    }
}

@Composable
private fun PrivateTag() {
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "private",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.xxs),
        )
    }
}

/** Long enough that typing a URL does not fetch a prefix of it, short enough not to feel stuck. */
private const val PeekDelayMs = 700L
