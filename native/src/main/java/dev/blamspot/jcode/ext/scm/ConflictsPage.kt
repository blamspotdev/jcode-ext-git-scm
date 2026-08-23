package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.handCursor
import dev.blamspot.jcode.design.jcIcon

/**
 * What is left to resolve in a merge.
 *
 * A page rather than the drawer's section because a merge that touches thirty files is exactly when
 * a column a few hundred pixels wide stops being enough — and because finishing a merge is a sitting
 * of its own, not something done past the corner of whatever else is open.
 */
@Composable
internal fun ConflictsPage(state: ConflictsState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        item {
            PageHeader(
                icon = ScmIcons.Tree,
                title = "Conflicts",
                subtitle = subtitleFor(state),
            ) {
                CompactOutlinedButton(
                    text = "Refresh",
                    onClick = { state.reload() },
                    enabled = !state.busy,
                )
            }
        }
        when {
            state.loading -> item { Note("Looking for a merge…", spinner = true) }
            state.error != null -> item { Note(state.error.orEmpty()) }
            state.files.isEmpty() -> item { Settled(state) }
            else -> {
                item {
                    Muted(
                        "Tap a file to resolve it side by side, or take one whole side when the " +
                            "file is not worth reading.",
                    )
                }
                items(state.files, key = { it.path }) { conflict -> ConflictRow(state, conflict) }
            }
        }
    }
}

private fun subtitleFor(state: ConflictsState): String = when {
    !state.merging -> "No merge in progress"
    state.from.isNotBlank() -> "Merging ${state.from} into ${state.into}"
    else -> "Merging into ${state.into}"
}

/**
 * Nothing left.
 *
 * Says so plainly rather than showing an empty list: an empty list is also what a broken query looks
 * like, and this is the one moment in a merge worth being sure about.
 */
@Composable
private fun Settled(state: ConflictsState) {
    Card(title = "") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Icon(
                imageVector = jcIcon(JCodeIcon.Save),
                contentDescription = null,
                tint = JCodeTheme.semanticColors.success,
                modifier = Modifier.size(IconSize.md),
            )
            Text(
                text = if (state.merging) "Every conflict is resolved." else "Nothing is conflicted.",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Muted(
            if (state.merging) {
                "The resolutions are staged. Commit from Source Control to finish the merge."
            } else {
                "This repository has no merge in progress."
            },
        )
    }
}

/** One unresolved file: open it, or settle it whole from either side. */
@Composable
private fun ConflictRow(state: ConflictsState, conflict: Conflict) {
    Card(title = "", modifier = Modifier.clickable { state.open(conflict) }.handCursor()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            StatusLetter("U")
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Git.baseName(conflict.path),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val folder = conflict.path.substringBeforeLast('/', "")
                if (folder.isNotEmpty()) {
                    Text(
                        text = folder,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                CompactOutlinedButton(
                    text = "Resolve…",
                    onClick = { state.open(conflict) },
                    enabled = !state.busy,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            // Whole-file shortcuts, named after the sides the editor uses so the two agree.
            ToggleChip(label = "Take theirs", on = false) { state.takeSide(conflict, ours = false) }
            ToggleChip(label = "Take mine", on = false) { state.takeSide(conflict, ours = true) }
        }
    }
}
