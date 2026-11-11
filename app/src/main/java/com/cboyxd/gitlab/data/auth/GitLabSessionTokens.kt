package com.cboyxd.gitlab.data.auth

import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * UI and notification workers share one refresh lock: GitLab refresh tokens are single use.
 *
 * When GitLab rejects a refresh token it stays rejected, so it is dropped from the account. Later
 * calls then skip refreshing (and its failing token request) until the user signs in again.
 */
object GitLabSessionTokens {
  private val lock = Mutex()

  suspend fun fresh(store: InstanceStore, instanceId: String, force: Boolean = false, staleToken: String? = null): GitLabInstance? = lock.withLock {
    withContext(Dispatchers.IO + NonCancellable) {
      val latest = store.getInstances().firstOrNull { it.id == instanceId } ?: return@withContext null
      if (latest.authKind != GitLabAuth.KIND_OAUTH || latest.refreshToken.isBlank()) return@withContext latest
      if (staleToken != null && latest.token != staleToken) return@withContext latest
      if (!force && latest.tokenExpiresAt > System.currentTimeMillis() / 1000 + 60) return@withContext latest
      val clientId = latest.oauthClientId.ifBlank { store.getGitLabComClientId() }
      if (clientId.isBlank()) return@withContext latest
      val tokens = try {
        GitLabOAuth.refresh(latest.url, clientId, latest.refreshToken)
      } catch (rejected: GitLabAuthException) {
        if (rejected.rejected) forgetRefreshToken(store, instanceId, latest.refreshToken)
        throw rejected
      }
      val updated = latest.copy(
        token = tokens.accessToken,
        refreshToken = tokens.refreshToken.ifBlank { latest.refreshToken },
        tokenExpiresAt = tokens.expiresAtEpochSec,
        oauthClientId = clientId,
      )
      if (store.updateInstance(updated)) store.getInstances().firstOrNull { it.id == instanceId } else null
    }
  }

  /** Drops [used] unless the account already holds a newer refresh token (a sign-in meanwhile). */
  private fun forgetRefreshToken(store: InstanceStore, instanceId: String, used: String) {
    val now = store.getInstances().firstOrNull { it.id == instanceId } ?: return
    if (now.refreshToken == used) store.updateInstance(now.copy(refreshToken = ""))
  }
}
