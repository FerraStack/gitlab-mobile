package com.cboyxd.gitlab.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationWorkerAndStoreTest {

  private lateinit var app: Application
  private lateinit var manager: NotificationManager
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    InstanceStore.resetForTests()
    app = ApplicationProvider.getApplicationContext()
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    GitLabNotifications.createChannels(app)
    manager = app.getSystemService(NotificationManager::class.java)
    manager.cancelAll()
    PollLog(app).clear()

    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certificates = HandshakeCertificates.Builder()
      .heldCertificate(certificate)
      .addTrustedCertificate(certificate.certificate)
      .build()
    server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
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
    InstanceStore.resetForTests()
    server.shutdown()
  }

  private fun addServerAccount(id: String = "acc-1"): GitLabInstance {
    val store = InstanceStore.get(app)
    return store.addInstance(
      GitLabInstance(
        id = id,
        name = "Company GitLab",
        url = server.url("/").toString(),
        token = "glpat-test",
        username = "dev",
        isActive = true,
        isCustom = true,
        authKind = GitLabAuth.KIND_PAT,
      )
    )
  }

  private fun event(id: String, at: Long, accountId: String = "acc-1", todoId: Long? = null) = GitLabNotificationEvent(
    id = id,
    accountId = accountId,
    title = "Title $id",
    message = "Message $id",
    targetType = "Issue",
    projectId = 10L,
    iid = 5L,
    url = "https://gitlab.example/proj/-/issues/5",
    channel = GitLabNotifications.ACTIVITY,
    createdAt = at,
    accountName = "Company GitLab",
    todoId = todoId,
  )

  @Test
  fun `dedupe store keeps insertion order across instances and drops oldest entries`() {
    val first = NotificationStore(app, maxDelivered = 3)
    assertTrue(first.record(event("e1", 100L)))
    assertTrue(first.record(event("e2", 200L)))
    assertTrue(first.record(event("e3", 300L)))
    assertFalse(first.record(event("e2", 250L)))

    // Simulate a fresh process reading from SharedPreferences.
    val restarted = NotificationStore(app, maxDelivered = 3)
    assertTrue(restarted.record(event("e4", 400L)))
    assertTrue(restarted.record(event("e5", 500L)))

    val verifier = NotificationStore(app, maxDelivered = 3)
    assertEquals(listOf("e3", "e4", "e5"), verifier.recordedIds("acc-1"))
    assertEquals(listOf(300L, 400L, 500L), verifier.recordedEntries("acc-1").map { it.at })
    assertFalse(verifier.record(event("e4", 400L)))
    // e1 was deterministically dropped when the window rolled over.
    assertTrue(verifier.record(event("e1", 600L)))
    assertEquals(listOf("e4", "e5", "e1"), verifier.recordedIds("acc-1"))
  }

  @Test
  fun `legacy string set entries migrate into ordered array`() {
    val prefs = app.getSharedPreferences("gitlab_notification_state", Application.MODE_PRIVATE)
    prefs.edit().putStringSet("delivered:legacy-acc", setOf("old-a", "old-b")).commit()

    val store = NotificationStore(app, maxDelivered = 5)
    assertTrue(store.record(event("new-1", 900L, accountId = "legacy-acc")))
    val ids = store.recordedIds("legacy-acc")
    assertTrue(ids.containsAll(listOf("old-a", "old-b", "new-1")))
    assertEquals("new-1", ids.last())
    assertFalse(prefs.contains("delivered:legacy-acc"))
  }

  @Test
  fun `polling skips all network work when notifications are not allowed`() = runBlocking {
    addServerAccount()
    shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    assertTrue(GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC))
    assertEquals(0, server.requestCount)
    assertEquals(PollOutcome.NOTIFICATIONS_DISABLED, PollLog(app).runs().first().outcome)
  }

  @Test
  fun `unauthorized poll marks authFailure, stops polling, and notifies once`() = runBlocking {
    val account = addServerAccount("acc-401")
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse =
        MockResponse().setResponseCode(401).setBody("""{"message":"401 Unauthorized"}""")
    }

    GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC)

    val saved = InstanceStore.get(app).getInstances().first { it.id == account.id }
    assertTrue(saved.authFailure)
    val authTag = "${account.id}:${GitLabNotifications.AUTH_FAILURE}"
    val posted = manager.activeNotifications.firstOrNull { it.tag == authTag }?.notification
    assertNotNull(posted)
    assertEquals(
      "Sign in again to keep getting notifications",
      posted!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
    )
    assertTrue(NotificationStore(app).isAuthFailureNotified(account.id))

    val requestsAfterFirstPoll = server.requestCount
    assertTrue(requestsAfterFirstPoll > 0)
    manager.cancelAll()

    // Second poll must skip the broken account and not re-post the auth-failure notification.
    GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC)
    assertEquals(requestsAfterFirstPoll, server.requestCount)
    assertNull(manager.activeNotifications.firstOrNull { it.tag == authTag })

    // Clearing authFailure (re-authentication) resumes polling and resets the notified flag.
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse =
        MockResponse().addHeader("Content-Type", "application/json").setBody("[]")
    }
    InstanceStore.get(app).setAuthFailure(account.id, false)
    GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC)
    assertTrue(server.requestCount > requestsAfterFirstPoll)
    assertFalse(NotificationStore(app).isAuthFailureNotified(account.id))
  }

  @Test
  fun `resolveCursor prefers server timestamps over skewed device clock`() {
    val serverDate = 1_700_000_000_000L
    val olderItem = 1_699_999_900_000L
    val newerItem = 1_700_000_050_000L
    val fastPhoneClock = 9_000_000_000_000L

    assertEquals(serverDate, GitLabNotificationMonitor.resolveCursor(serverDate, olderItem, 0L, fastPhoneClock))
    assertEquals(newerItem, GitLabNotificationMonitor.resolveCursor(serverDate, newerItem, 0L, fastPhoneClock))
    assertEquals(newerItem, GitLabNotificationMonitor.resolveCursor(null, newerItem, 0L, fastPhoneClock))
    assertEquals(serverDate, GitLabNotificationMonitor.resolveCursor(null, null, serverDate, fastPhoneClock))
    assertEquals(fastPhoneClock, GitLabNotificationMonitor.resolveCursor(null, null, 0L, fastPhoneClock))
  }

  @Test
  fun `poll advances todo cursor from server Date header`() = runBlocking {
    val account = addServerAccount("acc-clock")
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .addHeader("Date", "Wed, 15 Nov 2023 12:00:00 GMT")
        .setBody("[]")
    }

    assertTrue(GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC))
    // Wed, 15 Nov 2023 12:00:00 GMT == 1700049600000 ms
    assertEquals(1_700_049_600_000L, NotificationStore(app).cursor(account.id, "todos"))
  }

  @Test
  fun `todo notification adds mark as done action and receiver marks done on server`() = runBlocking {
    val account = addServerAccount("acc-action")
    val todoEvent = event("todo:42", System.currentTimeMillis(), accountId = account.id, todoId = 42L)
    assertTrue(GitLabNotifications.show(app, todoEvent))

    val tag = "${account.id}:todo:42"
    val posted = manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
    assertNotNull(posted)
    assertTrue(posted!!.actions?.any { it.title?.toString() == "Mark as done" } == true)

    server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
    val ok = NotificationActionReceiver.handleMarkTodoDone(app, account.id, 42L, tag, account.name)

    assertTrue(ok)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/v4/todos/42/mark_as_done", request.path)
    assertNull(manager.activeNotifications.firstOrNull { it.tag == tag })
  }
}
