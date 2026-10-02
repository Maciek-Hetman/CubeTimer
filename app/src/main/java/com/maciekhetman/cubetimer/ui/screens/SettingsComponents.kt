package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.R
import kotlin.math.roundToInt

// Building blocks of the Settings screen, styled after Material 3 Expressive segmented lists: a section
// is a header over a group of separate tiles 2dp apart. The group rounds the outer corners; each tile
// keeps small inner corners that round up while it is pressed. material3 1.4 (the stable release this
// app uses) keeps the Expressive components internal, so these follow the spec's sizes, shapes and
// springs with stable pieces.

/** Gap between the tiles of a group; the screen background shows through it. */
private val ItemGap = 2.dp
private val ItemCornerRadius = 4.dp
private val ItemPressedCornerRadius = 16.dp
private val ItemIconContainerSize = 40.dp

private val ConnectedButtonHeight = 40.dp
private val ConnectedButtonGap = 2.dp
private val ConnectedButtonInnerCornerRadius = 8.dp
private val ConnectedButtonPressedInnerCornerRadius = 4.dp

private val ChipHeight = 32.dp
private val ChipCornerRadius = ChipHeight / 2
private val ChipSelectedCornerRadius = 8.dp

private const val DisabledContentAlpha = 0.38f

// The Expressive motion scheme's springs.
private fun <T> fastSpatialSpring(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
private fun <T> defaultSpatialSpring(visibilityThreshold: T? = null): SpringSpec<T> =
    spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = visibilityThreshold)
private fun <T> fastEffectsSpring(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

/**
 * A titled group of settings. Each child is one tile (a `Setting*Row` / [SettingsItem]); the group
 * spaces them [ItemGap] apart and gives the first and last the large outer corners, so rows shown
 * conditionally need no bookkeeping. A null [title] draws the group alone.
 */
@Composable
fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                    .semantics { heading() }
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large),
            verticalArrangement = Arrangement.spacedBy(ItemGap),
            content = content
        )
    }
}

/** Shows or hides a tile of a [SettingsSection], growing it in and out with an expressive spring. */
@Composable
fun ColumnScope.AnimatedSettingsItem(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(defaultSpatialSpring(IntSize.VisibilityThreshold)) + fadeIn(fastEffectsSpring()),
        exit = shrinkVertically(defaultSpatialSpring(IntSize.VisibilityThreshold)) + fadeOut(fastEffectsSpring())
    ) {
        content()
    }
}

/** The leading icon of a settings row: a tinted glyph on a round tonal badge. */
@Composable
fun SettingsIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Box(
        modifier = modifier
            .size(ItemIconContainerSize)
            .background(containerColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * One settings tile: an optional leading badge ([icon], or any [leadingContent]), a title over optional
 * supporting text, optional trailing content and, below them, optional full-width [bottomContent] such
 * as a slider. With [onClick] the whole tile is a button whose corners round up while pressed.
 */
@Composable
fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    supportingTextColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    titleMaxLines: Int = Int.MAX_VALUE,
    titleOverflow: TextOverflow = TextOverflow.Clip,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    SettingsItemFrame(
        modifier = modifier,
        interactionSource = interactionSource,
        interactionModifier = if (onClick != null) {
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                role = role,
                onClick = onClick
            )
        } else {
            Modifier
        }
    ) {
        SettingsItemContent(
            title = title,
            enabled = enabled,
            titleStyle = titleStyle,
            titleMaxLines = titleMaxLines,
            titleOverflow = titleOverflow,
            icon = icon,
            leadingContent = leadingContent,
            supportingText = supportingText,
            supportingTextColor = supportingTextColor,
            supportingContent = supportingContent,
            trailingContent = trailingContent,
            bottomContent = bottomContent
        )
    }
}

/** A tile that opens a link or a dialog, marked with an "open in new" arrow or a chevron. */
@Composable
fun SettingLinkRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    opensExternally: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    SettingsItem(
        title = title,
        modifier = modifier,
        icon = icon,
        supportingText = supportingText,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        trailingContent = {
            if (opensExternally) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                SettingsChevron()
            }
        }
    )
}

