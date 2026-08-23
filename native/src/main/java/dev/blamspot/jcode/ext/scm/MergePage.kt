package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blamspot.jcode.design.CompactContextMenu
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ContextAction
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor
import kotlinx.coroutines.launch

/**
 * Resolve a file's conflicts, the way TortoiseGitMerge does it.
 *
 * Three views of the whole file: the two sides above, side by side, and the result you are building
 * below them. Not a list of extracted conflicts — a conflict is easiest to judge in the middle of the
 * file it is in, with the lines around it that did not change, and pulling it out is exactly what
 * makes it hard to judge.
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
        // The panes keep the most room they have ever been given here. When the keyboard takes half
        // the screen — which in landscape is most of it — the page scrolls to reach them rather than
        // crushing three panes into what is left, and it is the page that moves, not the window.
        // Measured rather than fixed, so a closed keyboard still fits exactly on any screen.
        val density = LocalDensity.current
        var chrome by remember { mutableStateOf(0.dp) }
        var roomiest by remember(maxWidth) { mutableStateOf(0.dp) }
        // Not before the header has been measured, or the first frame — when it still reports no
        // height — sets a mark that is a whole header too tall and never comes down again.
        val free = maxHeight - chrome
        if (chrome > 0.dp && free > roomiest) roomiest = free
        val panes = roomiest.coerceAtLeast(MergeMinPaneArea)

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.onSizeChanged { chrome = with(density) { it.height.toDp() } }) {
                MergeHeader(state, ::jump)
                HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
                state.message?.let {
                    Box(modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)) {
                        StatusText(it, state.failed)
                    }
                    HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            when {
                state.loading -> Note("Reading the file…", spinner = true)
                state.error != null -> Note(state.error.orEmpty())
                !state.conflicted -> Note(
                    "No conflict markers found. If this file is already resolved, use " +
                        "\"Mark resolved\" in the Merge Changes list.",
                )

                else -> Column(modifier = Modifier.height(panes)) {
                    Sides(state, rows, top, wide, modifier = Modifier.weight(SidesWeight))
                    HorizontalDivider(
                        thickness = StrokeWidth.thin,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Merged(state, rows, bottom, modifier = Modifier.weight(MergedWeight))
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
    list: LazyListState,
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
        // two lists agreeing to, and one row spanning both simply is aligned.
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(rows.size) { i ->
                val row = rows[i]
                Row(modifier = Modifier.fillMaxWidth().height(RowHeight)) {
                    SideCell(state, row, row.theirs, row.theirsNo, theirs, horizontal, Modifier.weight(1f))
                    if (wide) {
                        VerticalDivider(
                            thickness = StrokeWidth.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        SideCell(state, row, row.mine, row.mineNo, mine, horizontal, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * One line of one side.
 *
 * Long-pressing it is how a conflict gets composed a line at a time: taking a whole side is one tap
 * on the toolbar, and anything finer than that has to be reachable from the line itself.
 */
@Composable
private fun SideCell(
    state: MergeState,
    row: MergeRow,
    text: String?,
    number: Int,
    accent: Color,
    horizontal: ScrollState,
    modifier: Modifier = Modifier,
) {
    val actions = when {
        row.conflict < 0 || text == null -> emptyList()
        state.isTaken(row.conflict, text) -> listOf(
            ContextAction(JCodeIcon.Minus, "Remove this line") { state.dropLine(row.conflict, text) },
            ContextAction(JCodeIcon.Add, "Use this line again") { state.useLine(row.conflict, text) },
        )

        else -> listOf(
            ContextAction(JCodeIcon.Add, "Use this line") { state.useLine(row.conflict, text) },
            ContextAction(JCodeIcon.Save, "Use only this line") { state.useOnlyLine(row.conflict, text) },
            ContextAction(JCodeIcon.Clear, "Clear this conflict", destructive = true) {
                state.clearConflict(row.conflict)
            },
        )
    }
    LineCell(
        text = text,
        number = number,
        conflict = row.conflict,
        mark = state.markForSide(row, text),
        accent = accent,
        current = row.conflict >= 0 && row.conflict == state.current,
        horizontal = horizontal,
        modifier = modifier,
        onClick = { if (row.conflict >= 0) state.goTo(row.conflict) },
        actions = actions,
    )
}

/**
 * The file being built, edited in place.
 *
 * The conflict you are on renders as a field rather than as rows, so hand-editing happens in the pane
 * that shows the result instead of in a strip underneath it. Every other block stays as rows, which
 * is what keeps this pane lined up with the two above.
 */
