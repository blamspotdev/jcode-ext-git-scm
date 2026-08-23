package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor
import dev.blamspot.jcode.design.jcIcon

/**
 * One commit: what it touched, and what it looks like from either side.
 *
 * Files open one at a time rather than every patch being laid out under the list. The commit that
 * scaffolds a project touches every file in it, and burying the one you came for under seventy
 * others is the same as not showing it.
 */
@Composable
internal fun CommitPage(state: CommitState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= CommitSplitMinWidth
        LaunchedEffect(wide) { state.layout = if (wide) DiffLayout.Split else DiffLayout.Inline }
        Column(modifier = Modifier.fillMaxSize()) {
            CommitHeader(state, wide)
            HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            when {
                state.loading -> Note("Reading the commit…", spinner = true)
                state.error != null -> Note(state.error.orEmpty())
                state.files.isEmpty() -> Note("Nothing differs.")
                else -> FileList(state)
            }
        }
    }
}

@Composable
private fun CommitHeader(state: CommitState, wide: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = state.shortHash,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = state.subject,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            // Says which question is on screen, and how much of an answer it has.
            Muted(
                listOfNotNull(
                    state.author.takeIf { it.isNotBlank() },
                    state.relative.takeIf { it.isNotBlank() },
                    "${state.files.size} file" + if (state.files.size == 1) "" else "s",
                ).joinToString(" · "),
            )
            Box(modifier = Modifier.weight(1f))
            SegmentedToggle(
                options = CommitCompare.entries.toList(),
                selected = state.compare,
                label = { it.label },
                onSelect = { state.choose(it) },
            )
            if (wide) {
                SegmentedToggle(
                    options = DiffLayout.entries.toList(),
                    selected = state.layout,
                    label = { it.name },
                    onSelect = { state.layout = it },
                )
            }
            ToggleChip(label = "Tree", on = state.tree) { state.tree = !state.tree }
            ToggleChip(label = "Wrap", on = state.wrap) { state.wrap = !state.wrap }
        }
    }
}

@Composable
private fun FileList(state: CommitState) {
    // Flat or foldered, from the same list — the tree is a way of reading it, not a different set.
    val rows = if (state.tree) {
        buildTreeRows(state.files, state.collapsedFolders.toSet()) { it.path }
    } else {
        state.files.map { TreeRow.File(it, 0) }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows.size) { i ->
            when (val row = rows[i]) {
                is TreeRow.Folder -> FolderRow(row) { state.toggleFolder(row.key) }
                is TreeRow.File -> {
                    FileSection(state, row.item, row.depth)
                    HorizontalDivider(
                        thickness = StrokeWidth.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
        item { Box(modifier = Modifier.size(Space.xxl)) }
    }
}

@Composable
private fun FolderRow(row: TreeRow.Folder<*>, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .handCursor()
            .padding(
                start = Space.lg + Indent * row.depth,
                end = Space.lg,
                top = Space.xs,
                bottom = Space.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            imageVector = jcIcon(if (row.collapsed) JCodeIcon.ChevronRight else JCodeIcon.ChevronDown),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.sm),
        )
        Icon(
            imageVector = jcIcon(JCodeIcon.Folder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.sm),
        )
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One file: a row, and its patch under it once opened.
 *
 * Rows are independent rather than an accordion, so two files can be compared against each other
 * without one closing the moment the next opens.
 */
@Composable
private fun FileSection(state: CommitState, file: CommitFile, depth: Int) {
    val open = file.path in state.opened
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { state.toggle(file.path) }
            .handCursor()
            .padding(
                start = Space.lg + Indent * depth,
                end = Space.lg,
                top = Space.sm,
                bottom = Space.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = jcIcon(if (open) JCodeIcon.ChevronDown else JCodeIcon.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.sm),
        )
        StatusLetter(file.status)
        Text(
            // In a tree the folder rows already carry the path, so the leaf is just its name.
            text = if (state.tree) Git.baseName(file.path) else file.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    if (!open) return
    val rows = state.rowsFor(file.path)
    when {
        rows == null || state.isLoading(file.path) -> Note("Loading…", spinner = true)
        rows.isEmpty() -> Note("No text changes — the file is binary, or only its mode changed.")
        else -> Patch(state, rows)
    }
}

/**
 * A file's patch, capped in height.
 *
 * An opened file scrolls inside its own box rather than pushing every row below it off the screen —
 * the list is the thing being navigated, and a patch that displaces it has taken the page over.
 */
@Composable
private fun Patch(state: CommitState, rows: List<AlignedRow>) {
    val horizontal = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.xs),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = PatchMaxHeight)) {
            items(rows.size) { i ->
                // Not click-to-open, unlike the working-tree diff: this is a historical revision, so
                // that line number in the file as it stands now points somewhere unrelated.
                if (state.layout == DiffLayout.Split) {
                    SplitRow(rows[i], state.wrap, horizontal, onOpen = null)
                } else {
                    InlineRows(rows[i], state.wrap, horizontal, onOpen = null)
                }
            }
        }
    }
}

/** One step of nesting, matching the drawer's changed-files tree. */
private val Indent = 14.dp

/** Enough to read a hunk without the file list disappearing above it. */
private val PatchMaxHeight = 420.dp

/** Below this a split view is two columns of ellipsis rather than a comparison. */
private val CommitSplitMinWidth = 640.dp
