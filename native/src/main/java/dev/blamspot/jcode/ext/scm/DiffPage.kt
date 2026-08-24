package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ControlSize
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor
import dev.blamspot.jcode.design.jcIcon
import kotlinx.coroutines.launch

/**
 * Two versions of a file, side by side.
 *
 * A diff is a comparison, so the page is built to answer comparison questions: which version is on
 * which side, which *word* in a line changed, where a deleted line used to be, how much of the file
 * nobody touched, and — once you have read a hunk — what to do about it. git's own output answers
 * none of those directly, which is why it is parsed rather than printed.
 *
 * Splits when there is width for two columns and falls back to one when there is not. Serves a
 * stash's patch too; a stash is not in the working tree, so its rows open no file and none of the
 * actions apply.
 */
@Composable
internal fun DiffPage(state: DiffState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Two columns of monospace need real width; below that a split is two columns of ellipsis.
        // This only seeds the default — the toggle is the user's once they touch it.
        val wide = maxWidth >= SplitMinWidth
        LaunchedEffect(wide) { state.layout = if (wide) DiffLayout.Split else DiffLayout.Inline }
        Column(modifier = Modifier.fillMaxSize()) {
            DiffHeader(state, wide)
            HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            state.message?.let {
                Box(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)) {
                    StatusText(it, state.messageIsError)
                }
                HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            }
            when {
                state.loading -> Note("Loading diff…", spinner = true)
                state.error != null -> Note(state.error.orEmpty())
                state.items.isEmpty() -> Note("No differences to show.")
                else -> DiffBody(state)
            }
        }
    }
    state.confirm?.let { c -> ConfirmDialog(c) { state.confirm = null } }
}

@Composable
private fun DiffHeader(state: DiffState, wide: Boolean) {
    // One row, not two. The file is the subject and sits left; everything else is chrome and packs
    // to the right. Split across two rows the same controls put one lonely chip at the far left and
    // two at the far right with a chasm between, and spent twice the height saying it.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false so the counts stay beside the name rather than being pushed to the
                // far side of the row by a path with room to spare.
                modifier = Modifier.weight(1f, fill = false),
            )
            Counts(state)
        }
        // A file git has never seen has nothing to compare against: every other option resolves to
        // an empty diff, so the picker would offer only dead ends.
        if (state.openable && state.compare != Compare.Untracked) ComparePicker(state)
        else Muted(state.subtitle)
        if (wide) {
            SegmentedToggle(
                options = DiffLayout.entries.toList(),
                selected = state.layout,
                label = { it.name },
                onSelect = { state.layout = it },
            )
        }
        ToggleChip(label = "Wrap", on = state.wrap) { state.wrap = !state.wrap }
        if (state.openable) {
            CompactOutlinedButton(
                text = "Open file",
                onClick = { state.openAt(state.firstChangedLine) },
            )
        }
    }
}

