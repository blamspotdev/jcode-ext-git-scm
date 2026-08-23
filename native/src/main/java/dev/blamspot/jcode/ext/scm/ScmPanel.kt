package dev.blamspot.jcode.ext.scm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ControlSize
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor
import dev.blamspot.jcode.design.jcIcon

/**
 * The Source Control drawer panel.
 *
 * Drawn out of JCode's own parts — its spacing scale, its compact buttons, its icon bundle, and the
 * same semantic colours the Explorer badges use — so a file reads identically in the tree and here,
 * and the panel changes shape when the app's density does rather than holding its own opinion.
 */
@Composable
internal fun ScmPanel(state: ScmState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        PanelHeader(state)
        when {
            state.booting -> CenteredNote("Looking for a repository…", spinner = true)
            state.repos.isEmpty() -> CenteredNote(
                text = "No git repository here.",
                detail = state.projectPath?.let { "Start tracking ${Git.baseName(it)} with git." }
                    ?: "Open a project to use Source Control.",
                action = state.projectPath?.let {
                    {
                        CompactFilledButton(
                            text = "Initialize Repository",
                            onClick = { state.initRepo() },
                            enabled = !state.busy,
                            busy = state.busy,
                        )
                    }
                },
            )
            state.error != null -> RepoError(state)
            else -> RepoBody(state)
        }
    }
    state.log?.let { text -> LogSheet(text) { state.log = null } }
    state.confirm?.let { c -> ConfirmDialog(c) { state.confirm = null } }
}

/**
 * Repository, branch, and the things you do to a branch.
 *
 * The title only shows when there is no repository. Once there is one, the branch takes its place:
 * the drawer already says "SCM" on the tool that opened it, and a headline repeating that was a row
 * of a short panel spent on something the user had just clicked.
 */
@Composable
private fun PanelHeader(state: ScmState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.ms, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (state.repos.size > 1) RepoChip(state)
        if (state.repo != null) Toolbar(state) else TitleRow(state)
    }
    HorizontalDivider(thickness = StrokeWidth.hairline, color = MaterialTheme.colorScheme.outlineVariant)
}

/** What the panel calls itself while it has no repository to name instead. */
@Composable
private fun TitleRow(state: ScmState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Source Control",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (state.busy) BusySpinner() else HeaderIcon(jcIcon(JCodeIcon.Refresh), "Refresh") { state.boot() }
    }
}

@Composable
private fun BusySpinner() {
    Box(modifier = Modifier.size(ControlSize.iconButtonSm), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(IconSize.sm), strokeWidth = StrokeWidth.thick)
    }
}

/**
 * Branch chip, then the things you do to a branch.
 *
 * The chip is first and takes the room it needs: which branch you are on is the single most
 * consequential fact in the panel, and truncating it to fit more buttons gets that backwards.
 */
@Composable
private fun Toolbar(state: ScmState) {
    var branchMenu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        PopoverAnchor(
            expanded = branchMenu,
            onDismiss = { branchMenu = false },
            anchor = {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.lg))
                        .clickable { branchMenu = true; state.loadBranches() }
                        .handCursor(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
                    ) {
                        Icon(
                            imageVector = ScmIcons.Branch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IconSize.xs),
                        )
                        Text(
                            text = state.branch,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp),
                        )
                    }
                }
            },
        ) {
            BranchMenu(state) { branchMenu = false }
        }
        Box(modifier = Modifier.weight(1f))
        HeaderIcon(ScmIcons.Pull, "Pull", badge = state.behind) { state.pull() }
        HeaderIcon(ScmIcons.Push, "Push", badge = state.ahead) { state.push() }
        HeaderIcon(
            if (state.viewMode == ViewMode.Tree) ScmIcons.List else ScmIcons.Tree,
            if (state.viewMode == ViewMode.Tree) "Show as list" else "Show as tree",
        ) { state.toggleViewMode() }
        // Sign-in and the history page behind one button: a drawer this narrow cannot hold five tap
        // targets in a row without the branch name losing to them.
        var overflow by remember { mutableStateOf(false) }
        PopoverAnchor(
            expanded = overflow,
            onDismiss = { overflow = false },
            alignEnd = true,
            anchor = { HeaderIcon(jcIcon(JCodeIcon.MoreVert), "More") { overflow = true } },
        ) {
            PopoverItem("Branches & history…", ScmIcons.Branch) { overflow = false; state.openManage() }
            PopoverItem("GitHub sign-in…", ScmIcons.GitHub) { overflow = false; state.openGitHub() }
        }
    }
}

