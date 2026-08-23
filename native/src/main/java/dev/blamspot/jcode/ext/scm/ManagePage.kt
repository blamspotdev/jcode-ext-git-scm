package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor

/**
 * Branches and history, full width in the editor area.
 *
 * The drawer panel is a column a few hundred pixels wide and everything in it is compressed to fit;
 * this is a page, so a branch can have its name, where it tracks, and its three actions on one line
 * without any of them being an icon you have to recognise.
 */
@Composable
internal fun ManagePage(state: ManageState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            item { PageHeader(state) }

            when {
                state.booting -> item { Note("Looking for a repository…", spinner = true) }
                state.repo == null -> item { Note("This project isn't a git repository.") }
                else -> {
                    item { BranchesCard(state) }
                    item { CommitsCard(state) }
                }
            }
        }
    }
    state.confirm?.let { c -> ConfirmDialog(c) { state.confirm = null } }
}

@Composable
private fun PageHeader(state: ManageState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            imageVector = ScmIcons.Branch,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(IconSize.xl),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Source Control",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Manage branches and view history",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.repo != null) {
            CompactOutlinedButton(
                text = "Fetch",
                onClick = { state.fetch() },
                enabled = !state.busy,
            )
        }
    }
}

/** A titled slab. The page is a stack of these, the way the settings screens are. */
@Composable
private fun Card(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScopeAlias.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.xxl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun BranchesCard(state: ManageState) {
    Card(
        title = "Branches",
        trailing = { TabToggle(state) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                CompactField(
                    value = state.newBranch,
                    onValueChange = { state.newBranch = it },
                    placeholder = "new-branch-name",
                )
            }
            CompactFilledButton(
                text = "Create",
                onClick = { state.create() },
                enabled = !state.busy,
                busy = state.busy,
            )
        }
        state.message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.messageIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            state.tab == BranchTab.Local && state.local.isEmpty() ->
                Text(
                    "No local branches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            state.tab == BranchTab.Remote && state.remote.isEmpty() ->
                Text(
                    "No remote branches — Fetch to update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            state.tab == BranchTab.Local -> state.local.forEachIndexed { i, b ->
                if (i > 0) RowDivider()
                LocalBranchRow(state, b)
            }
            else -> state.remote.forEachIndexed { i, name ->
                if (i > 0) RowDivider()
                RemoteBranchRow(state, name)
            }
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = StrokeWidth.hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** Local / Remote, as a two-segment switch rather than two buttons that both look pressable. */
@Composable
private fun TabToggle(state: ManageState) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row {
            BranchTab.entries.forEach { tab ->
                val selected = state.tab == tab
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable { state.tab = tab }
                        .handCursor(),
                ) {
                    Text(
                        text = tab.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = Space.md, vertical = Space.s),
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalBranchRow(state: ManageState, branch: LocalBranch) {
    BranchRow(
        name = branch.name,
        current = branch.current,
        upstream = branch.upstream,
    ) {
        if (!branch.current) {
            CompactOutlinedButton(text = "Checkout", onClick = { state.checkout(branch.name) }, enabled = !state.busy)
        }
        CompactOutlinedButton(text = "Rename", onClick = { state.promptRename(branch.name) }, enabled = !state.busy)
        if (!branch.current) {
            dev.blamspot.jcode.design.CompactDestructiveButton(
                text = "Delete",
                onClick = { state.promptDelete(branch.name) },
                enabled = !state.busy,
            )
        }
    }
}

/**
 * A branch on the remote, offered for checkout only.
 *
 * Deleting a remote branch is destructive, easy to hit by accident on a touch screen, and not
 * undoable from here — so it is deliberately not on offer.
 */
@Composable
private fun RemoteBranchRow(state: ManageState, name: String) {
    val short = state.localNameOf(name)
    BranchRow(name = name, current = short == state.branch, upstream = "") {
        CompactOutlinedButton(text = "Checkout", onClick = { state.checkout(short) }, enabled = !state.busy)
    }
}

@Composable
private fun BranchRow(
    name: String,
    current: Boolean,
    upstream: String,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = BranchNameMaxWidth),
        )
        if (upstream.isNotEmpty()) {
            Text(
                text = upstream,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            Surface(
                shape = RoundedCornerShape(Radius.pill),
                color = JCodeTheme.semanticColors.success.copy(alpha = 0.18f),
            ) {
                Text(
                    text = "current",
                    style = MaterialTheme.typography.labelSmall,
                    color = JCodeTheme.semanticColors.success,
                    modifier = Modifier.padding(horizontal = Space.s, vertical = Space.xxs),
                )
            }
        }
        Box(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) { actions() }
    }
}

/** Long enough for a real branch name, short enough to leave the actions their room. */
private val BranchNameMaxWidth = 320.dp

@Composable
private fun CommitsCard(state: ManageState) {
    Card(title = "Commits") {
        Text(
            text = state.branch.ifBlank { "HEAD" },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.commits.isEmpty()) {
            Text(
                "No commits yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.commits.forEachIndexed { i, c ->
                if (i > 0) RowDivider()
                CommitRow(c)
            }
        }
    }
}

@Composable
private fun CommitRow(commit: Commit) {
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
        Text(
            text = commit.author + " · " + commit.relative,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
