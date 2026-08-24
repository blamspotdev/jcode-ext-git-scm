package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor

/**
 * How a line of a diff is drawn, wherever a diff is drawn.
 *
 * The file diff and a commit's files show the same thing and must show it the same way — two
 * columns that stay in step, both line numbers, and the changed words picked out. [onOpen] is what
 * separates them: a working-tree diff opens the real file at that line, and a commit's diff is a
 * historical revision, so jumping to that line in the file as it is now lands somewhere unrelated.
 * Null means the rows are to read, not to press.
 */

/** Room for four digits, which covers the files anyone reads a diff of on a phone. */
internal val GutterWidth = 44.dp

@Composable
internal fun SplitRow(row: AlignedRow, wrap: Boolean, horizontal: ScrollState, onOpen: ((Int) -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        CodeCell(row.left, wrap, horizontal, old = true, onOpen = onOpen, modifier = Modifier.weight(1f).fillMaxHeight())
        VerticalDivider(
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        CodeCell(row.right, wrap, horizontal, old = false, onOpen = onOpen, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

/**
 * The same row with one column instead of two.
 *
 * A replacement becomes the two lines it always was in a unified diff — removed above added — which
 * is what a narrow screen has room for and what a reader of `git diff` already expects.
 */
@Composable
internal fun InlineRows(row: AlignedRow, wrap: Boolean, horizontal: ScrollState, onOpen: ((Int) -> Unit)?) {
    val left = row.left
    val right = row.right
    if (left != null && right != null && left.kind == DiffLineKind.Context) {
        CodeCell(right, wrap, horizontal, old = false, onOpen = onOpen, modifier = Modifier.fillMaxWidth())
        return
    }
    left?.let { CodeCell(it, wrap, horizontal, old = true, onOpen = onOpen, modifier = Modifier.fillMaxWidth()) }
    right?.let { CodeCell(it, wrap, horizontal, old = false, onOpen = onOpen, modifier = Modifier.fillMaxWidth()) }
}

/**
 * One line of one version.
 *
 * A null cell is a blank across from a line the other side does not have — drawn, not skipped, so
 * the two columns stay in step.
 */
@Composable
private fun CodeCell(
    cell: Cell?,
    wrap: Boolean,
    horizontal: ScrollState,
    old: Boolean,
    onOpen: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val success = JCodeTheme.semanticColors.success
    val background = when (cell?.kind) {
        DiffLineKind.Add -> success.copy(alpha = 0.12f)
        DiffLineKind.Delete -> colors.error.copy(alpha = 0.12f)
        null -> colors.surfaceVariant.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val tappable = onOpen != null && !old && cell != null && cell.number > 0
    Row(
        modifier = modifier
            .background(background)
            .then(if (tappable) Modifier.clickable { onOpen!!(cell.number) }.handCursor() else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = cell?.number?.takeIf { it > 0 }?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant.copy(alpha = 0.55f),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(GutterWidth).padding(end = Space.xs),
        )
        Text(
            text = highlighted(cell, success, colors.error),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when (cell?.kind) {
                DiffLineKind.Add -> success
                DiffLineKind.Delete -> colors.error
                else -> colors.onSurface
            },
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            softWrap = wrap,
            modifier = Modifier
                .weight(1f)
                .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))
                .padding(end = Space.sm),
        )
    }
}

/**
 * The line, with the part that actually changed picked out.
 *
 * Without this a replaced line is two solid blocks of colour and the reader has to spot the
 * difference themselves — which, on a line of code, is the whole of the work.
 */
private fun highlighted(cell: Cell?, success: Color, error: Color): AnnotatedString {
    val text = cell?.text?.ifEmpty { " " } ?: " "
    val span = cell?.changed ?: return AnnotatedString(text)
    val start = span.first.coerceIn(0, text.length)
    val end = (span.last + 1).coerceIn(start, text.length)
    if (start >= end) return AnnotatedString(text)
    val tint = if (cell.kind == DiffLineKind.Add) success else error
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(background = tint.copy(alpha = 0.30f)), start, end)
    }
}


@Composable
internal fun StatusLetter(code: String) {
    val tint = when (code) {
        "A" -> JCodeTheme.semanticColors.success
        "D" -> MaterialTheme.colorScheme.error
        "R", "C" -> MaterialTheme.colorScheme.primary
        else -> JCodeTheme.semanticColors.warning
    }
    Surface(
        shape = RoundedCornerShape(Radius.xs),
        color = tint.copy(alpha = 0.16f),
        modifier = Modifier.size(IconSize.sm),
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
