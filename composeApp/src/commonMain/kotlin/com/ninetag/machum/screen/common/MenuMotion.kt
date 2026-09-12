package com.ninetag.machum.screen.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ninetag.machum.theme.WorkspaceMotion

internal class MenuMotion(val present: Boolean, val alpha: State<Float>)

private class ImmediateMenuScheme(base: MotionScheme) : MotionScheme by base {
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

/** Standard Material surface and sizing, with the same motion as custom drawer menus. */
@Composable
internal fun MotionDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = rememberMenuMotion(expanded)
    if (!motion.present) return
    val inheritedScheme = MaterialTheme.motionScheme
    val immediateScheme = remember(inheritedScheme) { ImmediateMenuScheme(inheritedScheme) }
    MaterialTheme(motionScheme = immediateScheme) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { if (expanded) onDismissRequest() },
            modifier = modifier.then(menuMotionModifier(expanded, motion)),
        ) {
            MaterialTheme(motionScheme = inheritedScheme) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MotionDropdownMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable () -> Unit,
) {
    val motion = rememberMenuMotion(expanded)
    if (!motion.present) return
    val inheritedScheme = MaterialTheme.motionScheme
    val immediateScheme = remember(inheritedScheme) { ImmediateMenuScheme(inheritedScheme) }
    MaterialTheme(motionScheme = immediateScheme) {
        // Keep Material's anchor positioning and focus handling. Never start its scale-out.
        DropdownMenuPopup(expanded = true, onDismissRequest = {
            if (expanded) onDismissRequest()
        }, offset = offset) {
            MaterialTheme(motionScheme = inheritedScheme) {
                MenuMotionContent(expanded, motion, content)
            }
        }
    }
}

@Composable
internal fun rememberMenuMotion(expanded: Boolean): MenuMotion {
    val alpha = remember { Animatable(0f) }
    val fadingVisible by remember { derivedStateOf { alpha.value > 0f } }
    LaunchedEffect(expanded) {
        alpha.animateTo(
            if (expanded) 1f else 0f,
            if (expanded) WorkspaceMotion.menuEnterSpec() else WorkspaceMotion.menuExitSpec(),
        )
    }
    return MenuMotion(expanded || fadingVisible, alpha.asState())
}

/** Keep the modal window until fade-out ends, but stop commands at logical dismissal. */
@Composable
internal fun MenuMotionContent(expanded: Boolean, motion: MenuMotion, content: @Composable () -> Unit) {
    Box(menuMotionModifier(expanded, motion)) { content() }
}

@Suppress("ModifierFactoryExtensionFunction")
private fun menuMotionModifier(expanded: Boolean, motion: MenuMotion): Modifier =
        Modifier.graphicsLayer { alpha = motion.alpha.value }
            .onPreviewKeyEvent { !expanded }
            .pointerInput(expanded) {
                if (!expanded) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
            .then(if (expanded) Modifier else Modifier.clearAndSetSemantics { })
