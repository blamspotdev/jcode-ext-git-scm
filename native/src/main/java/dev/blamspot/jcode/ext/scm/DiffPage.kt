package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor
import dev.blamspot.jcode.design.jcIcon

/**
 * A unified diff, full width in the editor area.
 *
 * The gutter carries the line number in the *new* file, because that is the file you can open — a
 * removed line has no number there and shows none. Tapping a row opens the real file at that line,
 * which is the whole reason to read a diff in an editor rather than a terminal.
 *
 * Serves a stash's patch too. A stash is not in the working tree, so those rows are not tappable:
 * there is no file at that line to open.
 */
@Composable
internal fun DiffPage(state: DiffState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        DiffHeader(state)
        HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
        when {
            state.loading -> Note("Loading diff…", spinner = true)
            state.error != null -> Note(state.error.orEmpty())
            state.lines.isEmpty() -> Note("No differences to show.")
            else -> DiffBody(state)
        }
    }
}

@Composable
private fun DiffHeader(state: DiffState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            imageVector = jcIcon(JCodeIcon.Files),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.lg),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.openable) {
            CompactOutlinedButton(
                text = "Open file",
                onClick = { state.openAt(state.firstChangedLine) },
            )
        }
    }
}

@Composable
private fun DiffBody(state: DiffState) {
    // One horizontal scroll for the whole body rather than per row: a diff's rows must move together
    // or the columns stop lining up, which is the one thing a diff has to get right.
    val horizontal = rememberScrollState()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.files.isNotEmpty()) {
            item { StashFileList(state) }
        }
        items(state.lines) { line ->
            DiffRow(line, horizontal) { if (state.openable && line.openLine > 0) state.openAt(line.openLine) }
        }
        item { Box(modifier = Modifier.size(Space.xxl)) }
    }
}

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

@Composable
private fun StatusLetter(code: String) {
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

@Composable
private fun DiffRow(line: DiffLine, horizontal: androidx.compose.foundation.ScrollState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when (line.kind) {
        DiffLineKind.Add -> JCodeTheme.semanticColors.success.copy(alpha = 0.12f)
        DiffLineKind.Delete -> colors.error.copy(alpha = 0.12f)
        DiffLineKind.Hunk -> colors.primary.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val text = when (line.kind) {
        DiffLineKind.Add -> JCodeTheme.semanticColors.success
        DiffLineKind.Delete -> colors.error
        DiffLineKind.Hunk -> colors.primary
        DiffLineKind.Meta -> colors.onSurfaceVariant.copy(alpha = 0.7f)
        DiffLineKind.Context -> colors.onSurface
    }
    val tappable = line.kind != DiffLineKind.Meta && line.openLine > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .then(if (tappable) Modifier.clickable(onClick = onClick).handCursor() else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line.gutter,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant.copy(alpha = 0.55f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(GutterWidth).padding(end = Space.sm),
        )
        Text(
            text = line.text.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = text,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.horizontalScroll(horizontal).padding(end = Space.lg),
        )
    }
}

/** Room for four digits, which covers the files anyone reads a diff of on a phone. */
private val GutterWidth = 52.dp

@Composable
internal fun Note(text: String, spinner: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (spinner) {
            CircularProgressIndicator(modifier = Modifier.size(IconSize.sm), strokeWidth = StrokeWidth.thick)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
