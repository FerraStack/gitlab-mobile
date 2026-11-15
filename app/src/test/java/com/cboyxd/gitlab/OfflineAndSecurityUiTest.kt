package com.cboyxd.gitlab

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.storage.AppLockTimeout
import com.cboyxd.gitlab.data.storage.SecurityPreferences
import com.cboyxd.gitlab.ui.OfflineBanner
import com.cboyxd.gitlab.ui.screens.AppLockScreen
import com.cboyxd.gitlab.ui.screens.InstancesScreen
import com.cboyxd.gitlab.ui.screens.SecuritySettingsScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.LoginState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class OfflineAndSecurityUiTest {

  @get:Rule val rule = createComposeRule()

  @Test
  fun `offline banner indicates offline state and cached data`() {
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        OfflineBanner(savedAtMs = 1_700_000_000_000L)
      }
    }
    rule.onNodeWithTag("offline_banner").assertIsDisplayed()
    rule.onNodeWithText("You're offline · Showing cached data").assertIsDisplayed()
  }

  @Test
  fun `security settings shows timeout options when app lock enabled and updates callbacks`() {
    val toggledLocks = mutableListOf<Boolean>()
    val selectedTimeouts = mutableListOf<AppLockTimeout>()
    val toggledScreenSec = mutableListOf<Boolean>()

    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        SecuritySettingsScreen(
          preferences = SecurityPreferences(
            appLockEnabled = true,
            lockTimeout = AppLockTimeout.ONE_MINUTE,
            screenSecurityEnabled = false,
          ),
          biometricsAvailable = true,
          onBack = {},
          onSetAppLock = { toggledLocks += it },
          onSetLockTimeout = { selectedTimeouts += it },
          onSetScreenSecurity = { toggledScreenSec += it },
        )
      }
    }

    rule.onNodeWithText("Biometric & device lock").assertIsDisplayed()
    rule.onNodeWithTag("app_lock_timeout_row").assertIsDisplayed()
    rule.onNodeWithText("After 5 minutes").performClick()
    rule.onNodeWithTag("screen_security_toggle").performClick()

    assertEquals(listOf(AppLockTimeout.FIVE_MINUTES), selectedTimeouts)
    assertEquals(listOf(true), toggledScreenSec)
  }

  @Test
  fun `security settings disables app lock toggle when biometrics unavailable`() {
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        SecuritySettingsScreen(
          preferences = SecurityPreferences(appLockEnabled = false),
          biometricsAvailable = false,
          onBack = {},
          onSetAppLock = {},
          onSetLockTimeout = {},
          onSetScreenSecurity = {},
        )
      }
    }

    rule.onNodeWithTag("app_lock_toggle").assertIsNotEnabled()
    rule.onNodeWithText("Set up biometrics or a screen lock in Android settings to enable app lock.").assertIsDisplayed()
  }

  @Test
  fun `app lock screen shows error and triggers unlock`() {
    var clicks = 0
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        AppLockScreen(errorMessage = "Authentication failed", onUnlock = { clicks++ })
      }
    }

    rule.onNodeWithTag("app_lock_screen").assertIsDisplayed()
    rule.onNodeWithText("GitLab Mobile is locked").assertIsDisplayed()
    rule.onNodeWithText("Authentication failed").assertIsDisplayed()
    rule.onNodeWithTag("app_lock_unlock_button").performClick()
    assertEquals(1, clicks)
  }

  @Test
  fun `instances screen opens security settings from guest and signed-in views`() {
    var securityClicks = 0
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        InstancesScreen(
          activeInstance = Fakes.signedIn,
          savedInstances = listOf(Fakes.signedIn),
          loginState = LoginState(),
          savedClientId = "",
          openAddOnStart = false,
          onAddOpened = {},
          onSwitchInstance = {},
          onDeleteInstance = {},
          onSignInWithPassword = { _, _, _, _ -> },
          onSubmitTwoFactor = {},
          onSignInWithOAuth = { _, _, _ -> },
          onSignInWithToken = { _, _, _ -> },
          onClearLogin = {},
          onBack = {},
          onOpenNotifications = {},
          onOpenSecurity = { securityClicks++ },
        )
      }
    }

    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Security & app lock"))
    rule.onNodeWithText("Security & app lock").performClick()
    assertEquals(1, securityClicks)
  }

  @Test
  fun `instances guest view also exposes security settings`() {
    var securityClicks = 0
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        InstancesScreen(
          activeInstance = GitLabAuth.GUEST,
          savedInstances = emptyList(),
          loginState = LoginState(),
          savedClientId = "",
          openAddOnStart = false,
          onAddOpened = {},
          onSwitchInstance = {},
          onDeleteInstance = {},
          onSignInWithPassword = { _, _, _, _ -> },
          onSubmitTwoFactor = {},
          onSignInWithOAuth = { _, _, _ -> },
          onSignInWithToken = { _, _, _ -> },
          onClearLogin = {},
          onBack = {},
          onOpenNotifications = {},
          onOpenSecurity = { securityClicks++ },
        )
      }
    }

    rule.onNodeWithText("Security & app lock").performClick()
    assertEquals(1, securityClicks)
  }
}