/**
 * Which repository the panel is looking at, when the workspace holds more than one.
 *
 * Absent otherwise — a chip that always reads "the only repo" is a row of the panel spent saying
 * nothing.
 */
@Composable
private fun RepoChip(state: ScmState) {
    var menu by remember { mutableStateOf(false) }
    val active = state.repo ?: return
    PopoverAnchor(
        expanded = menu,
        onDismiss = { menu = false },
        anchor = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xxs),
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.md))
                .clickable { menu = true }
                .handCursor()
                .padding(horizontal = Space.xs, vertical = Space.xxs),
        ) {
            Text(
                text = "Repo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = active.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Icon(
                imageVector = jcIcon(JCodeIcon.ChevronDown),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.xs),
            )
        }
        },
    ) {
        PopoverLabel("Repositories")
        state.repos.forEach { info ->
            PopoverItem(
                label = info.name,
                icon = if (info.root == active.root) jcIcon(JCodeIcon.Folder) else null,
                selected = info.root == active.root,
            ) { menu = false; state.selectRepo(info) }
        }
    }
}

/**
 * Switch branch, or start one.
 *
 * The branches come after the two actions rather than before them: the list is unbounded and the
 * actions are not, and a menu whose fixed entries move down as the repository grows is a menu you
 * have to read every time.
 */
@Composable
private fun ColumnScope.BranchMenu(state: ScmState, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    if (creating) {
        CompactField(
            value = newName,
            onValueChange = { newName = it },
            placeholder = "New branch name",
            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xxs),
        )
        PopoverItem(
            label = "Create and switch",
            icon = jcIcon(JCodeIcon.Add),
            enabled = newName.isNotBlank(),
        ) { state.createBranch(newName); newName = ""; creating = false; onDismiss() }
        return
    }
    PopoverItem("New branch…", jcIcon(JCodeIcon.Add)) { creating = true }
    PopoverItem("Branches & history…", ScmIcons.Branch) { onDismiss(); state.openManage() }
    if (state.branches.isNotEmpty()) {
        PopoverDivider()
        PopoverLabel("Switch to")
        state.branches.forEach { name ->
            PopoverItem(
                label = name,
                icon = if (name == state.branch) ScmIcons.Branch else null,
                selected = name == state.branch,
            ) { if (name != state.branch) state.switchBranch(name); onDismiss() }
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, badge: Int = 0, onClick: () -> Unit) {
    Box {
        IconButton(onClick = onClick, modifier = Modifier.size(ControlSize.iconButtonSm).handCursor()) {
            Icon(
                imageVector = icon,
                contentDescription = if (badge > 0) "$label ($badge)" else label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.sm),
            )
        }
        if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * How far out of step this branch is, on the button that fixes it.
 *
 * These counts used to sit as `↓2 ↑1` beside the branch chip — a third thing competing for the one
 * line that already had the branch name and the toolbar, and a number nowhere near the control it
 * described. On the buttons the arrow says which direction and the badge says how much.
 *
 * Capped at 9+, because past a certain point the answer is "a lot" and the digits stop fitting on a
 * twenty-eight pixel button.
 */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            fontSize = BadgeFontSize,
            lineHeight = BadgeFontSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = Space.xxs, vertical = Space.hairline),
        )
    }
}

/** Small enough to sit on the icon rather than replace it. */
private val BadgeFontSize = 9.sp

