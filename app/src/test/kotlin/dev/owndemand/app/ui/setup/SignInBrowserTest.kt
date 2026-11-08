package dev.owndemand.app.ui.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInBrowserTest {

    @Test
    fun `no handler means no browser`() {
        assertFalse(canOpenAuthPage(emptyList()))
    }

    @Test
    fun `the Android TV link stub is not a browser`() {
        assertFalse(canOpenAuthPage(listOf("com.google.android.tv.frameworkpackagestubs")))
    }

    @Test
    fun `a real browser next to the stub counts`() {
        assertTrue(
            canOpenAuthPage(
                listOf("com.google.android.tv.frameworkpackagestubs", "org.example.browser"),
            ),
        )
    }
}
