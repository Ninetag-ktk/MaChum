package com.ninetag.machum.screen.common

/** Prevents an IME action and a button click from submitting the same form twice. */
internal class SingleLineSubmitGate {
    private var submitted = false

    fun submitIf(enabled: Boolean, action: () -> Unit): Boolean {
        if (!enabled || submitted) return false

        submitted = true
        return try {
            action()
            true
        } catch (error: Throwable) {
            submitted = false
            throw error
        }
    }

    fun reset() {
        submitted = false
    }
}
