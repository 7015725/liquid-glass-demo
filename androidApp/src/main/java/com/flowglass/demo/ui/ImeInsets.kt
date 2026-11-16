package com.flowglass.demo.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Keeps the focused text field above the software keyboard.
 *
 * `imePadding()` alone only shrinks the viewport; it does not scroll a focused field that is
 * already below the new viewport. Attach this to every editable component inside a scroll
 * container. The requester coordinates with verticalScroll/LazyColumn through Compose's
 * bring-into-view protocol, including nested dialogs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.imeAutoScroll(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(focused, imeVisible) {
        if (focused && imeVisible) {
            // Wait for the IME inset animation to settle before asking the scroll container
            // to reveal the field; doing it in the same frame can calculate the old viewport.
            withFrameNanos { }
            delay(60)
            runCatching { requester.bringIntoView() }
        }
    }
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { focused = it.isFocused }
}
