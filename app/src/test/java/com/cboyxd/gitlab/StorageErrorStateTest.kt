package com.cboyxd.gitlab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.cboyxd.gitlab.ui.theme.Gl
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cboyxd.gitlab.ui.screens.StorageErrorState
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class StorageErrorStateTest {

  @get:Rule val rule = createComposeRule()

  private fun show(dark: Boolean, onReset: () -> Unit = {}) = rule.setContent {
    GitLabTheme(darkTheme = dark, reducedMotion = true) {
      // As in the app: full screen, centered, on the page background.
      Box(Modifier.fillMaxSize().background(Gl.colors.bg), contentAlignment = Alignment.Center) {
        StorageErrorState(message = "Unable to read saved credentials.", onRetry = {}, onReset = onReset)
      }
    }
  }

  @Test fun storage_error_light() { show(dark = false); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/storage_error_light.png") }
  @Test fun storage_error_dark() { show(dark = true); rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/storage_error_dark.png") }

  @Test
  fun `removing saved accounts asks first`() {
    var resets = 0
    show(dark = true, onReset = { resets++ })
    rule.onNodeWithTag("reset_accounts").performClick()
    rule.onNodeWithText("Remove saved accounts?").assertExists()
    assertEquals(0, resets)
    rule.onNodeWithTag("reset_accounts_confirm").performClick()
    assertEquals(1, resets)
  }
}