/** How much changed, in the two colours the rows themselves use. */
@Composable
private fun Counts(state: DiffState) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (state.added > 0) {
            Text(
                text = "+${state.added}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = JCodeTheme.semanticColors.success,
            )
        }
        if (state.removed > 0) {
            Text(
                text = "-${state.removed}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}


/**
 * What the file is being compared against.
 *
 * The named comparisons first — they are what the question usually is — then every branch, because
 * "what has changed since `main`?" is the other half of it.
 */
@Composable
private fun ComparePicker(state: DiffState) {
    PopoverAnchor(
        expanded = state.showPicker,
        onDismiss = { state.showPicker = false },
        anchor = {
            ActionChip(label = state.compare.label, caret = true) {
                state.showPicker = !state.showPicker
            }
        },
    ) {
        listOf(Compare.WorkingVsIndex, Compare.IndexVsHead, Compare.WorkingVsHead).forEach { option ->
            PopoverItem(
                label = option.label,
                selected = state.compare == option,
                onClick = { state.choose(option) },
            )
        }
        if (state.refs.isNotEmpty()) {
            PopoverDivider()
            PopoverLabel("Compare with branch")
            state.refs.forEach { ref ->
                PopoverItem(
                    label = ref,
                    selected = (state.compare as? Compare.Ref)?.name == ref,
                    onClick = { state.choose(Compare.Ref(ref)) },
                )
            }
        }
    }
}

@Composable
private fun DiffBody(state: DiffState) {
    // One horizontal scroll for the whole body: a diff's rows must move together or the columns
    // stop lining up, which is the one thing a diff has to get right. Wrapping retires it.
    val horizontal = rememberScrollState()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var current by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.generation, state.layout) {
        current = 0
        list.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.files.isNotEmpty()) StashFileList(state)
        if (state.hunks.size > 1) {
            HunkBar(current, state.hunks.size) { next ->
                current = next
                val at = state.items.indexOfFirst { it is DiffItem.HunkStart && it.index == next }
                if (at >= 0) scope.launch { list.animateScrollToItem(at) }
            }
        }
        if (state.layout == DiffLayout.Split) ColumnTitles(state)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(state.items.size) { i ->
                when (val item = state.items[i]) {
                    is DiffItem.Unchanged -> UnchangedRow(item.gap, state.expanded) { state.expandContext() }
                    is DiffItem.HunkStart -> HunkHeaderRow(state, item)
                    is DiffItem.Row -> {
                        val onOpen: ((Int) -> Unit)? = if (state.openable) state::openAt else null
                        if (state.layout == DiffLayout.Split) {
                            SplitRow(item.row, state.wrap, horizontal, onOpen)
                        } else {
                            InlineRows(item.row, state.wrap, horizontal, onOpen)
                        }
                    }
                }
            }
            item { Box(modifier = Modifier.size(Space.xxl)) }
        }
    }
}

/** Which version is on which side. Without it the two columns are just two columns. */
@Composable
private fun ColumnTitles(state: DiffState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(vertical = Space.xxs),
    ) {
        listOf(state.compare.leftTitle, state.compare.rightTitle).forEach { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = GutterWidth),
            )
        }
    }
}

/** Which hunk of how many, and the way to the next one without scrolling a whole file. */
@Composable
private fun HunkBar(index: Int, total: Int, onJump: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = "Hunk ${index + 1} of $total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionChip(label = "Previous", enabled = index > 0) { onJump(index - 1) }
        ActionChip(label = "Next", enabled = index < total - 1) { onJump(index + 1) }
    }
}

/**
 * The lines neither side changed, counted rather than printed.
 *
 * git prints three lines of context and says nothing about the rest; the hunk headers say exactly
 * how many were skipped, so the row can name the number and offer to show them.
 */
@Composable
private fun UnchangedRow(gap: Gap, expanded: Boolean, onExpand: () -> Unit) {
    val label = "${gap.lines} unchanged line" + if (gap.lines == 1) "" else "s"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier else Modifier.clickable(onClick = onExpand).handCursor())
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(vertical = Space.xs),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (expanded) label else "$label — show",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The `@@` line, plus what you can do to the hunk under it. */
@Composable
private fun HunkHeaderRow(state: DiffState, item: DiffItem.HunkStart) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = Space.sm, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = item.hunk.header,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.canAct) {
            ActionChip(label = "Stage") { state.stage(item.hunk) }
            ActionChip(label = "Revert", danger = true) { state.promptRevert(item.hunk) }
        }
    }
}

// --- stash ---------------------------------------------------------------------------------------

@Composable
private fun StashFileList(state: DiffState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        Text(
            text = "FILES",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.files.forEach { file ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                StatusLetter(file.status)
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = Space.sm),
        )
    }
}


/** A small action, sized for a toolbar rather than for a form. */
@Composable
private fun ActionChip(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    caret: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = Color.Transparent,
        contentColor = when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = 0.4f)
            danger -> colors.error
            else -> colors.onSurfaceVariant
        },
        border = BorderStroke(StrokeWidth.hairline, colors.outlineVariant),
        modifier = if (enabled) Modifier.clickable(onClick = onClick).handCursor() else Modifier,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = ControlSize.compactHeight)
                .padding(ControlSize.compactPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A vector, not a "⌄" character: the glyph sits low, ignores the label's size, and
            // reads as a stray character rather than as the control's own affordance.
            if (caret) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.ChevronDown),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.xs),
                )
            }
        }
    }
}


/** Below this a split view is two columns of ellipsis rather than a comparison. */
private val SplitMinWidth = 640.dp