/**
 * The commit box and the file lists, in one scroller.
 *
 * The commit box scrolls with the lists rather than sitting pinned above them. Pinned, it took a
 * fixed share of a drawer that in landscape is only a few hundred pixels tall — and when the commit
 * failed for want of a git identity, the two fields and their Save button grew past the bottom edge
 * with nothing to scroll.
 *
 * Pulling the list down fetches. That is where the gesture already points — you reach for it to ask
 * "is this still true?" — and it buys back a tap target from a header row that had five of them
 * competing with the branch name.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RepoBody(state: ScmState) {
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { state.fetch() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = Space.sm)) {
            item {
                Column {
                    CommitBox(state)
                    HorizontalDivider(
                        thickness = StrokeWidth.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }

            if (state.conflicts.isNotEmpty()) {
                stickyHeader { SectionHeader(state, "Conflicts", state.conflicts.size) }
                if ("Conflicts" !in state.collapsedSections) {
                    section(state, state.conflicts, Section.Conflict)
                }
            }

            stickyHeader {
                SectionHeader(state, "Staged", state.staged.size) {
                    if (state.staged.isNotEmpty()) BulkAction("Unstage all") { state.unstageAll() }
                }
            }
            if ("Staged" !in state.collapsedSections) section(state, state.staged, Section.Staged)

            stickyHeader {
                SectionHeader(state, "Changes", state.unstaged.size) {
                    if (state.unstaged.isNotEmpty()) {
                        BulkAction("Stage all") { state.stageAll() }
                        BulkAction("Discard all") { state.discardAll() }
                        BulkAction("Stash") { state.stashPush() }
                    }
                }
            }
            if ("Changes" !in state.collapsedSections) section(state, state.unstaged, Section.Unstaged)

            // One line for the whole clean tree rather than a sentence under each empty heading:
            // "No staged changes." above "No changes." said the same thing twice and took two rows
            // of a panel that has few to spare.
            if (state.staged.isEmpty() && state.unstaged.isEmpty() && state.conflicts.isEmpty()) {
                item { EmptyLine("Nothing to commit — the working tree is clean.") }
            }

            if (state.stashes.isNotEmpty()) {
                stickyHeader {
                    SectionHeader(state, "Stashes", state.stashes.size) {
                        BulkAction("Pop latest") { state.stashPopLatest() }
                    }
                }
                if ("Stashes" !in state.collapsedSections) {
                    items(state.stashes) { StashRow(state, it) }
                }
            }

            item { Box(modifier = Modifier.size(Space.md)) }
        }
    }
}

/** One section's rows, as a tree or a flat list depending on the toggle. */
private fun LazyListScope.section(state: ScmState, files: List<FileEntry>, section: Section) {
    if (state.viewMode == ViewMode.List) {
        items(files) { FileRow(state, it, section, depth = 0) }
        return
    }
    val rows = buildTreeRows(files, state.collapsedFolders.toSet())
    items(rows) { row ->
        when (row) {
            is TreeRow.Folder -> FolderRow(state, row)
            is TreeRow.File -> FileRow(state, row.entry, section, row.depth)
        }
    }
}

@Composable
private fun FolderRow(state: ScmState, row: TreeRow.Folder) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { state.toggleFolder(row.key) }
            .handCursor()
            .padding(
                start = Space.xs + (row.depth * 12).dp,
                end = Space.xs,
                top = Space.xxs,
                bottom = Space.xxs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        Icon(
            imageVector = jcIcon(if (row.collapsed) JCodeIcon.ChevronRight else JCodeIcon.ChevronDown),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.xs),
        )
        Icon(
            imageVector = jcIcon(JCodeIcon.Folder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.xs),
        )
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StashRow(state: ScmState, entry: StashEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { state.openStash(entry) }
            .handCursor()
            .padding(start = Space.xs, end = Space.xxs, top = Space.xxs, bottom = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.desc.ifBlank { entry.ref },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.ref,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowAction(ScmIcons.Pull, "Apply") { state.stashApply(entry) }
        RowAction(ScmIcons.Push, "Pop") { state.stashPop(entry) }
        RowAction(jcIcon(JCodeIcon.Delete), "Drop", MaterialTheme.colorScheme.error) { state.stashDrop(entry) }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = Space.ms, end = Space.xs, top = Space.xxs, bottom = Space.xs),
    )
}

