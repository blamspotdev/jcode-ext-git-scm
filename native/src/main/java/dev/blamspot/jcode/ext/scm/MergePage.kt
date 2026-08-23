package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
    val merged = remember(rows, state.editing) { mergedRows(rows, state.editing) }
    // Which pane the reader is moving; the others follow it, and it follows nobody. Without that a
    // follower answers with a move of its own, and since that answer lands a frame late it arrives
    // as a correction to a position the hand has already left — the pane being dragged is dragged
    // back to where it was, and the leftover falls through to the page.
    var driver by remember { mutableStateOf(Driver.None) }

    fun jump(n: Int) {
        state.goTo(n)
        val at = rows.indexOfFirst { it.conflict == state.current }
        if (at >= 0) {
            // Both are placed here, so neither should be chasing the other while it happens.
            driver = Driver.None
            scope.launch { top.animateScrollToItem(at) }
            // goTo just opened a different block, which renumbers the merged pane's items — so the
            // mapping is taken from where it now stands rather than from the one composed with.
            scope.launch { bottom.animateScrollToItem(mergedRows(rows, state.editing).itemOf[at]) }
        }
    }

    // Where the page's own frame sits, to put the caret's position in the same terms. It does not
    // move with the page's scroll, which is what makes it the thing to measure against.
    var frameTop by remember { mutableStateOf(0f) }
    var frameHeight by remember { mutableStateOf(0) }
    val caret = remember { Caret() }
    val page = rememberScrollState()
    // One state across all three panes: a line running off the right of one has run off all of them.
    val horizontal = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().onGloballyPositioned {
            frameTop = it.positionInRoot().y
            frameHeight = it.size.height
        },
    ) {
        val wide = maxWidth >= MergeSplitMinWidth
        // Two columns of monospace need real width. Below it only one side fits, so which one
        // becomes a choice rather than a given — without it a phone held upright shows Theirs and
        // no way at all to see what you had.
        var showMine by remember { mutableStateOf(false) }
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

        // Keep the caret in sight without the window sliding: the editor says where its caret is,
        // and the page scrolls the difference. Keyed on the caret and on the frame's height — the
        // keyboard opening shrinks the latter, which is the moment the editor needs reaching. The
        // editor's own position is read, not keyed on, or every animated frame would restart this.
        // The three panes are one view of one file, so they move as one. The two sides already
        // share a list; the merged pane both follows it and leads it.
        val sidesDragged by top.interactionSource.collectIsDraggedAsState()
        val mergedDragged by bottom.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(sidesDragged, mergedDragged) {
            // Latched rather than read live, so the fling a gesture leaves behind keeps carrying the
            // other panes after the finger is gone.
            when {
                sidesDragged -> driver = Driver.Sides
                mergedDragged -> driver = Driver.Merged
            }
        }
        val rowHeight = with(density) { RowHeight.toPx() }
        LaunchedEffect(merged) {
            snapshotFlow { top.firstVisibleItemIndex to top.firstVisibleItemScrollOffset }
                .collect { (row, offset) ->
                    if (driver != Driver.Sides || row !in merged.itemOf.indices) return@collect
                    val item = merged.itemOf[row]
                    val into = (row - merged.rowOf[item]) * rowHeight + offset
                    val at = merged.intoItem(item, into, rowHeight, bottom).toInt()
                    if (bottom.firstVisibleItemIndex != item ||
                        bottom.firstVisibleItemScrollOffset != at
                    ) {
                        bottom.scrollToItem(item, at)
                    }
                }
        }
        LaunchedEffect(merged) {
            snapshotFlow { bottom.firstVisibleItemIndex to bottom.firstVisibleItemScrollOffset }
                .collect { (item, offset) ->
                    if (driver != Driver.Merged || item !in merged.rowOf.indices) return@collect
                    val into = merged.intoRows(item, offset.toFloat(), rowHeight, bottom)
                    val row = (merged.rowOf[item] + (into / rowHeight).toInt())
                        .coerceAtMost(merged.itemOf.lastIndex)
                    val at = (into % rowHeight).toInt()
                    if (top.firstVisibleItemIndex != row || top.firstVisibleItemScrollOffset != at) {
                        top.scrollToItem(row, at)
                    }
                }
        }

        LaunchedEffect(caret.span, frameHeight, frameTop) {
            if (frameHeight <= 0) return@LaunchedEffect
            val margin = with(density) { CaretMargin.toPx() }
            // Two things can be hiding the caret and they are fixed in order. Inside the pane, the
            // editor may have grown past what the pane shows, and only the pane's own list reaches
            // that. Outside it, the pane may be under the keyboard, and only the page reaches that.
            //
            // The pane is moved by what it takes to see the caret *within the pane* — never by the
            // page's shortfall. Handing it that instead scrolls the editor clean out of the pane,
            // at which point the list drops it and there is nothing left to measure against.
            //
            // Measure, correct, measure again: a correction moves the editor, and where it lands is
            // not known until the frame after. Reading a position once and trusting it is what
            // sends a caret walking up the file chasing a stale number to the top of the page.
            repeat(MaxRevealPasses) {
                val span = caret.span ?: return@LaunchedEffect
                val editor = caret.editor?.takeIf { it.isAttached } ?: return@LaunchedEffect
                val editorTop = editor.positionInRoot().y
                val pane = caret.pane?.takeIf { it.isAttached }

                val inPane = pane?.let { editorTop - it.positionInRoot().y }
                val paneShift = if (inPane == null) 0f else shift(
                    top = inPane + span.first,
                    bottom = inPane + span.last,
                    height = pane.size.height.toFloat(),
                    margin = margin,
                )
                if (paneShift != 0f) {
                    driver = Driver.Merged
                    bottom.scrollBy(paneShift)
                    withFrameNanos { }
                    return@repeat
                }

                val inFrame = editorTop - frameTop
                // Snapped, not animated: a caret is followed, not travelled to, and an animation
                // half-finished when the next keystroke lands leaves the page between two places.
                val pageShift = shift(
                    top = inFrame + span.first,
                    bottom = inFrame + span.last,
                    height = frameHeight.toFloat(),
                    margin = margin,
                )
                // In sight, which is where every pass but the first ends up.
                if (pageShift == 0f) return@LaunchedEffect
                page.scrollBy(pageShift)
                withFrameNanos { }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(page)) {
            Column(modifier = Modifier.onSizeChanged { chrome = with(density) { it.height.toDp() } }) {
                MergeHeader(state, wide, ::jump)
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
                    Sides(
                        state,
                        rows,
                        top,
                        wide,
                        showMine,
                        { showMine = it },
                        horizontal,
                        modifier = Modifier.weight(SidesWeight),
                    )
                    HorizontalDivider(
                        thickness = StrokeWidth.thin,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Merged(state, merged, bottom, caret, horizontal, modifier = Modifier.weight(MergedWeight))
                }
            }
        }
    }
}

