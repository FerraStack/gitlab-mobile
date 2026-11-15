package com.cboyxd.gitlab

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
import com.cboyxd.gitlab.notifications.GitLabNotificationMonitor
import com.cboyxd.gitlab.notifications.PollLog
import com.cboyxd.gitlab.notifications.PollOutcome
import com.cboyxd.gitlab.notifications.PollTrigger
import com.cboyxd.gitlab.widget.WidgetStore
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicInteger

/** Unit tests for [GitLabNotificationMonitor]: baseline, dedupe, suppression, errors, sign-out guard, and OAuth refresh (#62). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationMonitorTest {

  private lateinit var app: Application
  private lateinit var store: InstanceStore
  private lateinit var server: MockWebServer
  private lateinit var manager: NotificationManager

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    manager = app.getSystemService(NotificationManager::class.java)
    manager.cancelAll()
    PollLog(app).clear()
    app.getSharedPreferences("gitlab_notification_state", Context.MODE_PRIVATE).edit().clear().commit()
    WidgetStore.clear(app)
    store = InstanceStore.get(app)
    store.resetAll()

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
    server.shutdown()
    store.resetAll()
    WidgetStore.clear(app)
  }

  private fun json(body: String) = MockResponse().addHeader("Content-Type", "application/json").setBody(body)

  private fun addPatAccount(id: String = "acc-1"): GitLabInstance = store.addInstance(
    GitLabInstance(
      id = id,
      name = "Work",
      url = server.url("/").toString(),
      token = "glpat-test",
      username = "dev",
      isActive = true,
      isCustom = true,
      authKind = GitLabAuth.KIND_PAT,
    ),
  )

  @Test
  fun `first poll sets baseline, second poll delivers new events and suppresses duplicate MR todos, third poll dedupes`() = runBlocking {
    val account = addPatAccount()
    val phase = AtomicInteger(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val path = url.encodedPath
        val p = phase.get()
        return when {
          path.endsWith("/api/v4/todos") && url.queryParameter("state") == "pending" -> {
            if (p == 1) {
              json(
                """[{"id":1,"action_name":"mentioned","target_type":"Issue","target":{"iid":1,"title":"Base","project_id":1},"state":"pending","created_at":"2026-11-15T07:00:00Z","author":{"id":2,"username":"alice","name":"Alice"}}]""",
              )
            } else {
              // Future created_at timestamps avoid cursor/clock skew in Robolectric tests.
              json(
                """
                [
                  {"id":1,"action_name":"mentioned","target_type":"Issue","target":{"iid":1,"title":"Base","project_id":1},"state":"pending","created_at":"2026-11-15T07:00:00Z","author":{"id":2,"username":"alice","name":"Alice"}},
                  {"id":2,"action_name":"assigned","target_type":"MergeRequest","target":{"iid":20,"title":"Assigned MR","project_id":1},"state":"pending","created_at":"2030-01-01T00:00:00Z","author":{"id":2,"username":"alice","name":"Alice"}},
                  {"id":3,"action_name":"review_requested","target_type":"MergeRequest","target":{"iid":21,"title":"Review MR","project_id":1},"state":"pending","created_at":"2030-01-01T00:01:00Z","author":{"id":2,"username":"alice","name":"Alice"}},
                  {"id":4,"action_name":"build_failed","target_type":"MergeRequest","target":{"iid":22,"title":"Broken CI","project_id":1},"state":"pending","created_at":"2030-01-01T00:02:00Z","author":{"id":3,"username":"bot","name":"Bot"}},
                  {"id":5,"action_name":"mentioned","target_type":"Issue","target":{"iid":23,"title":"Ping","project_id":1},"state":"pending","created_at":"2030-01-01T00:03:00Z","author":{"id":2,"username":"alice","name":"Alice"}}
                ]
                """.trimIndent(),
              )
            }
          }
          path.endsWith("/api/v4/todos") && url.queryParameter("state") == "done" -> json("[]")
          path.endsWith("/api/v4/merge_requests") -> {
            val scope = url.queryParameter("scope")
            val reviewer = url.queryParameter("reviewer_username")
            when {
              scope == "assigned_to_me" -> {
                if (p == 1) json("""[{"id":110,"iid":10,"project_id":1,"title":"Initial assigned","state":"opened","updated_at":"2026-11-15T07:00:00Z","web_url":"https://git/org/app/-/merge_requests/10"}]""")
                else json(
                  """[
                    {"id":110,"iid":10,"project_id":1,"title":"Initial assigned","state":"opened","updated_at":"2026-11-15T07:00:00Z","web_url":"https://git/org/app/-/merge_requests/10"},
                    {"id":120,"iid":20,"project_id":1,"title":"Assigned MR","state":"opened","updated_at":"2030-01-01T00:00:00Z","web_url":"https://git/org/app/-/merge_requests/20"}
                  ]""".trimIndent(),
                )
              }
              reviewer == "dev" -> {
                if (p == 1) json("""[{"id":111,"iid":11,"project_id":1,"title":"Initial review","state":"opened","updated_at":"2026-11-15T07:00:00Z","web_url":"https://git/org/app/-/merge_requests/11"}]""")
                else json(
                  """[
                    {"id":111,"iid":11,"project_id":1,"title":"Initial review","state":"opened","updated_at":"2026-11-15T07:00:00Z","web_url":"https://git/org/app/-/merge_requests/11"},
                    {"id":121,"iid":21,"project_id":1,"title":"Review MR","state":"opened","updated_at":"2030-01-01T00:01:00Z","web_url":"https://git/org/app/-/merge_requests/21"}
                  ]""".trimIndent(),
                )
              }
              scope == "created_by_me" -> {
                if (p == 1) json("""[{"id":112,"iid":12,"project_id":1,"title":"My feature","state":"opened","updated_at":"2026-11-15T07:00:00Z","web_url":"https://git/org/app/-/merge_requests/12"}]""")
                else json("""[{"id":112,"iid":12,"project_id":1,"title":"My feature","state":"merged","merged_at":"2030-01-01T00:04:00Z","updated_at":"2030-01-01T00:04:00Z","merge_user":{"id":2,"username":"alice","name":"Alice"},"web_url":"https://git/org/app/-/merge_requests/12"}]""")
              }
              else -> json("[]")
            }
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
    }

    // 1. Baseline poll: delivers 0 notifications.
    assertTrue(GitLabNotificationMonitor.poll(app, PollTrigger.MANUAL))
    assertEquals(0, PollLog(app).runs().first().delivered)
    assertEquals(0, Shadows.shadowOf(manager).allNotifications.size)

    // 2. Second poll: delivers assigned !20, review !21, merged !12, build_failed todo:4, mentioned todo:5 (suppressing duplicate todo:2 and todo:3).
    phase.set(2)
    assertTrue(GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC))
    val run2 = PollLog(app).runs().first()
    assertEquals(PollOutcome.OK, run2.outcome)
    assertEquals(5, run2.delivered)

    val titles = Shadows.shadowOf(manager).allNotifications
      .mapNotNull { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
    assertTrue(titles.contains("Merge request assigned to you"))
    assertTrue(titles.contains("Review requested from you"))
    assertTrue(titles.contains("Your merge request was merged"))
    assertTrue(titles.contains("Pipeline failed"))
    assertTrue(titles.contains("You were mentioned"))

    // 3. Third poll with identical data delivers nothing new.
    phase.set(3)
    assertTrue(GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC))
    val run3 = PollLog(app).runs().first()
    assertEquals(0, run3.delivered)
    assertEquals(account.id, run3.accounts.single().accountId)
  }

  @Test
  fun `HTTP 500 marks poll retryable while HTTP 404 is non-retryable`() = runBlocking {
    addPatAccount()
    val errorCode = AtomicInteger(500)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        return if (path.endsWith("/api/v4/todos")) {
          MockResponse().setResponseCode(errorCode.get()).setBody("""{"message":"err"}""")
        } else {
          json("[]")
        }
      }
    }

    assertFalse(GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC))
    assertEquals(PollOutcome.FAILED, PollLog(app).runs().first().outcome)

    errorCode.set(404)
    assertTrue(GitLabNotificationMonitor.poll(app, PollTrigger.PERIODIC))
  }

  @Test
  fun `signing out mid-poll prevents delivering events for the removed account`() = runBlocking {
    val account = addPatAccount()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        if (path.endsWith("/api/v4/todos")) {
          store.deleteInstance(account.id)
          return json("""[{"id":99,"action_name":"mentioned","target_type":"Issue","target":{"iid":1,"title":"Late","project_id":1},"state":"pending","created_at":"2030-01-01T00:00:00Z","author":{"id":2,"username":"alice","name":"Alice"}}]""")
        }
        return json("[]")
      }
    }

    assertTrue(GitLabNotificationMonitor.poll(app, PollTrigger.MANUAL))
    assertEquals(0, PollLog(app).runs().first().delivered)
    assertEquals(0, Shadows.shadowOf(manager).allNotifications.size)
  }

  @Test
  fun `OAuth 401 triggers token refresh and retries the feed request`() = runBlocking {
    val account = store.addInstance(
      GitLabInstance(
        id = "oauth-1",
        name = "OAuth",
        url = server.url("/").toString(),
        token = "stale-token",
        username = "dev",
        isActive = true,
        isCustom = true,
        authKind = GitLabAuth.KIND_OAUTH,
        refreshToken = "refresh-1",
        tokenExpiresAt = System.currentTimeMillis() / 1000 + 3600,
        oauthClientId = "client-1",
      ),
    )
    val tokenRefreshed = AtomicInteger(0)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        if (path.endsWith("/oauth/token")) {
          tokenRefreshed.incrementAndGet()
          return json("""{"access_token":"fresh-token","refresh_token":"refresh-2","expires_in":7200,"created_at":1762500000,"token_type":"Bearer"}""")
        }
        val auth = request.getHeader("Authorization").orEmpty()
        if (!auth.contains("fresh-token")) {
          return MockResponse().setResponseCode(401).setBody("""{"error":"invalid_token"}""")
        }
        return json("[]")
      }
    }

    assertTrue(GitLabNotificationMonitor.poll(app, PollTrigger.MANUAL))
    assertEquals(1, tokenRefreshed.get())
    assertEquals("fresh-token", store.getInstances().single { it.id == account.id }.token)
    assertEquals(PollOutcome.OK, PollLog(app).runs().first().outcome)
  }
}