/** The trailing chevron of a tile that opens something inside the app. */
@Composable
fun SettingsChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
    )
}

/** A tile whose switch is the whole row; the switch thumb carries a check mark when on. */
@Composable
fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    SettingsItemFrame(
        modifier = modifier,
        interactionSource = interactionSource,
        interactionModifier = Modifier.toggleable(
            value = checked,
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { isOn ->
                haptic.performHapticFeedback(
                    if (isOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                )
                onCheckedChange(isOn)
            }
        )
    ) {
        SettingsItemContent(
            title = title,
            enabled = enabled,
            icon = icon,
            supportingText = supportingText,
            trailingContent = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    // Shared with the row, so pressing anywhere on it grows the thumb.
                    interactionSource = interactionSource,
                    thumbContent = if (checked) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        )
    }
}

/**
 * A tile with a discrete slider under its title and the current value on the right. The value follows
 * the thumb while dragging; [onValueChangeFinished] receives it, clamped to [valueRange], on release.
 * The slider carries the test tag `<title_in_snake_case>_slider`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSliderRow(
    title: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    sliderModifier: Modifier = Modifier,
    valueFormatter: @Composable (Int) -> String = { stringResource(R.string.settings_value_millis, it) },
    icon: ImageVector? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (Int) -> Unit
) {
    val clampedValue = value.toFloat().coerceIn(valueRange.start, valueRange.endInclusive)
    var sliderValue by remember(value, valueRange) { mutableFloatStateOf(clampedValue) }
    var lastHapticValue by remember(value, valueRange) { mutableIntStateOf(clampedValue.roundToInt()) }
    val haptic = LocalHapticFeedback.current

    SettingsItemFrame(
        modifier = modifier,
        interactionSource = remember { MutableInteractionSource() },
        interactionModifier = Modifier
    ) {
        SettingsItemContent(
            title = title,
            enabled = enabled,
            icon = icon,
            supportingText = supportingText,
            trailingContent = {
                Text(
                    text = valueFormatter(sliderValue.roundToInt()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha)
                    }
                )
            },
            // The slider's touch area already pads its track.
            bottomContentSpacing = 4.dp,
            bottomContent = {
                val colors = SliderDefaults.colors()
                val sliderInteractionSource = remember { MutableInteractionSource() }
                Slider(
                    value = sliderValue.coerceIn(valueRange.start, valueRange.endInclusive),
                    onValueChange = {
                        sliderValue = it
                        val currentInt = it.roundToInt()
                        if (currentInt != lastHapticValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            lastHapticValue = currentInt
                        }
                    },
                    modifier = sliderModifier.testTag("${title.lowercase().replace(' ', '_')}_slider"),
                    enabled = enabled,
                    onValueChangeFinished = {
                        onValueChangeFinished(sliderValue.coerceIn(valueRange.start, valueRange.endInclusive).roundToInt())
                    },
                    colors = colors,
                    interactionSource = sliderInteractionSource,
                    steps = steps,
                    thumb = {
                        SliderDefaults.Thumb(
                            interactionSource = sliderInteractionSource,
                            colors = colors,
                            enabled = enabled
                        )
                    },
                    track = { sliderState ->
                        // The value is spelled out next to the title, so the per-step dots are just noise.
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            enabled = enabled,
                            colors = colors,
                            drawTick = { _, _ -> }
                        )
                    },
                    valueRange = valueRange
                )
            }
        )
    }
}

/** A tile with a single-choice [ConnectedButtonGroup] under its title. */
@Composable
fun <T> SettingChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    supportingTextColor: Color = Color.Unspecified,
    optionIcon: ((T) -> ImageVector)? = null,
    optionContentDescription: (@Composable (T) -> String)? = null,
    enabled: Boolean = true,
    isOptionEnabled: (T) -> Boolean = { true },
) {
    SettingsItem(
        title = title,
        modifier = modifier,
        icon = icon,
        supportingText = supportingText,
        supportingTextColor = supportingTextColor,
        enabled = enabled,
        bottomContent = {
            ConnectedButtonGroup(
                options = options,
                selected = selected,
                optionLabel = optionLabel,
                onSelect = onSelect,
                optionIcon = optionIcon,
                optionContentDescription = optionContentDescription,
                enabled = enabled,
                isOptionEnabled = isOptionEnabled
            )
        }
    )
}

