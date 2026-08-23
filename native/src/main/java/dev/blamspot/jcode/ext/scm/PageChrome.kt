package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth

/**
 * The parts every page of this extension is built from.
 *
 * Six surfaces open in the editor area — sign-in, manage, clone, remote repositories, a diff, a
 * merge — and they are one extension, so they have to look like one. Written once here rather than
 * per page: a header that drifted by a few pixels between two of them would read as two extensions.
 */

/** Icon, title, one line of explanation, and whatever action the page leads with. */
@Composable
internal fun PageHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    monospaceTitle: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(IconSize.xl),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (monospaceTitle) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.headlineSmall,
                fontFamily = if (monospaceTitle) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

/** A titled slab. Every page is a stack of these, the way the settings screens are. */
@Composable
internal fun Card(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radius.xxl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (title.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        thickness = StrokeWidth.hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** A caption above a field, so a form reads as labelled rows rather than a column of placeholders. */
@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Explanatory prose inside a card — what a setting means, or what a page is about to do. */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** How the last action went, in the colour that says which. */
@Composable
internal fun StatusText(message: String, isError: Boolean) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else JCodeTheme.semanticColors.success,
    )
}

/**
 * What a command actually printed.
 *
 * Scrolls sideways rather than wrapping: git's own output is laid out in columns, and a clone that
 * failed says why on a line long enough that wrapping it loses the shape.
 */
@Composable
internal fun LogBlock(text: String, modifier: Modifier = Modifier) {
    val horizontal = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier.horizontalScroll(horizontal).padding(Space.sm),
        )
    }
}

/** A page with nothing to show yet, or nothing to show at all. */
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
