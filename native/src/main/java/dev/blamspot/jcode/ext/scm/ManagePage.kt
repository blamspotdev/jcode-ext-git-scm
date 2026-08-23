package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.handCursor
import dev.blamspot.jcode.design.jcIcon
import kotlinx.coroutines.launch

/**
 * Branches and history, full width in the editor area.
 *
 * The drawer panel is a column a few hundred pixels wide and everything in it is compressed to fit;
 * this is a page, so a branch can have its name, where it tracks, and its three actions on one line
 * without any of them being an icon you have to recognise.
 */
@Composable
internal fun ManagePage(state: ManageState, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Asking a branch what it carries points the history card at it; the card is at the bottom of
    // the page, so the answer has to be brought to the question rather than left to be found.
    LaunchedEffect(state.historyRequest) {
        if (state.historyRequest > 0) scope.launch { list.animateScrollToItem(HistoryItem) }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
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
        action = {
            IconAction(
                icon = jcIcon(JCodeIcon.Add),
                label = "New branch",
                enabled = !state.busy,
            ) { state.promptCreate() }
        },
    ) {
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
            else -> state.remote.forEachIndexed { i, b ->
                if (i > 0) RowDivider()
                RemoteBranchRow(state, b)
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
        incoming = branch.incoming,
        outgoing = branch.outgoing,
    ) {
        // Ordered by consequence: switching to it, then bringing it up to date, then sending it,
        // then renaming, and deleting last where a mis-tap is least likely to land.
        BranchMenu(state) { dismiss ->
            PopoverItem("Show $RECENT_COMMITS recent commits", ScmIcons.List, enabled = !state.busy) {
                dismiss(); state.showRecent(branch.name)
            }
            PopoverDivider()
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
private fun RemoteBranchRow(state: ManageState, branch: RemoteBranch) {
    val name = branch.name
    val short = state.localNameOf(name)
    BranchRow(
        name = name,
        current = short == state.branch,
        upstream = "",
        incoming = branch.incoming,
        outgoing = branch.outgoing,
    ) {
        BranchMenu(state) { dismiss ->
            PopoverItem("Show $RECENT_COMMITS recent commits", ScmIcons.List, enabled = !state.busy) {
                dismiss(); state.showRecent(name)
            }
            PopoverDivider()
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
    incoming: Int,
    outgoing: Int,
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
        Drift(ScmIcons.Pull, incoming)
        Drift(ScmIcons.Push, outgoing)
        Box(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) { actions() }
    }
}

/** Long enough for a real branch name, short enough to leave the actions their room. */
private val BranchNameMaxWidth = 320.dp

@Composable
private fun CommitsCard(state: ManageState) {
    val other = state.historyBranch.isNotEmpty()
    Card(
        title = "Commits",
        action = {
            if (other) {
                IconAction(
                    icon = jcIcon(JCodeIcon.Close),
                    label = "Back to " + state.branch,
                ) { state.showCurrentHistory() }
            }
        },
    ) {
        Text(
            // Named, and when it is a sample of another branch it says so: a card that showed ten
            // of somebody's commits under a bare branch name would read as the whole of it.
            text = state.historyBranch.ifBlank { state.branch }.ifBlank { "HEAD" } +
                if (other) " · last $RECENT_COMMITS" else "",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (other) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            state.historyError != null -> Text(
                text = state.historyError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            state.commits.isEmpty() -> Text(
                "No commits yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> CommitGroup.entries.forEach { group ->
                val rows = state.commits.filter { it.group == group }
                if (rows.isEmpty()) return@forEach
                GroupHeader(group, rows.size)
                rows.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    CommitRow(c) { state.openCommit(c) }
                }
            }
        }
    }
}

/**
 * Which side of the upstream the commits under it are on.
 *
 * The branch row says "2 incoming, 2 outgoing"; this says which two. Shared history gets a heading
 * too, so the list never leaves you guessing whether an unlabelled run is settled or just unlabelled.
 */
@Composable
private fun GroupHeader(group: CommitGroup, count: Int) {
    val label = when (group) {
        CommitGroup.Incoming -> "Incoming"
        CommitGroup.Outgoing -> "Outgoing · not pushed"
        CommitGroup.Shared -> "History"
    }
    val tint = when (group) {
        CommitGroup.Incoming -> MaterialTheme.colorScheme.primary
        CommitGroup.Outgoing -> JCodeTheme.semanticColors.warning
        CommitGroup.Shared -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        when (group) {
            CommitGroup.Incoming -> Icon(ScmIcons.Pull, null, tint = tint, modifier = Modifier.size(IconSize.xs))
            CommitGroup.Outgoing -> Icon(ScmIcons.Push, null, tint = tint, modifier = Modifier.size(IconSize.xs))
            CommitGroup.Shared -> Unit
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
        // Only where it adds something: "History" is however much of it was read, not a total.
        if (group != CommitGroup.Shared) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

/**
 * Where the history card sits in the list: header, branches, commits.
 *
 * A literal because a lazy list is addressed by index and there is nothing else to address it by.
 * It only has to hold while the page has these three items, which is what the `when` above builds.
 */
private const val HistoryItem = 2

@Composable
private fun CommitRow(commit: Commit, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .handCursor()
            .padding(vertical = Space.xs),
    ) {
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

/**
 * How many commits a branch is behind or ahead of what it is measured against.
 *
 * Absent at zero rather than shown as "0": a row that says nothing is level, and a list of zeroes
 * is a list you have to read to learn nothing.
 */
@Composable
private fun Drift(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
    if (count <= 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.xs),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