@Composable
private fun CommitBox(state: ScmState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        CompactField(
            value = state.commitMessage,
            onValueChange = { state.commitMessage = it },
            placeholder = "Message",
            // Two rows before anything is typed, six at most. One row read as a search box, and a
            // commit message is a subject and a body; six is where it stops eating the file list.
            minLines = 2,
            maxLines = 6,
            // Beside the message rather than floating in its corner: drafting one is something you
            // do to the field, and an icon parked over the text was in the way of reading it.
            trailing = if (!state.generateEnabled) null else {
                {
                    if (state.generating) {
                        Box(
                            modifier = Modifier.size(ControlSize.iconButtonSm),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(IconSize.xs),
                                strokeWidth = StrokeWidth.thick,
                            )
                        }
                    } else {
                        RowAction(ScmIcons.Sparkle, "Generate a commit message") {
                            state.generateCommitMessage()
                        }
                    }
                }
            },
        )
        var menu by remember { mutableStateOf(false) }
        SplitButton(
            label = "Commit",
            onClick = { state.commitVariant(CommitVariant.Plain) },
            enabled = state.canCommit,
            busy = state.busy,
            menuEnabled = !state.busy,
            menuOpen = menu,
            onMenuOpen = { menu = true },
            onMenuDismiss = { menu = false },
        ) {
            // Plain Commit is the button this menu hangs off, so it is not repeated inside it: the
            // menu carries the variants, which is the only reason to open it.
            //
            // Amend is the one that works without a message: it reuses the last commit's.
            PopoverItem("Commit (Amend)", jcIcon(JCodeIcon.Undo), enabled = !state.busy) {
                menu = false; state.commitVariant(CommitVariant.Amend)
            }
            PopoverItem("Commit & Push", ScmIcons.Push, enabled = state.canCommit) {
                menu = false; state.commitVariant(CommitVariant.Push)
            }
            PopoverItem("Commit & Sync", ScmIcons.Fetch, enabled = state.canCommit) {
                menu = false; state.commitVariant(CommitVariant.Sync)
            }
        }
        if (state.needsIdentity) IdentityForm(state)
    }
}

/**
 * Commit, and the other three ways to commit.
 *
 * One control, not two. A separate pill holding a "⌄" glyph read as a second button that happened to
 * be next to the first, and set that glyph as a *label* — sitting where a word would, at a word's
 * size, in a pill wide enough for one. Joined along a hairline, the caret is plainly the menu
 * belonging to the button beside it, and it takes a quarter of the width doing so.
 *
 * The halves are enabled separately, which is the reason a split button exists here at all: Commit
 * needs a message and something staged, while Amend — behind the caret — needs neither.
 */
@Composable
private fun SplitButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean,
    menuEnabled: Boolean,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    menu: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ControlSize.compactHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitHalf(
            enabled = enabled && !busy,
            shape = RoundedCornerShape(
                topStart = Radius.pill,
                bottomStart = Radius.pill,
                topEnd = Radius.none,
                bottomEnd = Radius.none,
            ),
            onClick = onClick,
            modifier = Modifier.weight(1f),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(IconSize.xs),
                    strokeWidth = StrokeWidth.thick,
                    color = LocalContentColor.current,
                )
            } else {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
        // Hairline rather than a gap: a gap would make them two controls again.
        Box(
            modifier = Modifier
                .width(StrokeWidth.hairline)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)),
        )
        PopoverAnchor(
            expanded = menuOpen,
            onDismiss = onMenuDismiss,
            alignEnd = true,
            anchor = {
                SplitHalf(
                    enabled = menuEnabled,
                    shape = RoundedCornerShape(
                        topStart = Radius.none,
                        bottomStart = Radius.none,
                        topEnd = Radius.pill,
                        bottomEnd = Radius.pill,
                    ),
                    onClick = onMenuOpen,
                    modifier = Modifier.width(SplitCaretWidth).fillMaxHeight(),
                ) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.ChevronDown),
                        contentDescription = "More commit actions",
                        modifier = Modifier.size(IconSize.sm),
                    )
                }
            },
            content = menu,
        )
    }
}

