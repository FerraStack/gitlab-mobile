package com.cboyxd.gitlab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.auth.GitLabAuthException
import com.cboyxd.gitlab.data.auth.GitLabSessionTokens
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Refreshing OAuth tokens: a refresh token GitLab rejected is not tried again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionTokensTest {

  private lateinit var server: MockWebServer
  private lateinit var store: InstanceStore
  private lateinit var account: GitLabInstance

  @Before
  fun setUp() {
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).addTrustedCertificate(certificate.certificate).build()
    server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.start()
    GitLabApiClient.clientOverride = OkHttpClient.Builder().sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager).build()
    store = InstanceStore(ApplicationProvider.getApplicationContext<Context>())
    account = GitLabInstance(
      name = "Work",
      url = server.url("/").toString(),
      token = "old-access",
      username = "dev",
      isActive = true,
      isCustom = true,
      authKind = GitLabAuth.KIND_OAUTH,
      refreshToken = "refresh-1",
      tokenExpiresAt = 0,
      oauthClientId = "client-1",
    )
    account = store.addInstance(account)
  }

  @After
  fun tearDown() {
    GitLabApiClient.clientOverride = null
    server.shutdown()
  }

  private fun stored() = store.getInstances().single { it.id == account.id }

  private fun refresh(force: Boolean = false): Result<GitLabInstance?> = runBlocking {
    runCatching { GitLabSessionTokens.fresh(store, account.id, force = force) }
  }

  @Test
  fun `a rejected refresh token is dropped and never sent again`() {
    server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"The provided authorization grant is invalid, expired, revoked, does not match the redirection URI used in the authorization request, or was issued to another client."}"""))

    val error = refresh().exceptionOrNull()

    assertTrue(error is GitLabAuthException && error.rejected)
    assertEquals("", stored().refreshToken)
    assertEquals("old-access", stored().token)
    // Later calls, forced or not, make no further token requests.
    assertEquals("old-access", refresh().getOrThrow()?.token)
    assertEquals("old-access", refresh(force = true).getOrThrow()?.token)
    assertEquals(1, server.requestCount)
    assertEquals("/oauth/token", server.takeRequest().path)
  }

  @Test
  fun `a server error keeps the refresh token for the next try`() {
    server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Service Unavailable"}"""))
    val error = refresh().exceptionOrNull()
    assertTrue(error is GitLabAuthException && !error.rejected)
    assertEquals("refresh-1", stored().refreshToken)
  }

  @Test
  fun `no network keeps the refresh token and isn't a rejection`() {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
    val error = refresh().exceptionOrNull()
    assertTrue(error is GitLabAuthException && !error.rejected)
    assertEquals("Couldn't reach GitLab.", error!!.message)
    assertEquals("refresh-1", stored().refreshToken)
  }

  @Test
  fun `a sign-in that lands meanwhile keeps its new refresh token`() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        // The user signs in again while the old refresh token is being turned down.
        store.updateInstance(stored().copy(token = "new-access", refreshToken = "refresh-2"))
        return MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
      }
    }
    refresh()
    assertEquals("refresh-2", stored().refreshToken)
  }

  @Test
  fun `a successful refresh stores the new tokens`() {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"access_token":"new-access","refresh_token":"refresh-2","expires_in":7200,"created_at":1762500000,"token_type":"Bearer"}"""))
    assertEquals("new-access", refresh().getOrThrow()?.token)
    assertEquals("refresh-2", stored().refreshToken)
  }
}
