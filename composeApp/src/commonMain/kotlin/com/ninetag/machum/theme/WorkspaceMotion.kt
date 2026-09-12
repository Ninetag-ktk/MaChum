package com.ninetag.machum.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme

/** Semantic durations; Compose's animation clock continues to honor duration scale. */
internal object WorkspaceMotion {
    const val DrawerOpenMillis = 240
    const val DrawerCloseMillis = 180
    const val MenuEnterMillis = 120
    const val MenuExitMillis = 80
    const val ContentEnterMillis = 120
    const val ContentExpandMillis = 180
    const val ContentCollapseMillis = 120

    fun <T> drawerOpenSpec(): TweenSpec<T> = tween(DrawerOpenMillis, easing = FastOutSlowInEasing)
    fun <T> drawerCloseSpec(): TweenSpec<T> = tween(DrawerCloseMillis, easing = FastOutSlowInEasing)
    fun <T> menuEnterSpec(): TweenSpec<T> = tween(MenuEnterMillis, easing = LinearEasing)
    fun <T> menuExitSpec(): TweenSpec<T> = tween(MenuExitMillis, easing = LinearEasing)
    fun <T> contentEnterSpec(): TweenSpec<T> = tween(ContentEnterMillis, easing = LinearEasing)
    fun <T> contentExpandSpec(): TweenSpec<T> = tween(ContentExpandMillis, easing = FastOutSlowInEasing)
    fun <T> contentCollapseSpec(): TweenSpec<T> = tween(ContentCollapseMillis, easing = FastOutSlowInEasing)

    // DrawerState.open/close consume DefaultSpatial/FastEffects; gesture settling is separate.
    // Restore the original scheme inside both of its content slots so these timings stay local.
    fun drawerScheme(base: MotionScheme): MotionScheme = object : MotionScheme by base {
        override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = drawerOpenSpec()
        override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = drawerCloseSpec()
    }
}
