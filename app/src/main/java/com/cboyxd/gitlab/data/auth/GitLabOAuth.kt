package com.cboyxd.gitlab.data.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.cboyxd.gitlab.IncomingLinks
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.storage.AndroidKeyStoreSecretStore
import com.cboyxd.gitlab.viewResolvesToThisApp
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class PendingOAuth(
  val state: String,
  val verifier: String,
  val name: String,
  val clientId: String,
  val baseUrl: String = "https://gitlab.com"
)

object GitLabOAuth {
  private const val PREFS = "gitlab_oauth_pending"
  private const val KEY_STATE = "state"
  private const val KEY_VERIFIER = "verifier"
  private const val KEY_NAME = "name"
  private const val KEY_CLIENT = "client_id"
  private const val KEY_BASE = "base_url"

  private val defaultTokenClient: OkHttpClient by lazy {
    configureTokenClient(GitLabApiClient.baseHttpClient.newBuilder()).build()
  }

  @Volatile
  private var cachedOverride: Pair<OkHttpClient, OkHttpClient>? = null

  fun begin(name: String, clientId: String, baseUrl: String = "https://gitlab.com"): PendingOAuth = PendingOAuth(
    state = GitLabAuth.randomVerifier(),
    verifier = GitLabAuth.randomVerifier(),
    name = name.ifBlank { GitLabAuth.rootUrl(baseUrl).removePrefix("https://").trimEnd('/') },
    clientId = clientId.trim(),
    baseUrl = GitLabAuth.rootUrl(baseUrl)
  )

  fun authorizationUrl(pending: PendingOAuth, provider: String = ""): String =
    GitLabAuth.authorizationUrl(pending.baseUrl, pending.clientId, pending.state, pending.verifier, provider)

  fun savePending(context: Context, pending: PendingOAuth) {
    val secrets = AndroidKeyStoreSecretStore(context, PREFS)
    secrets.put(KEY_STATE, pending.state)
    secrets.put(KEY_VERIFIER, pending.verifier)
    secrets.put(KEY_NAME, pending.name)
    secrets.put(KEY_CLIENT, pending.clientId)
    secrets.put(KEY_BASE, pending.baseUrl)
  }

  fun peekPending(context: Context): PendingOAuth? {
    val secrets = AndroidKeyStoreSecretStore(context, PREFS)
    val state = secrets.get(KEY_STATE)
    val verifier = secrets.get(KEY_VERIFIER)
    val clientId = secrets.get(KEY_CLIENT)
    if (state.isNullOrBlank() || verifier.isNullOrBlank() || clientId.isNullOrBlank()) return null
    return PendingOAuth(
      state = state,
      verifier = verifier,
      name = secrets.get(KEY_NAME).orEmpty().ifBlank { "GitLab.com" },
      clientId = clientId,
      baseUrl = secrets.get(KEY_BASE).orEmpty().ifBlank { "https://gitlab.com" }
    )
  }

  fun consumePending(context: Context): PendingOAuth? {
    val pending = peekPending(context) ?: return null
    AndroidKeyStoreSecretStore(context, PREFS).clear()
    return pending
  }

  /**
   * Returns and clears the pending sign-in only when [state] matches its saved OAuth state, so
   * unsolicited redirects cannot cancel an in-progress sign-in or spoof errors.
   */
  fun consumePendingIfStateMatches(context: Context, state: String?): PendingOAuth? {
    if (state.isNullOrBlank()) return null
    val pending = peekPending(context) ?: return null
    if (!MessageDigest.isEqual(state.toByteArray(Charsets.UTF_8), pending.state.toByteArray(Charsets.UTF_8))) {
      return null
    }
    AndroidKeyStoreSecretStore(context, PREFS).clear()
    return pending
  }

