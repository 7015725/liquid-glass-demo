package com.flowglass.demo.ui

import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.vibrancy

/**
 * Backdrop parameters need their own dark values: a saturated white-glass recipe looks washed
 * out over a dark background, while an over-strong lens distorts the small dark text on panels.
 */
fun BackdropEffectScope.themedVibrancy() {
    if (C.dark) {
        colorControls(brightness = -0.02f, contrast = 1.06f, saturation = 1.18f)
    } else {
        vibrancy()
    }
}

fun themedBlur(basePx: Float): Float = if (C.dark) basePx * 1.18f else basePx

fun themedLensX(basePx: Float): Float = if (C.dark) basePx * 0.88f else basePx

fun themedLensY(basePx: Float): Float = if (C.dark) basePx * 0.92f else basePx
