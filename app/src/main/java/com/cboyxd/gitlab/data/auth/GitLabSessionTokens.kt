package com.cboyxd.gitlab.data.auth

import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-account refresh locks: GitLab refresh tokens are single use, so concurrent callers for the
 * same account must serialize, while requests for other accounts or non-expiring tokens never wait.
 *
 * When GitLab rejects a refresh token it stays rejected, so it is dropped from the account. Later
 * calls then skip refreshing (and its failing token request) until the user signs in again.
 */
object GitLabSessionTokens {
  private val locks = ConcurrentHashMap<String, Mutex>()

  private fun lockFor(instanceId: String): Mutex = locks.getOrPut(instanceId) { Mutex() }

  internal fun needsRefresh(
    instance: GitLabInstance,
    force: Boolean = false,
    staleToken: String? = null,
    nowEpochSec: Long = System.currentTimeMillis() / 1000,
  ): Boolean {
    if (instance.authKind != GitLabAuth.KIND_OAUTH || instance.refreshToken.isBlank()) return false
    if (staleToken != null && instance.token != staleToken) return false
    if (!force && instance.tokenExpiresAt > nowEpochSec + 60) return false
    return true
  }

  suspend fun fresh(store: InstanceStore, instanceId: String, force: Boolean = false, staleToken: String? = null): GitLabInstance? {
    // Fast path: cached instance with a still-valid token needs no lock and no IO thread hop.
    val cached = store.cachedInstance(instanceId)
    if (cached != null && !needsRefresh(cached, force, staleToken)) {
      return cached
    }
    if (cached == null) {
      val loaded = withContext(Dispatchers.IO) {
        store.getInstances().firstOrNull { it.id == instanceId }
      } ?: return null
      if (!needsRefresh(loaded, force, staleToken)) return loaded
    }

    return lockFor(instanceId).withLock {
      withContext(Dispatchers.IO + NonCancellable) {
        val latest = store.getInstances().firstOrNull { it.id == instanceId } ?: return@withContext null
        if (!needsRefresh(latest, force, staleToken)) return@withContext latest
        val clientId = latest.oauthClientId.ifBlank { store.getGitLabComClientId() }
        if (clientId.isBlank()) return@withContext latest
        val tokens = try {
          GitLabOAuth.refresh(latest.url, clientId, latest.refreshToken)
        } catch (rejected: GitLabAuthException) {
          if (rejected.rejected) forgetRefreshToken(store, instanceId, latest.refreshToken)
          throw rejected
        }
        store.patch(instanceId) { current ->
          // Keep newer credentials if the user signed in again while refresh was in flight.
          if (current.refreshToken != latest.refreshToken) current
          else current.copy(
            token = tokens.accessToken,
            refreshToken = tokens.refreshToken.ifBlank { current.refreshToken },
            tokenExpiresAt = tokens.expiresAtEpochSec,
            oauthClientId = clientId,
          )
        }
      }
    }
  }

  /** Drops [used] unless the account already holds a newer refresh token (a sign-in meanwhile). */
  private fun forgetRefreshToken(store: InstanceStore, instanceId: String, used: String) {
    store.patch(instanceId) { now ->
      if (now.refreshToken == used) now.copy(refreshToken = "") else now
    }
  }
}
