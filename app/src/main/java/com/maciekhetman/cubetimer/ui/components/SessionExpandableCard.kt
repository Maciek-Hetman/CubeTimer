package com.maciekhetman.cubetimer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.ui.screens.HistorySolveCard
import com.maciekhetman.cubetimer.viewmodel.SessionGroupUiModel

internal val GroupOuterCorner = 28.dp
internal val GroupInnerCorner = 6.dp
internal val GroupSegmentGap = 2.dp

/**
 * A session on the History screen, drawn as one connected group of tonal segments:
 * the header, one row per solve and an action row. Outer corners are large, inner
 * corners small, and the header's bottom corners morph as the group opens.
 */
@Composable
fun SessionExpandableCard(
    sessionGroup: SessionGroupUiModel,
    isSelectionMode: Boolean,
    selectedSolveIds: Set<String>,
    onToggleExpand: () -> Unit,
    onExportSession: () -> Unit,
    onDeleteSession: () -> Unit,
    onSolveClick: (SolveTime, Int) -> Unit,
    onSolveLongClick: (SolveTime) -> Unit,
    onToggleSolveSelection: (String) -> Unit,
    onTogglePlusTwo: (SolveTime) -> Unit,
    onToggleDnf: (SolveTime) -> Unit,
    onDeleteSolve: (SolveTime) -> Unit,
    modifier: Modifier = Modifier
) {
    val expanded = sessionGroup.isExpanded

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GroupSegmentGap)
    ) {
        SessionCardHeader(
            sessionGroup = sessionGroup,
            expanded = expanded,
            onClick = onToggleExpand
        )

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut() + shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(GroupSegmentGap)) {
                if (sessionGroup.solves.isEmpty()) {
                    SessionCardEmptyOrLoadingMessage(sessionGroup)
                } else {
                    sessionGroup.solves.forEachIndexed { index, solve ->
                        SessionCardSolveRow(
                            sessionGroup = sessionGroup,
                            solve = solve,
                            index = index,
                            isSelectionMode = isSelectionMode,
                            selectedSolveIds = selectedSolveIds,
                            onSolveClick = onSolveClick,
                            onSolveLongClick = onSolveLongClick,
                            onToggleSolveSelection = onToggleSolveSelection,
                            onTogglePlusTwo = onTogglePlusTwo,
                            onToggleDnf = onToggleDnf,
                            onDeleteSolve = onDeleteSolve
                        )
                    }
                }

                SessionCardActions(
                    onExport = onExportSession,
                    onDelete = onDeleteSession
                )
            }
        }
    }
}

/**
 * Single solve row inside an expanded session group, extracted so it can also be emitted as its
 * own `LazyColumn` item (see `HistoryScreen`) instead of being composed eagerly for every solve
 * in a large expanded session.
 */
@Composable
fun SessionCardSolveRow(
    sessionGroup: SessionGroupUiModel,
    solve: SolveTime,
    index: Int,
    isSelectionMode: Boolean,
    selectedSolveIds: Set<String>,
    onSolveClick: (SolveTime, Int) -> Unit,
    onSolveLongClick: (SolveTime) -> Unit,
    onToggleSolveSelection: (String) -> Unit,
    onTogglePlusTwo: (SolveTime) -> Unit,
    onToggleDnf: (SolveTime) -> Unit,
    onDeleteSolve: (SolveTime) -> Unit,
    modifier: Modifier = Modifier
) {
    val solveNumber = sessionGroup.solveNumbers[solve.id]
        ?: (sessionGroup.solveCount - index).coerceAtLeast(1)
    HistorySolveCard(
        solve = solve,
        solveNumber = solveNumber,
        onClick = {
            if (isSelectionMode) {
                onToggleSolveSelection(solve.id)
            } else {
                onSolveClick(solve, solveNumber)
            }
        },
        onDelete = { onDeleteSolve(solve) },
        onTogglePlusTwo = { onTogglePlusTwo(solve) },
        onToggleDnf = { onToggleDnf(solve) },
        isSelected = solve.id in selectedSolveIds,
        isSelectionMode = isSelectionMode,
        onLongClick = { onSolveLongClick(solve) },
        modifier = modifier
    )
}

/** The "loading" or "no solves" placeholder shown in place of the solve rows. */
@Composable
fun SessionCardEmptyOrLoadingMessage(sessionGroup: SessionGroupUiModel, modifier: Modifier = Modifier) {
    if (sessionGroup.isSolvesLoading && sessionGroup.solves.isEmpty()) {
        SegmentMessage(modifier) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else {
        SegmentMessage(modifier) {
            Text(
                text = if (sessionGroup.solveCount == 0) {
                    "No solves in this session"
                } else {
                    "No solves match your filters"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The tappable header row of a session group: name, summary, best/average, and expand chevron. */
@Composable
fun SessionCardHeader(
    sessionGroup: SessionGroupUiModel,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bottomCorner by animateDpAsState(
        targetValue = if (expanded) GroupInnerCorner else GroupOuterCorner,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "session_header_corner"
    )
    val containerColor by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "session_header_color"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "session_header_chevron"
    )

    val session = sessionGroup.session
    val solveCountText = "${sessionGroup.solveCount} ${if (sessionGroup.solveCount == 1) "solve" else "solves"}"
    val kindText = if (session.kind == SessionKind.MANUAL) "Manual" else "Auto"

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(
            topStart = GroupOuterCorner,
            topEnd = GroupOuterCorner,
            bottomStart = bottomCorner,
            bottomEnd = bottomCorner
        ),
        color = containerColor,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$solveCountText · ${session.event.displayName} · $kindText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val best = sessionGroup.formattedBest
                val average = sessionGroup.formattedAvg
                if (best != null || average != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        best?.let { SessionStat(label = "Best", value = it) }
                        if (best != null && average != null) Spacer(modifier = Modifier.width(24.dp))
                        average?.let { SessionStat(label = "Average", value = it) }
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse session" else "Expand session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation)
            )
        }
    }
}

@Composable
private fun SessionStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SegmentMessage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(GroupInnerCorner),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/** The export/delete action row shown at the bottom of an expanded session group. */
@Composable
fun SessionCardActions(
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(
            topStart = GroupInnerCorner,
            topEnd = GroupInnerCorner,
            bottomStart = GroupOuterCorner,
            bottomEnd = GroupOuterCorner
        ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onExport) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text("Export")
            }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text("Delete")
            }
        }
    }
}