@Composable
private fun Merged(
    state: MergeState,
    rows: List<MergeRow>,
    list: LazyListState,
    modifier: Modifier = Modifier,
) {
    val accent = JCodeTheme.semanticColors.success
    val horizontal = rememberScrollState()
    // Rows, with the current conflict's run collapsed into one editable item. The item carries the
    // line number its first line has, so the editor's gutter carries on counting the file rather
    // than starting again at one.
    val items = remember(rows, state.current) {
        val out = ArrayList<Triple<MergeRow?, Int, Int>>()
        var i = 0
        var lastNumber = 0
        while (i < rows.size) {
            val row = rows[i]
            if (row.conflict >= 0 && row.conflict == state.current) {
                out += Triple(null, row.conflict, lastNumber + 1)
                while (i < rows.size && rows[i].conflict == row.conflict) {
                    if (rows[i].mergedNo > 0) lastNumber = rows[i].mergedNo
                    i++
                }
            } else {
                if (row.mergedNo > 0) lastNumber = row.mergedNo
                out += Triple(row, -1, 0)
                i++
            }
        }
        out
    }
    Column(modifier = modifier.fillMaxWidth()) {
        PaneTitle("Merged", accent, Modifier.fillMaxWidth())
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(items.size) { i ->
                val (row, conflict, firstLine) = items[i]
                if (row == null) {
                    InlineEditor(state, conflict, firstLine)
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(RowHeight)) {
                        LineCell(
                            text = row.merged,
                            number = row.mergedNo,
                            conflict = row.conflict,
                            mark = state.markForMerged(row),
                            accent = accent,
                            current = false,
                            horizontal = horizontal,
                            modifier = Modifier.fillMaxSize(),
                            onClick = { if (row.conflict >= 0) state.goTo(row.conflict) },
                            actions = emptyList(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The conflict being worked on, as an editing surface rather than a form field.
 *
 * A bordered, rounded input dropped into the middle of three code panes reads as a dialog that got
 * loose. This is the pane: the same monospace at the same size, the same gutter width, its numbers
 * carrying on from the lines above it, flush with the rows on either side.
 *
 * The gutter and the canvas are pinned to one line height in sp rather than left to their own
 * metrics — a Compose Text and an Android EditText do not agree on leading, and a fraction of a line
 * per row is all it takes for the numbers to stop being beside their lines.
 */
@Composable
private fun InlineEditor(state: MergeState, conflict: Int, firstLine: Int) {
    val colors = MaterialTheme.colorScheme
    val value = state.resolutionOf(conflict)
    val count = if (value.isEmpty()) 1 else value.count { it == '\n' } + 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(JCodeTheme.semanticColors.warning.copy(alpha = 0.14f)),
    ) {
        Box(
            modifier = Modifier
                .width(BarWidth)
                .fillMaxHeight()
                .background(JCodeTheme.semanticColors.warning),
        )
        Text(
            text = (0 until count).joinToString("\n") { (firstLine + it).toString() },
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = CodeSize,
                lineHeight = CodeLineHeight,
                color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
            ),
            textAlign = TextAlign.End,
            modifier = Modifier.width(GutterWidth).padding(end = Space.xs),
        )
        CodeCanvas(
            value = value,
            onValueChange = { state.editCurrent(it) },
            textColor = colors.onSurface,
            cursorColor = colors.primary,
            fontSize = CodeSize,
            lineHeight = CodeLineHeight,
            modifier = Modifier.weight(1f).padding(end = Space.sm),
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
 * One line of one version, with a bar down its left edge saying where it stands.
 *
 * Green for a line that is in the merged result, red for one that is not, amber for a conflict line
 * nothing has been decided about yet. A line all three agree on gets no bar at all — the bars are for
 * the argument, and a rule beside every settled line would only be a second left margin.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineCell(
    text: String?,
    number: Int,
    conflict: Int,
    mark: LineMark,
    accent: Color,
    current: Boolean,
    horizontal: ScrollState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    actions: List<ContextAction>,
) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val background = when {
        conflict < 0 -> Color.Transparent
        text == null -> colors.surfaceVariant.copy(alpha = 0.35f)
        current -> accent.copy(alpha = 0.22f)
        else -> accent.copy(alpha = 0.10f)
    }
    val bar = when (mark) {
        LineMark.Added -> JCodeTheme.semanticColors.success
        LineMark.Removed -> colors.error
        LineMark.Conflicted -> JCodeTheme.semanticColors.warning
        LineMark.None -> Color.Transparent
    }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (actions.isNotEmpty()) menu = true },
                )
                .handCursor(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(BarWidth).fillMaxHeight().background(bar))
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
        CompactContextMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            listActions = actions,
        )
    }
}

/** Wide enough to read as a rule beside the line rather than as part of the gutter. */
private val BarWidth = 3.dp

/** Fixed, so a row in one pane is the same height as the row across from it. */
private val RowHeight = 22.dp

/** The two sides get the larger share; the result needs less room than the argument about it. */
private const val SidesWeight = 0.55f
private const val MergedWeight = 0.45f

/** Below this the two sides become two columns of ellipsis, so only Theirs is shown. */
private val MergeSplitMinWidth = 640.dp

/** A floor for the pane area, for a window too short to have established one of its own. */
private val MergeMinPaneArea = 200.dp

/** The one type scale the three panes and the editor all use. */
internal val CodeSize = 13.sp
internal val CodeLineHeight = 18.sp