/** Wide enough for a chevron and a thumb, narrow enough to stay the smaller half. */
private val SplitCaretWidth = 40.dp

/**
 * One half of a [SplitButton], wearing the filled-tonal colours the app's compact buttons use.
 *
 * Written out rather than borrowed from `CompactFilledButton`, which owns its own shape: the whole
 * point here is that the two halves round only on their outer edges.
 */
@Composable
private fun SplitHalf(
    enabled: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (enabled) colors.secondaryContainer else colors.onSurface.copy(alpha = 0.12f),
        contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = 0.38f),
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .handCursor(),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * A text field the size of its text.
 *
 * Material's `OutlinedTextField` reserves fifty-six density-independent pixels before it holds
 * anything, which is most of a phone-height drawer spent on three of them. This is a bordered
 * surface around a `BasicTextField`, so an empty one is a line of text tall and grows only when the
 * text does.
 */
@Composable
internal fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 1,
    /** For a value the keyboard must not help with — a URL, a username, an email, a token. */
    literal: Boolean = false,
    password: Boolean = false,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    // The app's own field, down to the type scale: SettingsTextFieldRow and CompactSearchField agree
    // on Radius.xl, a quarter-strength surface tint and a thin outline at 60%, and this matches the
    // rest of it too — bodyMedium, a placeholder at 60%, Space.ms of horizontal room, and the same
    // 40 / 96dp heights. Written out rather than reused because neither of those takes a trailing
    // action or a multi-line commit message; but a field that is *nearly* the app's field reads as
    // a mistake rather than as a choice, so it is the app's field or it is nothing.
    val single = maxLines == 1
    Surface(
        shape = RoundedCornerShape(Radius.xl),
        color = colors.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(StrokeWidth.thin, colors.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            // The row stays centred so a trailing action sits in the middle of the box however tall
            // it grows; the text inside it starts at the top, which is where a message begins.
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = Space.ms,
                end = if (trailing == null) Space.ms else Space.xxs,
            ),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    // Only the one-line case takes a floor. A multi-line field's height is
                    // [minLines], which the message box sets deliberately — the app's own 96dp
                    // would quietly overrule it in the name of matching the app.
                    .heightIn(min = if (single) SingleLineHeight else Dp.Unspecified)
                    .padding(vertical = if (single) Space.none else Space.sm),
                contentAlignment = if (single) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = colors.onSurface,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    minLines = minLines,
                    maxLines = maxLines,
                    singleLine = maxLines == 1,
                    // Without a declared action the keyboard invents one, and in the landscape
                    // full-screen editor that came out as "EXECUTE" — a word for running something,
                    // over a field that only holds text. Naming it Done also gives the button
                    // something to do: put the keyboard away.
                    //
                    // Safe on the multi-line message box: Android gives a multi-line field a newline
                    // key and reaches the action only from the full-screen editor, so declaring one
                    // does not cost the message its line breaks.
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        capitalization = if (literal) KeyboardCapitalization.None
                        else KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = !literal,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    visualTransformation = if (password) PasswordVisualTransformation()
                    else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * The two fields git wants before it will attribute a commit.
 *
 * Shown where the commit was refused rather than sending the user to a settings page: the whole of
 * git's advice is a name and an email, and the panel can take both.
 */
@Composable
private fun IdentityForm(state: ScmState) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            text = "Git needs an identity for commits.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CompactField(value = name, onValueChange = { name = it }, placeholder = "Your name")
        CompactField(value = email, onValueChange = { email = it }, placeholder = "you@example.com")
        CompactFilledButton(
            text = "Save identity",
            onClick = { state.saveIdentity(name, email) },
            enabled = name.isNotBlank() && email.isNotBlank() && !state.busy,
            busy = state.busy,
        )
    }
}