  fun openBrowser(context: Context, pending: PendingOAuth, provider: String = "") {
    val uri = Uri.parse(authorizationUrl(pending, provider))
    val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
    tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // The https filter can claim gitlab.com. The authorize page still has to load in a
    // browser, so GitLab can redirect back to the oauth scheme.
    if (IncomingLinks.claimsHost(uri.host) && viewResolvesToThisApp(context, uri)) {
      val browser = CustomTabsClient.getPackageName(context, null)?.takeIf { it != context.packageName }
        ?: throw ActivityNotFoundException()
      tabs.intent.setPackage(browser)
    }
    tabs.launchUrl(context, uri)
  }

  fun exchange(pending: PendingOAuth, code: String, http: OkHttpClient = tokenClient()): OAuthTokenSet {
    val body = FormBody.Builder()
      .add("grant_type", "authorization_code")
      .add("client_id", pending.clientId)
      .add("code", code)
      .add("redirect_uri", GitLabAuth.REDIRECT_URI)
      .add("code_verifier", pending.verifier)
      .build()
    return postToken(pending.baseUrl, body, http)
  }

  fun refresh(baseUrl: String, clientId: String, refreshToken: String, http: OkHttpClient = tokenClient()): OAuthTokenSet {
    val body = FormBody.Builder()
      .add("grant_type", "refresh_token")
      .add("client_id", clientId)
      .add("refresh_token", refreshToken)
      .add("redirect_uri", GitLabAuth.REDIRECT_URI)
      .build()
    return postToken(baseUrl, body, http)
  }

  /** Best-effort RFC 7009 revocation of OAuth tokens on account removal or replacement. */
  fun revoke(
    baseUrl: String,
    clientId: String,
    token: String,
    refreshToken: String = "",
    http: OkHttpClient = tokenClient(),
  ): Boolean {
    val root = runCatching { GitLabAuth.rootUrl(baseUrl) }.getOrNull() ?: return false
    var anyAttempted = false
    var allSucceeded = true
    val targets = buildList {
      if (refreshToken.isNotBlank()) add(refreshToken to "refresh_token")
      if (token.isNotBlank() && token != refreshToken) add(token to "access_token")
    }
    for ((secret, hint) in targets) {
      anyAttempted = true
      val form = FormBody.Builder().apply {
        if (clientId.isNotBlank()) add("client_id", clientId)
        add("token", secret)
        add("token_type_hint", hint)
      }.build()
      val request = Request.Builder()
        .url("$root/oauth/revoke")
        .header("Accept", "application/json")
        .header("User-Agent", "GitLabMobile/1.0 (Android)")
        .post(form)
        .build()
      val ok = try {
        http.newCall(request).execute().use { it.isSuccessful }
      } catch (_: IOException) {
        false
      }
      if (!ok) allSucceeded = false
    }
    return anyAttempted && allSucceeded
  }

  private fun postToken(baseUrl: String, body: FormBody, http: OkHttpClient): OAuthTokenSet {
    val request = Request.Builder()
      .url(GitLabAuth.rootUrl(baseUrl) + "/oauth/token")
      .header("Accept", "application/json")
      .header("User-Agent", "GitLabMobile/1.0 (Android)")
      .post(body)
      .build()
    try {
      http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          // RFC 6749 answers invalid_grant / invalid_client with 400 or 401; 5xx is the server's problem.
          throw GitLabAuthException(
            GitLabAuth.accessError(raw) ?: "GitLab sign-in failed (${response.code}).",
            rejected = response.code == 400 || response.code == 401,
          )
        }
        return GitLabAuth.parseOAuthTokens(raw)
      }
    } catch (error: IOException) {
      throw GitLabAuthException("Couldn't reach GitLab.")
    }
  }

  // Tests reach MockWebServer through the API client's override (it trusts the test certificate).
  internal fun tokenClient(): OkHttpClient {
    val override = GitLabApiClient.clientOverride ?: return defaultTokenClient
    cachedOverride?.takeIf { it.first === override }?.let { return it.second }
    val built = configureTokenClient(override.newBuilder()).build()
    cachedOverride = override to built
    return built
  }

  private fun configureTokenClient(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
}