/**
 * A tile with a wrapping row of chips under its title for picking one of [options]; unlike a
 * [ConnectedButtonGroup] it fits any number of options at any width.
 */
@Composable
fun <T> SettingChipChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    SettingsItem(
        title = title,
        modifier = modifier,
        icon = icon,
        supportingText = supportingText,
        enabled = enabled,
        bottomContent = {
            FlowRow(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    SettingsChip(
                        label = optionLabel(option),
                        selected = option == selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            if (option != selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(option)
                            }
                        }
                    )
                }
            }
        }
    )
}

/** A tile with a wrapping row of chips under its title, any number of which can be selected. */
@Composable
fun <T> SettingMultiChoiceRow(
    title: String,
    options: List<T>,
    isSelected: (T) -> Boolean,
    optionLabel: @Composable (T) -> String,
    onSelectedChange: (option: T, selected: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    SettingsItem(
        title = title,
        modifier = modifier,
        icon = icon,
        supportingText = supportingText,
        enabled = enabled,
        bottomContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val selected = isSelected(option)
                    SettingsChip(
                        label = optionLabel(option),
                        selected = selected,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelectedChange(option, !selected)
                        }
                    )
                }
            }
        }
    )
}

/**
 * A Material 3 Expressive connected button group for picking one option: equal-width buttons 2dp
 * apart, rounded at the group's ends. The selected button fills with the primary color and rounds
 * fully; a pressed one squares its inner corners. [optionContentDescription] replaces a label that
 * reads badly aloud (such as a sample value).
 */
@Composable
fun <T> ConnectedButtonGroup(
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionIcon: ((T) -> ImageVector)? = null,
    optionContentDescription: (@Composable (T) -> String)? = null,
    enabled: Boolean = true,
    isOptionEnabled: (T) -> Boolean = { true },
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ConnectedButtonGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            ConnectedButton(
                label = optionLabel(option),
                contentDescription = optionContentDescription?.invoke(option),
                icon = optionIcon?.invoke(option),
                selected = option == selected,
                enabled = enabled && isOptionEnabled(option),
                isFirst = index == 0,
                isLast = index == options.lastIndex,
                onClick = {
                    if (option != selected) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ConnectedButton(
    label: String,
    contentDescription: String?,
    icon: ImageVector?,
    selected: Boolean,
    enabled: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val fullCorner = ConnectedButtonHeight / 2
    val innerCorner by animateDpAsState(
        targetValue = when {
            selected -> fullCorner
            pressed -> ConnectedButtonPressedInnerCornerRadius
            else -> ConnectedButtonInnerCornerRadius
        },
        animationSpec = fastSpatialSpring(),
        label = "connected_button_inner_corner"
    )
    val shape = RoundedCornerShape(
        topStart = if (isFirst) fullCorner else innerCorner.atLeastZero(),
        bottomStart = if (isFirst) fullCorner else innerCorner.atLeastZero(),
        topEnd = if (isLast) fullCorner else innerCorner.atLeastZero(),
        bottomEnd = if (isLast) fullCorner else innerCorner.atLeastZero()
    )
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = when {
            selected && !enabled -> colors.onSurface.copy(alpha = 0.12f)
            selected -> colors.primary
            else -> colors.surfaceContainerHighest
        },
        animationSpec = fastEffectsSpring(),
        label = "connected_button_container"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = DisabledContentAlpha)
            selected -> colors.onPrimary
            else -> colors.onSurfaceVariant
        },
        animationSpec = fastEffectsSpring(),
        label = "connected_button_content"
    )

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = ConnectedButtonHeight)
            .clip(shape)
            .background(containerColor)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (contentDescription != null) {
                Modifier.semantics { this.contentDescription = contentDescription }
            } else {
                Modifier
            }
        )
    }
}