/**
 * A section that folds away, with its count and its bulk actions.
 *
 * Deliberately quiet. The app's [ManagerGroupHeader] is built for settings and manager screens,
 * where a handful of headings sit far apart above big cards — used here it put a line of primary
 * blue at body size every few rows, and the filenames, which are the content, lost to their own
 * headings. A small tracked-out label in the muted colour recedes far enough for the list to read.
 *
 * The count sits against the title rather than floating off to the right: it belongs to the word,
 * and away at the margin it read as a separate control.
 *
 * The whole strip is the collapse target, so folding a section away is a tap anywhere along it
 * rather than an aim at a chevron.
 */
@Composable
private fun SectionHeader(
    state: ScmState,
    title: String,
    count: Int,
    actions: (@Composable () -> Unit)? = null,
) {
    val collapsed = title in state.collapsedSections
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The drawer sheet's own colour, so a header that has stuck to the top hides the rows
            // sliding under it instead of showing a lighter band across them.
            .background(MaterialTheme.colorScheme.surface)
            .clickable { state.toggleSection(title) }
            .handCursor()
            .padding(start = Space.xxs, end = Space.xxs, top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            imageVector = jcIcon(if (collapsed) JCodeIcon.ChevronRight else JCodeIcon.ChevronDown),
            contentDescription = if (collapsed) "Expand" else "Collapse",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.xs),
        )
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = SectionTracking,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CountChip(count)
        Box(modifier = Modifier.weight(1f))
        if (!collapsed) actions?.invoke()
    }
}

/** Wide enough to read as a label rather than a shouted word. */
private val SectionTracking = 0.08.em

@Composable
private fun CountChip(count: Int) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.none),
        )
    }
}

/**
 * "Stage all" and its neighbours — muted, because they act on everything.
 *
 * A bulk action in the accent colour sat at the same weight as the section it belonged to, so a
 * header of four blue words offered no clue which one was the heading.
 */
@Composable
private fun BulkAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .handCursor()
            .padding(horizontal = Space.xs, vertical = Space.xxs),
    )
}

// --- pop-overs ---------------------------------------------------------------------------------

/**
 * The panel's own menu surface.
 *
 * Material's `DropdownMenu` is built for a full-width screen: its rows are a touch target tall
 * apiece, so four of them stood taller than the file list they were opened from and buried the
 * branch you were trying to read. This keeps the same dismiss behaviour and the app's own surface,
 * shape and outline, at roughly half the height.
 *
 * Anchored by measuring: the popup is positioned against the anchor's own top corner and pushed down
 * by exactly the anchor's height, so it opens directly beneath whatever opened it rather than over
 * the top of it.
 */
@Composable
internal fun PopoverAnchor(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    anchor: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var anchorHeight by remember { mutableStateOf(0) }
    Box(modifier = modifier.onSizeChanged { anchorHeight = it.height }) {
        anchor()
        if (!expanded) return@Box
        Popup(
            alignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
            offset = IntOffset(0, anchorHeight + PopoverGapPx),
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = RoundedCornerShape(Radius.lg),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = PopoverElevation,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = PopoverMinWidth, max = PopoverMaxWidth)
                        .heightIn(max = PopoverMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Space.xs),
                    content = content,
                )
            }
        }
    }
}

private const val PopoverGapPx = 8
private val PopoverElevation = 8.dp
private val PopoverMinWidth = 176.dp
private val PopoverMaxWidth = 280.dp

/** Tall enough for a good handful of branches, short enough to stay a menu rather than a page. */
private val PopoverMaxHeight = 320.dp

/** One line of a pop-over. [selected] is for the entry you are already on. */
@Composable
internal fun PopoverItem(
    label: String,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .handCursor()
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // The icon column is held even when a row has no icon, so labels line up down the menu
        // instead of stepping in and out with whichever entries happen to carry one.
        Box(modifier = Modifier.size(IconSize.xs), contentAlignment = Alignment.Center) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else tint,
                    modifier = Modifier.size(IconSize.xs),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A heading inside a pop-over — the same quiet label the sections use. */
@Composable
internal fun PopoverLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = SectionTracking,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Space.sm, end = Space.sm, top = Space.xs, bottom = Space.xxs),
    )
}

@Composable
internal fun PopoverDivider() {
    HorizontalDivider(
        thickness = StrokeWidth.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = Space.xs),
    )
}

