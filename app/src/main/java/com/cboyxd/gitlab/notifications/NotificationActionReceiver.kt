package com.cboyxd.gitlab.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.auth.GitLabSessionTokens
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.data.storage.InstanceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles notification action buttons such as "Mark as done" without opening the app UI. */
class NotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != GitLabNotifications.ACTION_MARK_DONE) return
    val accountId = intent.getStringExtra(GitLabNotifications.EXTRA_ACCOUNT).orEmpty()
    val accountName = intent.getStringExtra(GitLabNotifications.EXTRA_ACCOUNT_NAME).orEmpty()
    val todoId = intent.getLongExtra(GitLabNotifications.EXTRA_TODO_ID, 0L)
    val tag = intent.getStringExtra(GitLabNotifications.EXTRA_NOTIFICATION_TAG).orEmpty()
    if (accountId.isBlank() || todoId <= 0L) return
    val pending = goAsync()
    val appContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        handleMarkTodoDone(appContext, accountId, todoId, tag, accountName)
      } finally {
        pending.finish()
      }
    }
  }

  internal companion object {
    internal suspend fun handleMarkTodoDone(
      context: Context,
      accountId: String,
      todoId: Long,
      notificationTag: String,
      accountName: String = "",
      repository: GitLabRepository = GitLabRepository(),
    ): Boolean {
      if (accountId.isBlank() || todoId <= 0L) return false
      val store = InstanceStore.get(context)
      val current = runCatching { GitLabSessionTokens.fresh(store, accountId) }.getOrNull() ?: return false
      if (current.token.isBlank()) return false
      var result = repository.markTodoDone(current, todoId)
      if ((result.exceptionOrNull() as? GitLabApiException)?.code == 401 && current.authKind == GitLabAuth.KIND_OAUTH) {
        val renewed = runCatching {
          GitLabSessionTokens.fresh(store, accountId, force = true, staleToken = current.token)
        }.getOrNull()
        if (renewed != null && renewed.token != current.token) {
          result = repository.markTodoDone(renewed, todoId)
        }
      }
      val alreadyGone = (result.exceptionOrNull() as? GitLabApiException)?.code == 404
      if (result.isSuccess || alreadyGone) {
        if (notificationTag.isNotBlank()) {
          NotificationManagerCompat.from(context).cancel(notificationTag, 0)
          GitLabNotifications.updateSummary(context, accountId, accountName.ifBlank { current.name })
        }
        return true
      }
      return false
    }
  }
}
