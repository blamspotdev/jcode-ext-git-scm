package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor
import kotlinx.coroutines.launch

/**
 * Resolve a file's conflicts, the way TortoiseGitMerge does it.
 *
 * Three views of the whole file: the two sides above, side by side, and the result you are building
 * below them. Not a list of extracted conflicts — a conflict is easiest to judge in the middle of
 * the file it is in, with the lines around it that did not change, and pulling it out of the file is
 * exactly what makes it hard to judge.
 *
 * The three are aligned line for line, with blanks where one side has fewer lines than another. That
 * filler is what makes reading across a row mean anything.
 */
@Composable
internal fun MergePage(state: MergeState, modifier: Modifier = Modifier) {
    val top = rememberLazyListState()
    val bottom = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rows = remember(state.revision, state.segments.size) { state.buildRows() }

    fun jump(n: Int) {
        state.goTo(n)
        val at = rows.indexOfFirst { it.conflict == state.current }
        if (at >= 0) {
            scope.launch { top.animateScrollToItem(at) }
            scope.launch { bottom.animateScrollToItem(at) }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= MergeSplitMinWidth
        LaunchedEffect(wide) { state.split = wide }
        Column(modifier = Modifier.fillMaxSize()) {
            MergeHeader(state, ::jump)
            HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            state.message?.let {
                Box(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)) {
                    StatusText(it, state.failed)
                }
                HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
            }
            when {
                state.loading -> Note("Reading the file…", spinner = true)
                state.error != null -> Note(state.error.orEmpty())
                !state.conflicted -> Note(
                    "No conflict markers found. If this file is already resolved, use " +
                        "\"Mark resolved\" in the Merge Changes list.",
                )

                else -> {
                    Sides(state, rows, top, wide, modifier = Modifier.weight(SidesWeight))
                    HorizontalDivider(
                        thickness = StrokeWidth.thin,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Merged(state, rows, bottom, modifier = Modifier.weight(MergedWeight))
                    HorizontalDivider(
                        thickness = StrokeWidth.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    HandEdit(state)
                }
            }
        }
    }
}

@Composable
private fun MergeHeader(state: MergeState, onJump: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = state.path,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!state.conflicted) {
            Box(modifier = Modifier.weight(1f))
            return@Row
        }
        Muted("${state.current + 1}/${state.conflictCount}")
        CompactOutlinedButton(
            text = "Prev",
            onClick = { onJump(state.current - 1) },
            enabled = state.current > 0,
        )
        CompactOutlinedButton(
            text = "Next",
            onClick = { onJump(state.current + 1) },
            enabled = state.current < state.conflictCount - 1,
        )
        Box(modifier = Modifier.weight(1f))
        // Applied to the conflict the navigation is on, which is what makes this a toolbar rather
        // than a control to be found again beside every block.
        Muted("Use:")
        CompactOutlinedButton(
            text = "Theirs",
            onClick = { state.editCurrent(state.theirsOf(state.current)) },
        )
        CompactOutlinedButton(
            text = "Mine",
            onClick = { state.editCurrent(state.mineOf(state.current)) },
        )
        CompactOutlinedButton(
            text = "Both",
            onClick = { state.editCurrent(state.bothOf(state.current)) },
        )
        CompactFilledButton(
            text = "Save & resolve",
            onClick = { state.save() },
            enabled = !state.busy,
        )
    }
}

/** Theirs and Mine, in the order TortoiseGit puts them, sharing one scroll position. */
@Composable
private fun Sides(
    state: MergeState,
    rows: List<MergeRow>,
    list: androidx.compose.foundation.lazy.LazyListState,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val theirs = JCodeTheme.semanticColors.warning
    val mine = MaterialTheme.colorScheme.primary
    val horizontal = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            PaneTitle("Theirs (incoming)", theirs, Modifier.weight(1f))
            if (wide) PaneTitle("Mine (current)", mine, Modifier.weight(1f))
        }
        // One list holding both columns, so they cannot drift apart: two lists sharing a position is
        // two lists agreeing to, and one row spanning both is two columns that simply are aligned.
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(rows.size) { i ->
                val row = rows[i]
                Row(modifier = Modifier.fillMaxWidth()) {
                    LineCell(row.theirs, row.theirsNo, row.conflict, state, theirs, horizontal, Modifier.weight(1f)) {
                        state.editCurrent(state.theirsOf(row.conflict))
                    }
                    if (wide) {
                        VerticalDivider(
                            thickness = StrokeWidth.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        LineCell(row.mine, row.mineNo, row.conflict, state, mine, horizontal, Modifier.weight(1f)) {
                            state.editCurrent(state.mineOf(row.conflict))
                        }
                    }
                }
            }
        }
    }
}

/** The file being built. */
@Composable
private fun Merged(
    state: MergeState,
    rows: List<MergeRow>,
    list: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val accent = JCodeTheme.semanticColors.success
    val horizontal = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth()) {
        PaneTitle("Merged", accent, Modifier.fillMaxWidth())
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(rows.size) { i ->
                val row = rows[i]
                LineCell(row.merged, row.mergedNo, row.conflict, state, accent, horizontal, Modifier.fillMaxWidth()) {
                    state.goTo(row.conflict)
                }
            }
        }
    }
}

/**
 * Typing into the conflict the navigation is on.
 *
 * The merged pane above shows the whole file and stays aligned with the two sides, which it can only
 * do while its rows are laid out rather than typed into. So the hand-editing happens here, on the
 * one conflict you are looking at, and the pane above shows the result of it.
 */
@Composable
private fun HandEdit(state: MergeState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs)) {
        FieldLabel("Conflict ${state.current + 1} — edit by hand")
        CompactField(
            value = state.resolutionOf(state.current),
            onValueChange = { state.editCurrent(it) },
            placeholder = "(empty — this conflict resolves to nothing)",
            minLines = 1,
            maxLines = 4,
            literal = true,
            monospace = true,
        )
    }
}

@Composable
private fun PaneTitle(label: String, accent: Color, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = accent,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(start = Space.lg, top = Space.xxs, bottom = Space.xxs),
    )
}

/**
 * One line of one version.
 *
 * A conflicted line carries its side's colour; the conflict the navigation is on carries it at full
 * strength so you can see where you are without reading the numbers. A null line is a blank held
 * open to keep the three columns level.
 */
@Composable
private fun LineCell(
    text: String?,
    number: Int,
    conflict: Int,
    state: MergeState,
    accent: Color,
    horizontal: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    onPick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val here = conflict >= 0 && conflict == state.current
    val background = when {
        conflict < 0 -> Color.Transparent
        text == null -> colors.surfaceVariant.copy(alpha = 0.35f)
        here -> accent.copy(alpha = 0.22f)
        else -> accent.copy(alpha = 0.10f)
    }
    Row(
        modifier = modifier
            .background(background)
            .then(if (conflict >= 0) Modifier.clickable(onClick = onPick).handCursor() else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.takeIf { it > 0 }?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant.copy(alpha = 0.55f),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(GutterWidth).padding(end = Space.xs),
        )
        Text(
            text = text?.ifEmpty { " " } ?: " ",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f).horizontalScroll(horizontal).padding(end = Space.sm),
        )
    }
}

/** The two sides get the larger share; the result needs less room than the argument about it. */
private const val SidesWeight = 0.58f
private const val MergedWeight = 0.42f

/** Below this the two sides become two columns of ellipsis, so only Theirs is shown. */
private val MergeSplitMinWidth = 640.dp
