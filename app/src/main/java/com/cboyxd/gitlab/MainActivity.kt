package com.cboyxd.gitlab

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cboyxd.gitlab.notifications.GitLabNotificationMonitor
import com.cboyxd.gitlab.notifications.GitLabNotifications
import com.cboyxd.gitlab.notifications.InstantNotificationService
import com.cboyxd.gitlab.ui.MainScreen
import com.cboyxd.gitlab.ui.screens.AppLockScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.GitLabViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

  private val viewModel: GitLabViewModel by viewModels()
  private var lockError by mutableStateOf<String?>(null)
  private var biometricPromptShowing = false

  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
    GitLabNotifications.schedule(this, viewModel.uiState.value.savedInstances.any { it.token.isNotBlank() })
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applyScreenSecurity(viewModel.securityPreferences.value.screenSecurityEnabled)
    enableEdgeToEdge()
    setContent {
      GitLabTheme {
        val appLocked by viewModel.appLocked.collectAsStateWithLifecycle()
        LaunchedEffect(appLocked) {
          if (appLocked) promptAppUnlock()
        }
        Box(modifier = Modifier.fillMaxSize()) {
          if (appLocked) {
            AppLockScreen(
              errorMessage = lockError,
              onUnlock = ::promptAppUnlock,
            )
          } else {
            MainScreen(viewModel = viewModel, onExit = { finish() })
          }
        }
      }
    }
    // A recreated activity must not replay the redirect it already handled.
    if (savedInstanceState == null) handleIncomingIntent(intent)
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.CREATED) {
        viewModel.securityPreferences
          .map { it.screenSecurityEnabled }
          .distinctUntilChanged()
          .collect { enabled -> applyScreenSecurity(enabled) }
      }
    }
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          viewModel.setForeground(true)
          try {
            awaitCancellation()
          } finally {
            viewModel.setForeground(false)
          }
        }
        launch {
          viewModel.uiState.first { it.bootstrapped }
          viewModel.uiState.map { it.savedInstances.filter { account -> account.token.isNotBlank() }.map { account -> account.id }.sorted() }
            .distinctUntilChanged().collect { accounts ->
              GitLabNotifications.schedule(this@MainActivity, accounts.isNotEmpty())
              // Starts (or restarts, after Android stopped it) the opt-in instant service.
              InstantNotificationService.sync(this@MainActivity)
              requestNotificationPermission(accounts.isNotEmpty())
            }
        }
        launch {
          viewModel.uiState.first { it.bootstrapped }
          while (true) {
            try {
              // The instant service already checks every minute or two, more cheaply.
              if (!InstantNotificationService.isRunning) GitLabNotificationMonitor.poll(this@MainActivity)
              viewModel.refreshNotificationInbox()
            } catch (cancel: CancellationException) {
              throw cancel
            } catch (_: Exception) {
              // Network outages are retried by the next poll and by the background worker.
            }
            delay(60_000)
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    viewModel.onAppForegrounded()
  }

  override fun onStop() {
    super.onStop()
    if (!isChangingConfigurations) {
      viewModel.onAppBackgrounded()
    }
  }

  private fun applyScreenSecurity(enabled: Boolean) {
    if (enabled) {
      window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }

  private fun promptAppUnlock() {
    if (biometricPromptShowing || !viewModel.appLocked.value) return
    val canAuth = BiometricManager.from(this).canAuthenticate(AUTHENTICATORS)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
      lockError = null
      viewModel.unlockApp()
      return
    }
    lockError = null
    biometricPromptShowing = true
    val executor = ContextCompat.getMainExecutor(this)
    val prompt = BiometricPrompt(
      this,
      executor,
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          biometricPromptShowing = false
          lockError = null
          viewModel.unlockApp()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          biometricPromptShowing = false
          lockError = if (
            errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
            errorCode == BiometricPrompt.ERROR_CANCELED ||
            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
          ) {
            null
          } else {
            errString.toString()
          }
        }
      },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle("Unlock GitLab Mobile")
      .setSubtitle("Verify with biometrics or your device lock")
      .setAllowedAuthenticators(AUTHENTICATORS)
      .build()
    try {
      prompt.authenticate(info)
    } catch (error: Exception) {
      biometricPromptShowing = false
      lockError = error.message ?: "Unable to verify identity."
    }
  }

  private fun requestNotificationPermission(hasAccounts: Boolean) {
    if (!hasAccounts || Build.VERSION.SDK_INT < 33 || GitLabNotifications.allowed(this)) return
    val prefs = getSharedPreferences("notification_permission", MODE_PRIVATE)
    if (prefs.getBoolean("requested", false)) return
    prefs.edit().putBoolean("requested", true).apply()
    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingIntent(intent)
  }

  private fun handleIncomingIntent(intent: Intent?) {
    if (intent?.action == GitLabNotifications.ACTION_OPEN) {
      GitLabNotifications.parseTrustedOpenIntent(this, intent)?.let { open ->
        viewModel.openSystemNotification(
          accountId = open.accountId,
          targetType = open.targetType,
          projectId = open.projectId,
          iid = open.iid,
          url = open.url,
        )
      }
      setIntent(Intent(this, MainActivity::class.java))
      return
    }
    if (intent == null) return
    val data = intent.data
    if (data != null && IncomingLinks.isOAuthRedirect(intent.dataString)) {
      viewModel.completeOAuthRedirect(data)
      setIntent(Intent(intent).apply { this.data = null })
      return
    }
    val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    val url = IncomingLinks.webUrl(intent.action, intent.dataString, shared) ?: return
    viewModel.openIncomingLink(url)
    // Drop the link from the activity so a recreated instance does not open it twice.
    setIntent(Intent(this, MainActivity::class.java))
  }

  companion object {
    private const val AUTHENTICATORS =
      BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
  }
}
