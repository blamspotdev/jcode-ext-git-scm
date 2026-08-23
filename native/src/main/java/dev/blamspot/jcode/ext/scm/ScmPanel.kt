package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ControlSize
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.ManagerGroupHeader
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor
import dev.blamspot.jcode.design.jcIcon

/**
 * The Source Control drawer panel.
 *
 * Drawn out of JCode's own parts — its spacing scale, its compact buttons, its icon bundle, and the
 * same semantic colours the Explorer badges use — so a file reads identically in the tree and here,
 * and the panel changes shape when the app's density does rather than holding its own opinion.
 */
@Composable
internal fun ScmPanel(state: ScmState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        PanelHeader(state)
        when {
            state.booting -> CenteredNote("Looking for a repository…", spinner = true)
            state.repos.isEmpty() -> CenteredNote(
                text = "No git repository here.",
                detail = state.projectPath?.let { "Start tracking ${Git.baseName(it)} with git." }
                    ?: "Open a project to use Source Control.",
                action = state.projectPath?.let {
                    {
                        CompactFilledButton(
                            text = "Initialize Repository",
                            onClick = { state.initRepo() },
                            enabled = !state.busy,
                            busy = state.busy,
                        )
                    }
                },
            )
            state.error != null -> RepoError(state)
            else -> RepoBody(state)
        }
    }
    state.log?.let { text -> LogSheet(text) { state.log = null } }
}

/**
 * Title, repository and branch.
 *
 * Two lines rather than one: the drawer is narrow, and a repository name beside a branch name beside
 * an ahead/behind count is three things competing for the same forty characters.
 */
@Composable
private fun PanelHeader(state: ScmState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.ms, vertical = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Source Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.busy) {
                Box(modifier = Modifier.size(ControlSize.iconButtonSm), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(IconSize.sm), strokeWidth = StrokeWidth.thick)
                }
            } else {
                HeaderIcon(JCodeIcon.Refresh, "Refresh") { state.boot() }
            }
        }
        state.repo?.let { repo ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier.padding(top = Space.xxs),
            ) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.Scm),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.xs),
                )
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.branch,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.ahead > 0 || state.behind > 0) {
                    Text(
                        text = "↓${state.behind}  ↑${state.ahead}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun HeaderIcon(icon: JCodeIcon, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(ControlSize.iconButtonSm).handCursor()) {
        Icon(
            imageVector = jcIcon(icon),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.sm),
        )
    }
}

@Composable
private fun RepoBody(state: ScmState) {
    Column(modifier = Modifier.fillMaxSize()) {
        CommitBox(state)
        HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
            if (state.conflicts.isNotEmpty()) {
                item { SectionHeader("Conflicts", state.conflicts.size) }
                items(state.conflicts) { FileRow(state, it, Section.Conflict) }
            }
            if (state.staged.isNotEmpty()) {
                item {
                    SectionHeader("Staged", state.staged.size, "Unstage all") { state.unstageAll() }
                }
                items(state.staged) { FileRow(state, it, Section.Staged) }
            }
            item {
                SectionHeader("Changes", state.unstaged.size, "Stage all".takeIf { state.unstaged.isNotEmpty() }) {
                    state.stageAll()
                }
            }
            items(state.unstaged) { FileRow(state, it, Section.Unstaged) }
            if (state.unstaged.isEmpty() && state.staged.isEmpty() && state.conflicts.isEmpty()) {
                item {
                    Text(
                        text = "Nothing to commit — the working tree is clean.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.sm),
                    )
                }
            }
            item { Box(modifier = Modifier.size(Space.md)) }
        }
    }
}