/**
 * A chip that squares up and fills with the primary color while selected, like an Expressive toggle
 * button; fill and shape mark the selection, so it needs no check mark and stays narrow. [role] is
 * [Role.RadioButton] for one-of-many choices and [Role.Checkbox] for independent ones.
 */
@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    role: Role,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val corner by animateDpAsState(
        targetValue = if (selected) ChipSelectedCornerRadius else ChipCornerRadius,
        animationSpec = fastSpatialSpring(),
        label = "settings_chip_corner"
    )
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = when {
            !selected -> Color.Transparent
            !enabled -> colors.onSurface.copy(alpha = 0.12f)
            else -> colors.primary
        },
        animationSpec = fastEffectsSpring(),
        label = "settings_chip_container"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = DisabledContentAlpha)
            selected -> colors.onPrimary
            else -> colors.onSurfaceVariant
        },
        animationSpec = fastEffectsSpring(),
        label = "settings_chip_content"
    )
    val borderColor = when {
        selected -> Color.Transparent
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        else -> colors.outline
    }
    val shape = RoundedCornerShape(corner.atLeastZero())
    val selection = if (role == Role.RadioButton) {
        Modifier.selectable(
            selected = selected,
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            role = role,
            onClick = onClick
        )
    } else {
        Modifier.toggleable(
            value = selected,
            interactionSource = interactionSource,
            indication = ripple(),
            enabled = enabled,
            role = role,
            onValueChange = { onClick() }
        )
    }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = ChipHeight)
            .clip(shape)
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .then(selection)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = contentColor,
            maxLines = 1
        )
    }
}

/**
 * A tile's container: its surface, small corners that round up while [interactionSource] is pressed,
 * and the click/toggle behaviour in [interactionModifier], applied inside the clip so the ripple is
 * clipped too.
 */
@Composable
private fun SettingsItemFrame(
    modifier: Modifier,
    interactionSource: MutableInteractionSource,
    interactionModifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val cornerRadius by animateDpAsState(
        targetValue = if (pressed) ItemPressedCornerRadius else ItemCornerRadius,
        animationSpec = fastSpatialSpring(),
        label = "settings_item_corner"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius.atLeastZero()))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(interactionModifier),
        content = content
    )
}

@Composable
private fun SettingsItemContent(
    title: String,
    enabled: Boolean,
    icon: ImageVector?,
    supportingText: String?,
    trailingContent: (@Composable () -> Unit)?,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    titleMaxLines: Int = Int.MAX_VALUE,
    titleOverflow: TextOverflow = TextOverflow.Clip,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingTextColor: Color = Color.Unspecified,
    supportingContent: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    bottomContentSpacing: Dp = 12.dp,
) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ItemIconContainerSize),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingContent != null || icon != null) {
                Box(modifier = Modifier.alpha(if (enabled) 1f else DisabledContentAlpha)) {
                    if (leadingContent != null) leadingContent() else if (icon != null) SettingsIcon(icon)
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledColor,
                    maxLines = titleMaxLines,
                    overflow = titleOverflow
                )
                when {
                    supportingContent != null -> supportingContent()
                    supportingText != null -> Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            supportingTextColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }
                        } else {
                            disabledColor
                        }
                    )
                }
            }
            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailingContent()
            }
        }
        if (bottomContent != null) {
            Spacer(modifier = Modifier.height(bottomContentSpacing))
            bottomContent()
        }
    }
}

/** A bouncy spring can briefly undershoot a corner radius below zero. */
private fun Dp.atLeastZero(): Dp = coerceAtLeast(0.dp)
