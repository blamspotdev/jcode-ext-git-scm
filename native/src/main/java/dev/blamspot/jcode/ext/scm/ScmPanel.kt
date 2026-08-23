package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The Source Control drawer panel.
 *
 * Laid out the way the web version was, because that layout was arrived at on a phone: the branch
 * and repository on one compact row, the commit box directly under it where the thumb is, and the
 * file sections below taking whatever height is left.
 */
@Composable
internal fun ScmPanel(state: ScmState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        HeaderRow(state)

        when {
            state.booting -> CenteredNote("Looking for a repository…", spinner = true)
            state.repos.isEmpty() -> CenteredNote(
                "No git repository here.\nOpen a project that is one, or run git init.",
            )
            state.error != null -> RepoError(state)
            else -> RepoBody(state)
        }
    }

    state.log?.let { text ->
        LogDialog(text) { state.log = null }
    }
}

@Composable
private fun HeaderRow(state: ScmState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = state.repo?.name ?: "Source Control",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.repo != null) {
            Text(
                text = state.branch,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.ahead > 0 || state.behind > 0) {
                Text(
                    text = "↓${state.behind} ↑${state.ahead}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.weight(1f))
        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        IconButton(onClick = { state.boot() }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RepoError(state: ScmState) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Couldn't read this repository.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = state.error.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { state.boot() }) { Text("Refresh") }
    }
}

@Composable
private fun RepoBody(state: ScmState) {
    Column(modifier = Modifier.fillMaxSize()) {
        CommitBox(state)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.conflicts.isNotEmpty()) {
                item { SectionHeader("Conflicts", state.conflicts.size, null) }
                items(state.conflicts) { entry -> FileRow(state, entry, staged = false, conflict = true) }
            }
            if (state.staged.isNotEmpty()) {
                item {
                    SectionHeader("Staged", state.staged.size, "Unstage all") { state.unstageAll() }
                }
                items(state.staged) { entry -> FileRow(state, entry, staged = true) }
            }
            item {
                SectionHeader("Changes", state.unstaged.size, "Stage all") { state.stageAll() }
            }
            if (state.unstaged.isEmpty() && state.staged.isEmpty() && state.conflicts.isEmpty()) {
                item {
                    Text(
                        "Nothing to commit — the working tree is clean.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.unstaged) { entry -> FileRow(state, entry, staged = false) }
        }
    }
}

@Composable
private fun CommitBox(state: ScmState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = state.commitMessage,
            onValueChange = { state.commitMessage = it },
            placeholder = { Text("Message", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            CompactAction("Commit", enabled = state.canCommit) { state.commit() }
            CompactAction("Pull", enabled = !state.busy) { state.pull() }
            CompactAction("Push", enabled = !state.busy) { state.push() }
        }
    }
}

@Composable
private fun CompactAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, actionLabel: String?, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "  $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null && count > 0) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Status letters get the colours the Explorer badges use, so one file reads the same in both. */
@Composable
private fun statusColor(code: String): Color = when (code) {
    "M" -> MaterialTheme.colorScheme.tertiary
    "A", "?" -> MaterialTheme.colorScheme.primary
    "D" -> MaterialTheme.colorScheme.error
    "!" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun FileRow(state: ScmState, entry: FileEntry, staged: Boolean, conflict: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { state.openFile(entry) }
            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = entry.code,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = statusColor(entry.code),
            modifier = Modifier.size(width = 12.dp, height = 16.dp),
        )
        Text(
            text = entry.display,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!conflict) {
            if (staged) {
                RowAction(Icons.Filled.Remove, "Unstage") { state.unstage(entry) }
            } else {
                RowAction(Icons.Filled.Close, "Discard") { state.discard(entry) }
                RowAction(Icons.Filled.Add, "Stage") { state.stage(entry) }
            }
        }
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(26.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun CenteredNote(text: String, spinner: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (spinner) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Multi-line git output, in a dismissible sheet rather than inline.
 *
 * A push or a failed merge writes several lines, and letting that grow inline shoved the file lists
 * around under the user's thumb.
 */
@Composable
private fun LogDialog(text: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
