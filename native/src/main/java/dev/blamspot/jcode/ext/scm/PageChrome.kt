package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.blamspot.jcode.design.ControlSize
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor

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

/**
 * A two-or-more-way switch where exactly one option is on.
 *
 * A filled segment inside a track reads as "this one is selected"; two independent filled pills
 * side by side read as two things shouting to be pressed. That difference is the whole reason this
 * is one control rather than a row of toggles.
 */
@Composable
internal fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row {
            options.forEach { option ->
                val on = option == selected
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable { onSelect(option) }
                        .handCursor(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .defaultMinSize(minHeight = ControlSize.compactHeight)
                            .padding(ControlSize.compactPadding),
                    ) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A setting that is simply on or off.
 *
 * Tinted rather than filled when on: it is a preference about how the page is drawn, not an action,
 * and it should not compete with the content it is a preference about.
 */
@Composable
internal fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = if (on) colors.secondaryContainer else Color.Transparent,
        contentColor = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (on) null else BorderStroke(StrokeWidth.hairline, colors.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(Radius.pill)).clickable(onClick = onClick).handCursor(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minHeight = ControlSize.compactHeight)
                .padding(ControlSize.compactPadding),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
