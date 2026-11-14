package com.cboyxd.gitlab

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.auth.GitLabOAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import com.cboyxd.gitlab.notifications.GitLabNotificationEvent
import com.cboyxd.gitlab.notifications.GitLabNotifications
import com.cboyxd.gitlab.notifications.NotificationStore
import com.cboyxd.gitlab.ui.viewmodel.GitLabViewModel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AuthAndNotificationSecurityTest {

  private lateinit var app: GitLabApplication
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    GitLabNotifications.createChannels(app)
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certificates = HandshakeCertificates.Builder()
      .heldCertificate(certificate)
      .addTrustedCertificate(certificate.certificate)
      .build()
    server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
          path.startsWith("/api/v4/user_status") -> MockResponse().setBody("{}")
          path.startsWith("/api/v4/user") -> MockResponse().setBody("""{"id":1,"username":"dev","name":"Dev"}""")
          path.startsWith("/api/v4/version") -> MockResponse().setBody("""{"version":"17.8.0"}""")
          else -> MockResponse().setBody("[]")
        }
      }
    }
    server.start()
    GitLabApiClient.clearCache()
    GitLabApiClient.clientOverride = OkHttpClient.Builder()
      .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
      .build()
  }

  @After
  fun tearDown() {
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    server.shutdown()
  }

  private fun waitUntil(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!predicate() && System.currentTimeMillis() < deadline) {
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(10)
    }
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue("Timed out waiting for condition", predicate())
  }

  @Test
  fun `consumePendingIfStateMatches preserves pending oauth when state is missing or wrong`() {
    val pending = GitLabOAuth.begin("GitLab.com", "client-1", "https://gitlab.com")
    GitLabOAuth.savePending(app, pending)

    assertNull(GitLabOAuth.consumePendingIfStateMatches(app, null))
    assertNull(GitLabOAuth.consumePendingIfStateMatches(app, ""))
    assertNull(GitLabOAuth.consumePendingIfStateMatches(app, "wrong-state"))
    assertEquals(pending.state, GitLabOAuth.peekPending(app)?.state)

    val consumed = GitLabOAuth.consumePendingIfStateMatches(app, pending.state)
    assertEquals(pending.state, consumed?.state)
    assertNull(GitLabOAuth.peekPending(app))
  }

  @Test
  fun `completeOAuthRedirect ignores unsolicited error redirects with missing or wrong state`() {
    val store = InstanceStore.get(app)
    store.addInstance(
      GitLabInstance(
        name = "Local",
        url = server.url("/").toString(),
        token = "glpat-existing",
        username = "dev",
        isActive = true,
        authKind = GitLabAuth.KIND_PAT,
      )
    )
    val vm = GitLabViewModel(app)
    waitUntil { vm.uiState.value.bootstrapped }

    val pending = GitLabOAuth.begin("GitLab.com", "client-1", server.url("/").toString())
    GitLabOAuth.savePending(app, pending)

    // Missing state with spoofed error_description must not clear pending or show an error.
    vm.completeOAuthRedirect(Uri.parse("com.cboyxd.gitlab://oauth?error_description=Spoofed+error"))
    shadowOf(Looper.getMainLooper()).idle()
    Thread.sleep(50)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(pending.state, GitLabOAuth.peekPending(app)?.state)
    assertFalse(vm.uiState.value.loginState.isError)

    // Wrong state with spoofed error_description must also be ignored.
    vm.completeOAuthRedirect(Uri.parse("com.cboyxd.gitlab://oauth?state=evil&error_description=Spoofed+error"))
    shadowOf(Looper.getMainLooper()).idle()
    Thread.sleep(50)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(pending.state, GitLabOAuth.peekPending(app)?.state)
    assertFalse(vm.uiState.value.loginState.isError)

    // Matching state with error consumes pending and surfaces the error.
    vm.completeOAuthRedirect(Uri.parse("com.cboyxd.gitlab://oauth?state=${pending.state}&error_description=Access+denied"))
    waitUntil { vm.uiState.value.loginState.isError }
    assertEquals("Access denied", vm.uiState.value.loginState.message)
    assertNull(GitLabOAuth.peekPending(app))
  }

  @Test
  fun `deleteInstance revokes removed token in the background`() {
    val revokeLatch = CountDownLatch(1)
    val revokePaths = CopyOnWriteArrayList<String>()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        if (path == "/api/v4/personal_access_tokens/self" && request.method == "DELETE") {
          revokePaths.add("${request.method} $path ${request.getHeader("PRIVATE-TOKEN")}")
          revokeLatch.countDown()
          return MockResponse().setResponseCode(204)
        }
        return when {
          path.startsWith("/api/v4/user_status") -> MockResponse().setBody("{}")
          path.startsWith("/api/v4/user") -> MockResponse().setBody("""{"id":1,"username":"dev","name":"Dev"}""")
          path.startsWith("/api/v4/version") -> MockResponse().setBody("""{"version":"17.8.0"}""")
          else -> MockResponse().setBody("[]")
        }
      }
    }

    val store = InstanceStore.get(app)
    store.addInstance(
      GitLabInstance(
        name = "Backup",
        url = server.url("/").toString(),
        token = "glpat-keep",
        username = "dev2",
        isActive = false,
        authKind = GitLabAuth.KIND_PAT,
      )
    )
    val saved = store.addInstance(
      GitLabInstance(
        name = "Local",
        url = server.url("/").toString(),
        token = "glpat-to-revoke",
        username = "dev",
        isActive = true,
        authKind = GitLabAuth.KIND_PASSWORD,
      )
    )
    val vm = GitLabViewModel(app)
    waitUntil { vm.uiState.value.bootstrapped }

    vm.deleteInstance(saved.id)
    waitUntil { vm.uiState.value.savedInstances.size == 1 }
    assertTrue("Expected DELETE /api/v4/personal_access_tokens/self", revokeLatch.await(5, TimeUnit.SECONDS))
    assertTrue(revokePaths.any { it == "DELETE /api/v4/personal_access_tokens/self glpat-to-revoke" })
  }

  @Test
  fun `notification open intent carries secret token and rejects spoofed intents`() {
    val event = GitLabNotificationEvent(
      id = "evt-1",
      accountId = "acc-123",
      title = "You were mentioned",
      message = "#7 Title",
      targetType = "Issue",
      projectId = 10L,
      iid = 7L,
      url = "https://gitlab.com/org/repo/-/issues/7",
      channel = GitLabNotifications.ACTIVITY,
    )
    assertTrue(GitLabNotifications.show(app, event))

    val manager = app.getSystemService(NotificationManager::class.java)
    val posted = manager.activeNotifications.first { it.tag == "acc-123:evt-1" }.notification
    val savedIntent = shadowOf(posted.contentIntent).savedIntent

    val trusted = GitLabNotifications.parseTrustedOpenIntent(app, savedIntent)
    assertNotNull(trusted)
    assertEquals("acc-123", trusted!!.accountId)
    assertEquals("Issue", trusted.targetType)
    assertEquals(10L, trusted.projectId)
    assertEquals(7L, trusted.iid)
    assertEquals("https://gitlab.com/org/repo/-/issues/7", trusted.url)

    // External app forging ACTION_OPEN without token or with a wrong token is rejected.
    val spoofedNoToken = Intent(app, MainActivity::class.java).apply {
      action = GitLabNotifications.ACTION_OPEN
      putExtra(GitLabNotifications.EXTRA_ACCOUNT, "acc-123")
      putExtra(GitLabNotifications.EXTRA_URL, "https://evil.example/phish")
    }
    assertFalse(GitLabNotifications.isTrustedOpenIntent(app, spoofedNoToken))
    assertNull(GitLabNotifications.parseTrustedOpenIntent(app, spoofedNoToken))

    val spoofedWrongToken = Intent(spoofedNoToken).apply {
      putExtra(GitLabNotifications.EXTRA_TOKEN, "forged-token")
    }
    assertFalse(GitLabNotifications.isTrustedOpenIntent(app, spoofedWrongToken))
    assertNull(GitLabNotifications.parseTrustedOpenIntent(app, spoofedWrongToken))
  }

  @Test
  fun `notification open token persists across store instances and survives account prune`() {
    val store1 = NotificationStore(app)
    val token1 = store1.openIntentToken()
    assertNotEquals("", token1)

    store1.prune(emptySet())
    val store2 = NotificationStore(app)
    assertEquals(token1, store2.openIntentToken())
  }
}
