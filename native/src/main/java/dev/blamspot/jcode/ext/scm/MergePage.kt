package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon

/**
 * Resolve a file's conflicts, one at a time.
 *
 * Each conflict shows both sides and an editable result seeded with yours, so the common answers —
 * keep mine, take theirs, keep both — are one tap, and anything else is typing in a box that is
 * already most of the way there. Unchanged stretches are collapsed to a line saying how many lines
 * they are: they are not what you opened this for, and they still get written back on save.
 */
@Composable
internal fun MergePage(state: MergeState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item {
            PageHeader(
                icon = jcIcon(JCodeIcon.Files),
                title = state.path,
                subtitle = "Resolve conflicts",
                monospaceTitle = true,
            ) {
                if (state.conflicted) {
                    CompactFilledButton(
                        text = "Save & resolve",
                        onClick = { state.save() },
                        enabled = !state.busy,
                    )
                }
            }
        }
        state.message?.let { item { StatusText(it, state.failed) } }
        when {
            state.loading -> item { Note("Reading the file…", spinner = true) }
            state.error != null -> item { Note(state.error.orEmpty()) }
            !state.conflicted -> item {
                Note(
                    "No conflict markers found. If this file is already resolved, use " +
                        "\"Mark resolved\" in the Merge Changes list.",
                )
            }

            else -> itemsIndexed(state.segments) { index, segment ->
                if (segment.conflict) {
                    ConflictBlock(state, index, segment, state.conflictNumber(index))
                } else {
                    ContextLine(segment)
                }
            }
        }
    }
}

/** How many conflicts precede this one, so the blocks are numbered as the user counts them. */
private fun MergeState.conflictNumber(index: Int): Int =
    segments.take(index).count { it.conflict } + 1

@Composable
private fun ContextLine(segment: MergeSegment) {
    val count = segment.text.size
    if (count == 0 || (count == 1 && segment.text[0].isEmpty())) return
    Text(
        text = "⋯ $count unchanged line${if (count == 1) "" else "s"} ⋯",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
    )
}

@Composable
private fun ConflictBlock(state: MergeState, index: Int, segment: MergeSegment, number: Int) {
    Card(title = "") {
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
                text = "Current",
                onClick = { state.choose(index, segment.ours.joinToString("\n")) },
            )
            CompactOutlinedButton(
                text = "Incoming",
                onClick = { state.choose(index, segment.theirs.joinToString("\n")) },
            )
            CompactOutlinedButton(
                text = "Both",
                onClick = { state.choose(index, state.both(segment)) },
            )
        }
        Side("Current (ours)", segment.ours, JCodeTheme.semanticColors.success)
        Side("Incoming (theirs)", segment.theirs, MaterialTheme.colorScheme.primary)
        FieldLabel("Result (editable)")
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
 * One side of the conflict, tinted and scrollable sideways.
 *
 * Not wrapped: these are lines of code, and a wrapped line reads as two, which is the wrong thing to
 * see when the question is which of two versions to keep.
 */
@Composable
private fun Side(label: String, lines: List<String>, accent: Color) {
    val horizontal = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = accent.copy(alpha = 0.10f),
            modifier = Modifier.fillMaxWidth(),
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
