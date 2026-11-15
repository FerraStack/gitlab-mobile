package com.cboyxd.gitlab.ui.screens

import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cboyxd.gitlab.data.storage.AppLockTimeout
import com.cboyxd.gitlab.data.storage.AppPreferencesStore
import com.cboyxd.gitlab.data.storage.SecurityPreferences
import com.cboyxd.gitlab.ui.components.GitLabTanukiIcon
import com.cboyxd.gitlab.ui.components.GlButton
import com.cboyxd.gitlab.ui.components.GlDivider
import com.cboyxd.gitlab.ui.components.GlFilterRow
import com.cboyxd.gitlab.ui.components.GlTopBar
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GlSpace

private const val AUTHENTICATORS =
  BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

@Composable
fun SecuritySettingsRoute(onBack: () -> Unit) {
  val context = LocalContext.current
  val store = remember { AppPreferencesStore.get(context) }
  val preferences by store.preferences.collectAsStateWithLifecycle()
  val biometricsAvailable = remember(context) {
    runCatching {
      BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)
  }

  SecuritySettingsScreen(
    preferences = preferences,
    biometricsAvailable = biometricsAvailable,
    onBack = onBack,
    onSetAppLock = { enabled ->
      if (!enabled || biometricsAvailable) {
        store.setAppLockEnabled(enabled)
      }
    },
    onSetLockTimeout = store::setLockTimeout,
    onSetScreenSecurity = store::setScreenSecurityEnabled,
  )
}

@Composable
fun SecuritySettingsScreen(
  preferences: SecurityPreferences,
  biometricsAvailable: Boolean,
  onBack: () -> Unit,
  onSetAppLock: (Boolean) -> Unit,
  onSetLockTimeout: (AppLockTimeout) -> Unit,
  onSetScreenSecurity: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize().background(Gl.colors.bg)) {
    GlTopBar(title = "Security & app lock", onBack = onBack)
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = GlSpace.group),
    ) {
      item(key = "app_lock") {
        val canToggleAppLock = biometricsAvailable || preferences.appLockEnabled
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .toggleable(
              value = preferences.appLockEnabled,
              enabled = canToggleAppLock,
              role = Role.Switch,
              onValueChange = onSetAppLock,
            )
            .padding(horizontal = GlSpace.margin, vertical = 12.dp)
            .testTag("app_lock_toggle"),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Biometric & device lock", style = Gl.type.rowTitle, color = Gl.colors.textPrimary)
            Text(
              text = if (biometricsAvailable) {
                "Require fingerprint, face unlock or your device PIN before opening the app."
              } else {
                "Set up biometrics or a screen lock in Android settings to enable app lock."
              },
              style = Gl.type.meta,
              color = Gl.colors.textSecondary,
            )
          }
          Spacer(Modifier.width(GlSpace.margin))
          Switch(checked = preferences.appLockEnabled, onCheckedChange = null, enabled = canToggleAppLock)
        }
        if (preferences.appLockEnabled) {
          Text(
            "Lock after leaving the app",
            style = Gl.type.label,
            color = Gl.colors.textSecondary,
            modifier = Modifier.padding(start = GlSpace.margin, top = 4.dp),
          )
          GlFilterRow(
            options = AppLockTimeout.entries,
            selected = preferences.lockTimeout,
            label = { it.label },
            onSelect = onSetLockTimeout,
            modifier = Modifier.padding(bottom = GlSpace.tight).testTag("app_lock_timeout_row"),
          )
        }
        GlDivider()
      }

      item(key = "screen_security") {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .toggleable(
              value = preferences.screenSecurityEnabled,
              role = Role.Switch,
              onValueChange = onSetScreenSecurity,
            )
            .padding(horizontal = GlSpace.margin, vertical = 12.dp)
            .testTag("screen_security_toggle"),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Hide screen in Recents & block screenshots", style = Gl.type.rowTitle, color = Gl.colors.textPrimary)
            Text(
              "Turns on Android's secure window flag so previews aren't saved in the app switcher and screenshots are blocked.",
              style = Gl.type.meta,
              color = Gl.colors.textSecondary,
            )
          }
          Spacer(Modifier.width(GlSpace.margin))
          Switch(checked = preferences.screenSecurityEnabled, onCheckedChange = null)
        }
        GlDivider()
      }

      item(key = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
    }
  }
}

@Composable
fun AppLockScreen(
  errorMessage: String?,
  onUnlock: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Gl.colors.bg)
      .padding(horizontal = 32.dp)
      .testTag("app_lock_screen"),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    GitLabTanukiIcon(size = 48.dp)
    Spacer(Modifier.height(20.dp))
    Text("GitLab Mobile is locked", style = Gl.type.title, color = Gl.colors.textPrimary, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(
      text = errorMessage?.takeIf { it.isNotBlank() } ?: "Verify your identity to continue.",
      style = Gl.type.bodySmall,
      color = if (!errorMessage.isNullOrBlank()) Gl.colors.danger else Gl.colors.textSecondary,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    GlButton(
      text = "Unlock",
      onClick = onUnlock,
      modifier = Modifier.testTag("app_lock_unlock_button"),
    )
  }
}
