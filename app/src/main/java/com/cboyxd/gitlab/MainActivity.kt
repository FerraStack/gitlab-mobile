package com.cboyxd.gitlab

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cboyxd.gitlab.notifications.GitLabNotifications
import com.cboyxd.gitlab.notifications.GitLabNotificationMonitor
import com.cboyxd.gitlab.notifications.InstantNotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.cboyxd.gitlab.ui.MainScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.viewmodel.GitLabViewModel

class MainActivity : ComponentActivity() {

  private val viewModel: GitLabViewModel by viewModels()
  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
    GitLabNotifications.schedule(this, viewModel.uiState.value.savedInstances.any { it.token.isNotBlank() })
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      GitLabTheme {
        MainScreen(viewModel = viewModel, onExit = { finish() })
      }
    }
    // A recreated activity must not replay the redirect it already handled.
    if (savedInstanceState == null) handleIncomingIntent(intent)
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
      viewModel.openSystemNotification(
        accountId = intent.getStringExtra(GitLabNotifications.EXTRA_ACCOUNT).orEmpty(),
        targetType = intent.getStringExtra(GitLabNotifications.EXTRA_TARGET).orEmpty(),
        projectId = intent.getLongExtra(GitLabNotifications.EXTRA_PROJECT, 0),
        iid = intent.getLongExtra(GitLabNotifications.EXTRA_IID, 0),
        url = intent.getStringExtra(GitLabNotifications.EXTRA_URL).orEmpty(),
      )
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
}
