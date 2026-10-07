package dev.motionblur.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.motionblur.app.MainActivity
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReleaseUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        ui.waitForIdle()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "validation/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun settingsAndBundledLicensesOpen() {
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Encoding").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Use automatic bitrate").assertIsDisplayed()
        capture("encoding")
        ui.onNodeWithText("Privacy").performScrollTo().assertIsDisplayed()
        capture("settings")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Licenses"))
        ui.onNodeWithText("Licenses").performClick()
        ui.onNodeWithText("Third-party notices  ▾").assertIsDisplayed()
        ui.onNodeWithText("Third-party notices  ▾").performClick()
        ui.onNodeWithText("OpenBlur / MVTools").performClick()
        ui.onNode(hasText("GNU GENERAL PUBLIC LICENSE", substring = true)).assertIsDisplayed()
        capture("licenses")
        ui.onNodeWithText("Close").performClick()
    }
}
