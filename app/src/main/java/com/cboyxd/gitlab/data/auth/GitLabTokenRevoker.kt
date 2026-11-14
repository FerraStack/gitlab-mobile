package com.cboyxd.gitlab.data.auth

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.model.GitLabInstance
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Best-effort token revocation when an account is removed or replaced so stale tokens do not
 * remain active on the server.
 */
object GitLabTokenRevoker {
  private val defaultClient: OkHttpClient by lazy {
    GitLabApiClient.baseHttpClient.newBuilder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .callTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  fun revoke(
    instance: GitLabInstance,
    fallbackClientId: String = "",
    http: OkHttpClient? = null,
  ): Boolean {
    val token = instance.token.trim()
    if (token.isEmpty() || instance.authKind == GitLabAuth.KIND_PUBLIC) return false
    val client = http ?: GitLabApiClient.clientOverride ?: defaultClient
    if (instance.authKind == GitLabAuth.KIND_OAUTH) {
      val clientId = instance.oauthClientId.ifBlank { fallbackClientId }
      return GitLabOAuth.revoke(
        baseUrl = instance.url,
        clientId = clientId,
        token = token,
        refreshToken = instance.refreshToken,
        http = client,
      )
    }
    val root = runCatching { GitLabAuth.rootUrl(instance.url) }.getOrNull() ?: return false
    val request = Request.Builder()
      .url("$root/api/v4/personal_access_tokens/self")
      .header("User-Agent", "GitLabMobile/1.0 (Android)")
      .header("Accept", "application/json")
      .apply {
        if (token.startsWith("Bearer ", ignoreCase = true)) {
          header("Authorization", "Bearer ${token.substringAfter(' ').trim()}")
        } else {
          header("PRIVATE-TOKEN", token)
        }
      }
      .delete()
      .build()
    return try {
      client.newCall(request).execute().use { it.isSuccessful }
    } catch (_: IOException) {
      false
    }
  }
}
