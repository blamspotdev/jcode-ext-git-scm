package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import kotlinx.coroutines.launch

/**
 * Resolve a file's conflicts, three panes at a time.
 *
 * TortoiseGitMerge's shape: the two versions side by side, and the result you are building
 * underneath them. Reading two columns is what makes the difference visible — stacked, you have to
 * hold one in your head while looking at the other — and the result belongs below both because it
 * is what they are being turned into.
 *
 * Unchanged stretches collapse to a count. They are not what you opened this for, and they still get
 * written back on save.
 */
@Composable
internal fun MergePage(state: MergeState, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= MergeSplitMinWidth
        LaunchedEffect(wide) { state.split = wide }
        Column(modifier = Modifier.fillMaxSize()) {
            MergeHeader(state, wide) { n ->
                state.goTo(n)
                scope.launch { list.animateScrollToItem(state.segmentOf(state.current)) }
            }
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

                else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.segments) { index, segment ->
                        if (segment.conflict) {
                            ConflictBlock(state, index, segment, state.conflictNumberAt(index) + 1)
                        } else {
                            ContextLine(segment)
                        }
                    }
                    item { Box(modifier = Modifier.height(Space.xxl)) }
                }
            }
        }
    }
}

@Composable
private fun MergeHeader(state: MergeState, wide: Boolean, onJump: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = state.path,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.conflicted) {
                CompactFilledButton(
                    text = "Save & resolve",
                    onClick = { state.save() },
                    enabled = !state.busy,
                )
            }
        }
        if (!state.conflicted) return@Column
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Muted("Conflict ${state.current + 1} of ${state.conflictCount}")
            CompactOutlinedButton(
                text = "Previous",
                onClick = { onJump(state.current - 1) },
                enabled = state.current > 0,
            )
            CompactOutlinedButton(
                text = "Next",
                onClick = { onJump(state.current + 1) },
                enabled = state.current < state.conflictCount - 1,
            )
            Box(modifier = Modifier.weight(1f))
            if (wide) {
                SegmentedToggle(
                    options = listOf(true, false),
                    selected = state.split,
                    label = { if (it) "Side by side" else "Stacked" },
                    onSelect = { state.split = it },
                )
            }
        }
    }
}

@Composable
private fun ContextLine(segment: MergeSegment) {
    val count = segment.text.size
    if (count == 0 || (count == 1 && segment.text[0].isEmpty())) return
    Text(
        text = "⋯ $count unchanged line${if (count == 1) "" else "s"} ⋯",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
    )
}

@Composable
private fun ConflictBlock(state: MergeState, index: Int, segment: MergeSegment, number: Int) {
    val theirs = JCodeTheme.semanticColors.warning
    val ours = MaterialTheme.colorScheme.primary
    Card(title = "", modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = "Conflict $number",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            CompactOutlinedButton(
                text = "Theirs",
                onClick = { state.choose(index, segment.theirs.joinToString("\n")) },
            )
            CompactOutlinedButton(
                text = "Mine",
                onClick = { state.choose(index, segment.ours.joinToString("\n")) },
            )
            CompactOutlinedButton(
                text = "Both",
                onClick = { state.choose(index, state.both(segment)) },
            )
        }
        // Theirs on the left, mine on the right — TortoiseGitMerge's order, so muscle memory from it
        // lands on the right pane here.
        if (state.split) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Pane("Theirs (incoming)", segment.theirs, theirs, Modifier.weight(1f).fillMaxHeight())
                Pane("Mine (current)", segment.ours, ours, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Pane("Theirs (incoming)", segment.theirs, theirs, Modifier.fillMaxWidth())
            Pane("Mine (current)", segment.ours, ours, Modifier.fillMaxWidth())
        }
        FieldLabel("Merged (editable)")
        CompactField(
            value = state.resolutions.getOrElse(index) { "" },
            onValueChange = { state.choose(index, it) },
            placeholder = "(empty — this conflict resolves to nothing)",
            minLines = 2,
            maxLines = 12,
            literal = true,
            monospace = true,
        )
    }
}

/**
 * One version of the conflicted lines.
 *
 * Not wrapped: these are lines of code, and a wrapped line reads as two, which is the wrong thing to
 * see when the question is which of two versions to keep.
 */
@Composable
private fun Pane(label: String, lines: List<String>, accent: Color, modifier: Modifier = Modifier) {
    val horizontal = rememberScrollState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = accent.copy(alpha = 0.10f),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        ) {
            Text(
                text = lines.joinToString("\n").ifEmpty { "(nothing)" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(horizontal)
                    .padding(horizontal = Space.sm, vertical = Space.xs),
            )
        }
    }
}

/** Below this the two versions become two columns of ellipsis rather than a comparison. */
private val MergeSplitMinWidth = 640.dp