private enum class Section { Staged, Unstaged, Conflict }

/**
 * The status letter, in the colour the Explorer badges it with.
 *
 * Deliberately the same mapping rather than a similar one: a modified file is the same amber in the
 * tree and in this list, so the two views agree at a glance instead of needing to be read.
 */
@Composable
private fun statusColor(code: String): Color = when (code) {
    "M" -> JCodeTheme.semanticColors.warning
    "A", "?" -> JCodeTheme.semanticColors.success
    "D", "!" -> MaterialTheme.colorScheme.error
    "R", "C" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(state: ScmState, entry: FileEntry, section: Section, depth: Int) {
    val tint = statusColor(entry.code)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .combinedClickable(
                onClick = { state.openDiff(entry, section == Section.Staged) },
                // Hold for the file itself. A tap wants the change — that is what the row is showing —
                // and reaching the working copy through a diff page would be the long way round.
                onLongClick = { state.openFile(entry) },
            )
            .handCursor()
            .padding(
                start = Space.xs + (depth * 12).dp,
                end = Space.xxs,
                top = Space.xxs,
                bottom = Space.xxs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        StatusBadge(entry.code, tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.display.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only in the flat list: the tree already put the folder in a row of its own, and
            // repeating it under every file is noise the indent has already said.
            if (state.viewMode == ViewMode.List) {
                entry.display.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { dir ->
                    Text(
                        text = dir,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when (section) {
            Section.Staged -> RowAction(jcIcon(JCodeIcon.Minus), "Unstage") { state.unstage(entry) }
            Section.Unstaged -> {
                RowAction(jcIcon(JCodeIcon.Discard), "Discard", MaterialTheme.colorScheme.error) {
                    state.discard(entry)
                }
                RowAction(jcIcon(JCodeIcon.Add), "Stage") { state.stage(entry) }
            }
            // A conflict is resolved in the editor, not by a button here.
            Section.Conflict -> Unit
        }
    }
}

/** The status letter as a small tinted square, the way the Explorer draws it. */
@Composable
private fun StatusBadge(code: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(Radius.xs),
        color = tint.copy(alpha = 0.16f),
        modifier = Modifier.size(width = IconSize.sm, height = IconSize.sm),
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
private fun RowAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(ControlSize.iconButtonSm).handCursor()) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(IconSize.xs),
        )
    }
}

@Composable
private fun RepoError(state: ScmState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Space.ms),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = "Couldn't read this repository.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
        )
        Surface(
            shape = RoundedCornerShape(Radius.md),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Text(
                text = state.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Space.sm),
            )
        }
        CompactOutlinedButton(text = "Refresh", onClick = { state.boot() }, icon = JCodeIcon.Refresh)
    }
}

@Composable
private fun CenteredNote(
    text: String,
    detail: String? = null,
    spinner: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize().padding(Space.xl), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (spinner) {
                CircularProgressIndicator(modifier = Modifier.size(IconSize.md), strokeWidth = StrokeWidth.thick)
            } else {
                Surface(
                    shape = RoundedCornerShape(Radius.xxl),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Icon(
                        imageVector = ScmIcons.Branch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(Space.md).size(IconSize.xl),
                    )
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.let {
                Box(modifier = Modifier.padding(top = Space.xs)) { it() }
            }
        }
    }
}

/**
 * What git said, on the screen rather than in the drawer.
 *
 * A push, a failed merge or a first commit writes a paragraph, and the drawer is the wrong shape for
 * one: at a few hundred pixels wide every line wrapped twice and the tail ran off the bottom. As a
 * dialog it gets the app's full dialog width, and scrolls when git is feeling verbose.
 */
@Composable
private fun LogSheet(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Git") },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = LogMaxHeight)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { CompactOutlinedButton(text = "Close", onClick = onDismiss) },
    )
}

/** Tall enough for a commit summary, short enough to leave the dialog looking like a dialog. */
private val LogMaxHeight = 320.dp

/** The height a one-line field settles at in the app, so one in a panel is not a different size. */
private val SingleLineHeight = 40.dp