@Composable
private fun CommitBox(state: ScmState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.ms, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        OutlinedTextField(
            value = state.commitMessage,
            onValueChange = { state.commitMessage = it },
            placeholder = {
                Text("Message", style = MaterialTheme.typography.bodySmall)
            },
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(Radius.lg),
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().heightIn(min = ControlSize.touchTarget),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            CompactFilledButton(text = "Commit", onClick = { state.commit() }, enabled = state.canCommit)
            CompactOutlinedButton(text = "Pull", onClick = { state.pull() }, enabled = !state.busy)
            CompactOutlinedButton(text = "Push", onClick = { state.push() }, enabled = !state.busy)
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    ManagerGroupHeader(
        title = "$title  $count",
        trailing = {
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.handCursor()) {
                    Text(actionLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
    )
}

private enum class Section { Staged, Unstaged, Conflict }

/**
 * The status letter, in the colour the Explorer badges it with.
 *
 * Deliberately the same mapping rather than a similar one: a modified file is the same amber in the
 * tree and in this list, so the two views agree at a glance instead of needing to be read.
 */
@Composable
private fun statusColor(code: String): Color = when (code) {
    "M" -> JCodeTheme.semanticColors.warning
    "A", "?" -> JCodeTheme.semanticColors.success
    "D", "!" -> MaterialTheme.colorScheme.error
    "R", "C" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun FileRow(state: ScmState, entry: FileEntry, section: Section) {
    val tint = statusColor(entry.code)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { state.openFile(entry) }
            .handCursor()
            .padding(start = Space.xs, end = Space.xxs, top = Space.xxs, bottom = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        StatusBadge(entry.code, tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.display.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The folder under the name rather than beside it: a narrow drawer truncates a long path
            // into uselessness, and the filename is what identifies the row.
            entry.display.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { dir ->
                Text(
                    text = dir,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (section) {
            Section.Staged -> RowAction(JCodeIcon.Minus, "Unstage") { state.unstage(entry) }
            Section.Unstaged -> {
                RowAction(JCodeIcon.Discard, "Discard", MaterialTheme.colorScheme.error) { state.discard(entry) }
                RowAction(JCodeIcon.Add, "Stage") { state.stage(entry) }
            }
            // A conflict is resolved in the editor, not by a button here.
            Section.Conflict -> Unit
        }
    }
}

/** The status letter as a small tinted square, the way the Explorer draws it. */
@Composable
private fun StatusBadge(code: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(Radius.xs),
        color = tint.copy(alpha = 0.16f),
        modifier = Modifier.size(width = IconSize.sm, height = IconSize.sm),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = code,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }
    }
}

@Composable
private fun RowAction(
    icon: JCodeIcon,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(ControlSize.iconButtonSm).handCursor()) {
        Icon(
            imageVector = jcIcon(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(IconSize.xs),
        )
    }
}

@Composable
private fun RepoError(state: ScmState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Space.ms),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = "Couldn't read this repository.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
        )
        Surface(
            shape = RoundedCornerShape(Radius.md),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Text(
                text = state.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Space.sm),
            )
        }
        CompactOutlinedButton(text = "Refresh", onClick = { state.boot() }, icon = JCodeIcon.Refresh)
    }
}

@Composable
private fun CenteredNote(
    text: String,
    detail: String? = null,
    spinner: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize().padding(Space.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (spinner) {
                CircularProgressIndicator(modifier = Modifier.size(IconSize.md), strokeWidth = StrokeWidth.thick)
            } else {
                Surface(
                    shape = RoundedCornerShape(Radius.xxl),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Scm),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(Space.md).size(IconSize.xl),
                    )
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.let {
                Box(modifier = Modifier.padding(top = Space.xs)) { it() }
            }
        }
    }
}

/**
 * Multi-line git output, over the panel rather than inside it.
 *
 * A push or a failed merge writes several lines, and letting that grow inline shoved the file lists
 * around under the user's thumb.
 */
@Composable
private fun LogSheet(text: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.xxl),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(Space.md),
        ) {
            Column(
                modifier = Modifier.padding(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    CompactOutlinedButton(
                        text = "Close",
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}