/**
 * The toolbar, in one row or two.
 *
 * Everything on one row needs a desktop's width. On a phone held upright it does not fit, and what
 * does not fit is not dropped but wrapped onto a second line — a button reading "Save & resol ve"
 * over three lines is worse than a taller toolbar.
 */
@Composable
private fun MergeHeader(state: MergeState, wide: Boolean, onJump: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Where(state, wide, onJump)
            if (wide) Actions(state)
        }
        if (!wide && state.conflicted) {
            Spacer(modifier = Modifier.height(Space.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Actions(state)
            }
        }
    }
}

/** Which file, which conflict, and the way between them. */
@Composable
private fun RowScope.Where(state: MergeState, wide: Boolean, onJump: (Int) -> Unit) {
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
            return
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
        if (!wide) return
}

/**
 * What to do with the conflict the navigation is on.
 *
 * Applied to that one rather than offered beside every block, which is what makes this a toolbar.
 */
@Composable
private fun RowScope.Actions(state: MergeState) {
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
        Box(modifier = Modifier.weight(1f))
        CompactFilledButton(
            text = "Save & resolve",
            onClick = { state.save() },
            enabled = !state.busy,
        )
}

/** Theirs and Mine, in the order TortoiseGit puts them, sharing one scroll position. */
@Composable
private fun Sides(
    state: MergeState,
    rows: List<MergeRow>,
    list: LazyListState,
    wide: Boolean,
    showMine: Boolean,
    onShowMine: (Boolean) -> Unit,
    horizontal: ScrollState,
    modifier: Modifier = Modifier,
) {
    val theirs = JCodeTheme.semanticColors.warning
    val mine = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (wide) {
                PaneTitle("Theirs (incoming)", theirs, Modifier.weight(1f))
                PaneTitle("Mine (current)", mine, Modifier.weight(1f))
            } else {
                PaneTitle(
                    if (showMine) "Mine (current)" else "Theirs (incoming)",
                    if (showMine) mine else theirs,
                    Modifier.weight(1f),
                )
                Box(modifier = Modifier.padding(end = Space.sm)) {
                    ToggleChip(
                        label = if (showMine) "Show theirs" else "Show mine",
                        on = false,
                    ) { onShowMine(!showMine) }
                }
            }
        }
        // One list holding both columns, so they cannot drift apart: two lists sharing a position is
        // two lists agreeing to, and one row spanning both simply is aligned.
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            items(rows.size) { i ->
                val row = rows[i]
                Row(modifier = Modifier.fillMaxWidth().height(RowHeight)) {
                    if (wide || !showMine) {
                        SideCell(state, row, row.theirs, row.theirsNo, theirs, horizontal, Modifier.weight(1f))
                    }
                    if (wide) {
                        VerticalDivider(
                            thickness = StrokeWidth.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    if (wide || showMine) {
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
    // One thing to do with a line, and which one follows from whether it is already in the result.
    val actions = when {
        row.conflict < 0 || text == null -> emptyList()
        state.isTaken(row.conflict, text) ->
            listOf(ContextAction(JCodeIcon.Minus, "Remove this line") { state.dropLine(row.conflict, text) })

        else -> listOf(ContextAction(JCodeIcon.Add, "Use this line") { state.useLine(row.conflict, text) })
    }
    val current = row.conflict >= 0 && row.conflict == state.current
    LineCell(
        text = text,
        number = number,
        conflict = row.conflict,
        mark = state.markForSide(row, text),
        accent = accent,
        current = current,
        horizontal = horizontal,
        modifier = modifier,
        onClick = { if (row.conflict >= 0) state.goTo(row.conflict) },
        actions = actions,
        // Tapping a line of the conflict already being worked on used to do nothing — it navigated
        // to where it already was. It opens the line's own menu instead, which is otherwise reachable
        // only by long-pressing and so, in practice, not reachable at all.
        menuOnClick = current,
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
    merged: MergedRows,
    list: LazyListState,
    caret: Caret,
    horizontal: ScrollState,
    modifier: Modifier = Modifier,
) {
    val accent = JCodeTheme.semanticColors.success
    Column(modifier = modifier.fillMaxWidth()) {
        PaneTitle("Merged", accent, Modifier.fillMaxWidth())
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().onGloballyPositioned { caret.pane = it },
        ) {
            items(merged.items.size) { i ->
                val (row, conflict, firstLine) = merged.items[i]
                if (row == null) {
                    InlineEditor(state, conflict, firstLine, caret)
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
                            // Any line, not only a conflicted one: the merged pane is the
                            // result, and the result is yours to write.
                            onClick = {
                                if (row.conflict >= 0) {
                                    state.goTo(row.conflict)
                                } else {
                                    state.editSegment(row.segment)
                                }
                            },
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
 * The gutter and the canvas are pinned to one line height rather than left to their own metrics —
 * a Compose Text and an Android EditText do not agree on leading, and a fraction of a line per row
 * is all it takes for the numbers to stop being beside their lines.
 *
 * That height is a row's, not the text's own. The block occupies exactly the space of the rows it
 * stands in for, so opening it for editing does not shove this pane out of step with the two above
 * it — which is the whole point of showing three versions on one set of rows.
 */
@Composable
private fun InlineEditor(state: MergeState, segment: Int, firstLine: Int, caret: Caret) {
    val colors = MaterialTheme.colorScheme
    val value = state.textOf(segment)
    val count = if (value.isEmpty()) 1 else value.count { it == '\n' } + 1
    val lineHeight = with(LocalDensity.current) { RowHeight.toSp() }
    // Amber says conflicted, and most blocks are not — they are simply the one being written in.
    val accent = if (state.segments.getOrNull(segment)?.conflict == true) {
        JCodeTheme.semanticColors.warning
    } else {
        colors.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.14f)),
    ) {
        Box(modifier = Modifier.width(BarWidth).fillMaxHeight().background(accent))
        Text(
            text = (0 until count).joinToString("\n") { (firstLine + it).toString() },
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = CodeSize,
                lineHeight = lineHeight,
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
            onValueChange = { state.edit(segment, it) },
            textColor = colors.onSurface,
            cursorColor = colors.primary,
            fontSize = CodeSize,
            lineHeight = lineHeight,
            modifier = Modifier
                .weight(1f)
                .padding(end = Space.sm)
                .onGloballyPositioned { caret.editor = it },
            onCaret = { caret.span = it },
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
    menuOnClick: Boolean = false,
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
                    onClick = {
                        if (menuOnClick && actions.isNotEmpty()) menu = true else onClick()
                    },
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

/**
 * The merged pane's rows, and how they line up with the two panes above it.
 *
 * That pane collapses the conflict being edited into a single item, so from there on its indices
 * stop matching the rows either side of it. These carry the correspondence back, which is what lets
 * the three panes be scrolled as one.
 */
private class MergedRows(
    /** Row, conflict being edited, and the line number its first line carries. */
    val items: List<Triple<MergeRow?, Int, Int>>,
    /** The row each item begins at. */
    val rowOf: IntArray,
    /** The item each row falls inside. */
    val itemOf: IntArray,
)

/**
 * Collapse the block being edited into one item, and record what became what.
 *
 * The item carries the line number its first line has, so the editor's gutter carries on counting
 * the file rather than starting again at one.
 */
private fun mergedRows(rows: List<MergeRow>, editing: Int): MergedRows {
    val items = ArrayList<Triple<MergeRow?, Int, Int>>()
    val rowOf = ArrayList<Int>()
    val itemOf = IntArray(rows.size)
    var i = 0
    var lastNumber = 0
    while (i < rows.size) {
        val row = rows[i]
        rowOf += i
        if (row.segment == editing) {
            items += Triple(null, row.segment, lastNumber + 1)
            while (i < rows.size && rows[i].segment == row.segment) {
                if (rows[i].mergedNo > 0) lastNumber = rows[i].mergedNo
                itemOf[i] = items.size - 1
                i++
            }
        } else {
            if (row.mergedNo > 0) lastNumber = row.mergedNo
            items += Triple(row, -1, 0)
            itemOf[i] = items.size - 1
            i++
        }
    }
    return MergedRows(items, rowOf.toIntArray(), itemOf)
}

/** Which pane the reader is moving. The others follow it, and it follows nobody. */
private enum class Driver { None, Sides, Merged }

/** The rows an item stands for — one, except the conflict being edited. */
private fun MergedRows.rowsAt(item: Int): Int =
    (if (item + 1 < rowOf.size) rowOf[item + 1] else itemOf.size) - rowOf[item]

/** That item's height on screen, or 0 when it is not currently laid out. */
private fun heightOf(item: Int, list: LazyListState): Int =
    list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }?.size ?: 0

/**
 * How far into an item a distance measured in rows falls, and back again.
 *
 * The item standing in for the conflict is one item where the sides have many rows, and it is as
 * tall as its text rather than as tall as the rows it replaced. Held at its first row instead, the
 * sides would sit still through the whole of it — and then answer the merged pane with that same
 * first row, which is what dragged the pane back to the top of the conflict every time it moved.
 * Scaling between the two keeps them moving together across it.
 */
private fun MergedRows.intoItem(item: Int, into: Float, rowHeight: Float, list: LazyListState): Float {
    val rows = rowsAt(item)
    val height = heightOf(item, list)
    if (rows <= 1 || height <= 0) return into
    return into / (rows * rowHeight) * height
}

private fun MergedRows.intoRows(item: Int, into: Float, rowHeight: Float, list: LazyListState): Float {
    val rows = rowsAt(item)
    val height = heightOf(item, list)
    if (rows <= 1 || height <= 0) return into
    return into / height * (rows * rowHeight)
}

/** Room left around the caret when the page scrolls to it, so it never sits against an edge. */
private val CaretMargin = 24.dp

/** How many times a reveal re-measures before giving up; two settles it, the rest is headroom. */
private const val MaxRevealPasses = 4

/** How far a viewport of [height] must move for [top]..[bottom] to sit inside it, clear of [margin]. */
private fun shift(top: Float, bottom: Float, height: Float, margin: Float): Float = when {
    top < margin -> top - margin
    bottom > height - margin -> bottom - (height - margin)
    else -> 0f
}

/**
 * Where the caret is, told in two halves.
 *
 * The editor knows the caret's offset in its own pixels and nothing about the page; the page knows
 * where the editor sits and nothing about its text. Neither has to learn the other's business.
 */
private class Caret {
    /**
     * The editing surface itself, not a reading of where it was.
     *
     * Every scroll moves it, and a recorded position is a frame behind — which is enough for a
     * caret walking up the file to be measured against where the editor used to be, and for the
     * page to keep scrolling the same way until it runs out.
     */
    var editor by mutableStateOf<LayoutCoordinates?>(null)

    /** The pane the editor sits in, whose own scrolling is the only thing that reaches inside it. */
    var pane by mutableStateOf<LayoutCoordinates?>(null)

    /** Top and bottom of the caret's line within that surface, or null when it is not being edited. */
    var span by mutableStateOf<IntRange?>(null)
}

/** A floor for the pane area, for a window too short to have established one of its own. */
private val MergeMinPaneArea = 200.dp

/** The one type scale the three panes and the editor all use. */
internal val CodeSize = 13.sp
