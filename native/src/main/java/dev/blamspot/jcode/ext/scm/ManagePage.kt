package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon

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
            item {
                PageHeader(
                    icon = ScmIcons.Branch,
                    title = "Source Control",
                    subtitle = "Manage branches and view history",
                ) {
                    if (state.repo != null) {
                        IconAction(
                            icon = ScmIcons.Fetch,
                            label = "Fetch",
                            enabled = !state.busy,
                        ) { state.fetch() }
                    }
                }
            }

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
private fun BranchesCard(state: ManageState) {
    Card(
        title = "Branches",
        trailing = {
            SegmentedToggle(
                options = BranchTab.entries.toList(),
                selected = state.tab,
                label = { it.name },
                onSelect = { state.tab = it },
            )
        },
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
private fun LocalBranchRow(state: ManageState, branch: LocalBranch) {
    BranchRow(
        name = branch.name,
        current = branch.current,
        upstream = branch.upstream,
    ) {
        // Ordered by consequence: switching to it, then bringing it up to date, then sending it,
        // then renaming, and deleting last where a mis-tap is least likely to land.
        BranchMenu(state) { dismiss ->
            if (!branch.current) {
                PopoverItem("Checkout", ScmIcons.Branch, enabled = !state.busy) {
                    dismiss(); state.checkout(branch.name)
                }
                PopoverItem("Merge to current branch", ScmIcons.Tree, enabled = !state.busy) {
                    dismiss(); state.promptMerge(branch.name)
                }
            }
            // Nothing to pull from without an upstream, so it is left out rather than offered and
            // then failing. The current branch always has somewhere to pull from: its own tracking.
            if (branch.current || branch.upstream.isNotEmpty()) {
                PopoverItem("Pull", ScmIcons.Pull, enabled = !state.busy) {
                    dismiss(); state.pull(branch)
                }
            }
            PopoverItem("Push", ScmIcons.Push, enabled = !state.busy) {
                dismiss(); state.push(branch)
            }
            PopoverDivider()
            PopoverItem("Rename…", jcIcon(JCodeIcon.Rename), enabled = !state.busy) {
                dismiss(); state.promptRename(branch.name)
            }
            if (!branch.current) {
                PopoverItem("Delete…", jcIcon(JCodeIcon.Delete), enabled = !state.busy) {
                    dismiss(); state.promptDelete(branch.name)
                }
            }
        }
    }
}

/**
 * A branch on the remote: check it out, or merge it in.
 *
 * Nothing else. Deleting a remote branch is destructive, easy to hit by accident on a touch screen,
 * and not undoable from here; renaming and pushing belong to the local branch that tracks it.
 */
@Composable
private fun RemoteBranchRow(state: ManageState, name: String) {
    val short = state.localNameOf(name)
    BranchRow(name = name, current = short == state.branch, upstream = "") {
        BranchMenu(state) { dismiss ->
            PopoverItem("Checkout", ScmIcons.Branch, enabled = !state.busy) {
                dismiss(); state.checkout(short)
            }
            PopoverItem("Merge to current branch", ScmIcons.Tree, enabled = !state.busy) {
                dismiss(); state.promptMerge(name)
            }
        }
    }
}

/** The row's actions, behind one glyph. */
@Composable
private fun BranchMenu(state: ManageState, content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PopoverAnchor(
        expanded = open,
        onDismiss = { open = false },
        alignEnd = true,
        anchor = {
            IconAction(
                icon = jcIcon(JCodeIcon.MoreVert),
                label = "Branch actions",
                enabled = !state.busy,
            ) { open = true }
        },
    ) {
        content { open = false }
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
