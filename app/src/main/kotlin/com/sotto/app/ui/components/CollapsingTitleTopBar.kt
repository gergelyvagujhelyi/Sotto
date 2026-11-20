package com.sotto.app.ui.components

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

private val CollapsedHeight = 64.dp
private val ExpandedHeight = 152.dp
private val TitleStartPadding = 16.dp
private val TitleBottomPadding = 28.dp
private val IconRowPadding = 4.dp

private const val NavId = "nav"
private const val ActionsId = "actions"
private const val TitleId = "title"

/**
 * A top bar with one title that moves and shrinks from a large heading under the icons into
 * the bar as the content scrolls up, and grows back as it scrolls down. (Material's
 * LargeTopAppBar instead scrolls the large title away and fades in a second, small one.)
 * Same heights as LargeTopAppBar; use it with [TopAppBarDefaults.exitUntilCollapsedScrollBehavior].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingTitleTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = Color.Transparent,
    scrolledContainerColor: Color = MaterialTheme.colorScheme.background,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val state = scrollBehavior.state
    val density = LocalDensity.current
    val collapsedHeightPx = with(density) { CollapsedHeight.toPx() }
    val expandedHeightPx = with(density) { ExpandedHeight.toPx() }
    SideEffect {
        // How far the scroll behaviour may collapse the bar.
        val limit = collapsedHeightPx - expandedHeightPx
        if (state.heightOffsetLimit != limit) state.heightOffsetLimit = limit
    }
    // One text, laid out in the large style and scaled down to the small one's size.
    val largeStyle = MaterialTheme.typography.headlineMedium.copy(textMotion = TextMotion.Animated)
    val collapsedScale = MaterialTheme.typography.titleLarge.fontSize.value / largeStyle.fontSize.value
    val pivotX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else 0f
    val contentColor = MaterialTheme.colorScheme.onSurface

    // The scroll position is only read while laying out and drawing, so scrolling doesn't
    // recompose the screen on every frame.
    Box(
        modifier
            .drawBehind {
                val fraction = FastOutLinearInEasing.transform(state.collapsedFraction)
                drawRect(lerp(containerColor, scrolledContainerColor, fraction))
            }
            .windowInsetsPadding(windowInsets),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Layout(
                content = {
                    Box(Modifier.layoutId(NavId)) { navigationIcon() }
                    Row(
                        modifier = Modifier.layoutId(ActionsId),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                    Text(
                        text = title,
                        style = largeStyle,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .layoutId(TitleId)
                            .semantics { heading() }
                            .graphicsLayer {
                                val scale = lerp(1f, collapsedScale, state.collapsedFraction)
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(pivotX, 0.5f)
                            },
                    )
                },
            ) { measurables, constraints ->
                val rowHeight = collapsedHeightPx.roundToInt()
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val nav = measurables.first { it.layoutId == NavId }.measure(loose.copy(maxHeight = rowHeight))
                val actions = measurables.first { it.layoutId == ActionsId }.measure(loose.copy(maxHeight = rowHeight))
                val startPadding = TitleStartPadding.roundToPx()
                val titlePlaceable = measurables.first { it.layoutId == TitleId }
                    .measure(loose.copy(maxWidth = (constraints.maxWidth - 2 * startPadding).coerceAtLeast(0)))

                val fraction = state.collapsedFraction
                val height = (expandedHeightPx + state.heightOffset).roundToInt()
                    .coerceIn(rowHeight, expandedHeightPx.roundToInt())
                layout(constraints.maxWidth, height) {
                    val rowPadding = IconRowPadding.roundToPx()
                    nav.placeRelative(rowPadding, (rowHeight - nav.height) / 2)
                    actions.placeRelative(constraints.maxWidth - actions.width - rowPadding, (rowHeight - actions.height) / 2)

                    // Expanded: under the icons, at the bottom of the bar. Collapsed: in the
                    // icon row, after the navigation icon if there is one.
                    val collapsedX = if (nav.width > 0) rowPadding + nav.width + rowPadding else startPadding
                    val expandedY = expandedHeightPx - TitleBottomPadding.toPx() - titlePlaceable.height
                    val collapsedY = (rowHeight - titlePlaceable.height) / 2f
                    titlePlaceable.placeRelative(
                        x = lerp(startPadding.toFloat(), collapsedX.toFloat(), fraction).roundToInt(),
                        y = lerp(expandedY, collapsedY, fraction).roundToInt(),
                    )
                }
            }
        }
    }
}
