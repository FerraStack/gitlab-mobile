package com.cboyxd.gitlab

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import com.cboyxd.gitlab.notifications.InstantNotificationService
import com.cboyxd.gitlab.notifications.InstantSettings
import com.cboyxd.gitlab.notifications.PollLog
import com.cboyxd.gitlab.notifications.PollOutcome
import com.cboyxd.gitlab.notifications.PollTrigger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * End to end on a device: instant notifications against a fake GitLab (MockWebServer over HTTPS
 * on localhost). The first check sets the baseline; the server then reports a new to-do, and the
 * notification must appear by the second check. CI saves a screenshot of the notification shade.
 */
@RunWith(AndroidJUnit4::class)
class InstantNotificationsDeviceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val device = UiDevice.getInstance(instrumentation)
  private lateinit var server: MockWebServer

  @Volatile private var baselineServed = false

  private fun json(body: String) = MockResponse().setBody(body).addHeader("Content-Type", "application/json")

  private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date())

  private val todo get() = """[{"id":42,"action_name":"mentioned","target_type":"Issue",
    "target":{"iid":7,"project_id":1,"title":"Launch button does nothing on tablets"},
    "project":{"id":1,"name":"rocket","path_with_namespace":"example-org/rocket"},
    "author":{"id":2,"username":"pat","name":"Pat Reviewer"},
    "body":"@tanuki can you check this on a tablet?","state":"pending","created_at":"${now()}",
    "target_url":"https://gitlab.example.com/example-org/rocket/-/issues/7"}]"""

  @Before
  fun setUp() {
    if (Build.VERSION.SDK_INT >= 33) {
      instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certificates = HandshakeCertificates.Builder()
      .heldCertificate(certificate)
      .addTrustedCertificate(certificate.certificate)
      .build()
    server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
        return when (url.encodedPath) {
          "/api/v4/user_counts" -> json("""{"todos":${if (baselineServed) 1 else 0}}""")
          "/api/v4/todos" -> when {
            url.queryParameter("state") != "pending" -> json("[]")
            !baselineServed -> json("[]").also { baselineServed = true }
            else -> json(todo)
          }
          "/api/v4/merge_requests" -> json("[]")
          else -> MockResponse().setResponseCode(404).setBody("""{"message":"404 Not Found"}""")
        }
      }
    }
    server.start()
    GitLabApiClient.clearCache()
    GitLabApiClient.clientOverride = OkHttpClient.Builder()
      .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
      .build()

    PollLog(context).clear()
    val store = InstanceStore.get(context)
    store.getInstances().forEach { store.deleteInstance(it.id) }
    store.addInstance(
      GitLabInstance(
        name = "Test GitLab",
        url = server.url("/").toString(),
        token = "glpat-test",
        username = "tanuki",
        isActive = true,
        authKind = GitLabAuth.KIND_PAT,
      ),
    )
    InstantSettings(context).apply {
      intervalSeconds = 5
      enabled = true
    }
    device.wakeUp()
  }

  @After
  fun tearDown() {
    device.pressHome()
    InstantSettings(context).enabled = false
    InstantNotificationService.sync(context)
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    server.shutdown()
  }

  @Test
  fun instantNotificationsShowANewToDoWithinTwoChecks() {
    assertTrue("service didn't start", InstantNotificationService.sync(context))

    val manager = context.getSystemService(NotificationManager::class.java)
    val deadline = SystemClock.elapsedRealtime() + 90_000
    var shown: StatusBarNotification? = null
    while (shown == null && SystemClock.elapsedRealtime() < deadline) {
      shown = manager.activeNotifications.firstOrNull {
        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "You were mentioned"
      }
      if (shown == null) SystemClock.sleep(500)
    }
    assertNotNull("no notification for the new to-do within 90 s", shown)

    // Delivered by the instant service, in its first or second full check.
    val fullChecks = PollLog(context).runs()
      .filter { it.trigger == PollTrigger.INSTANT && it.outcome != PollOutcome.UNCHANGED }
      .sortedBy { it.startedAt }
    val delivering = fullChecks.indexOfFirst { it.delivered > 0 }
    assertTrue("delivered by check ${delivering + 1} of ${fullChecks.size}", delivering in 0..1)

    device.openNotification()
    device.wait(Until.hasObject(By.text("You were mentioned")), 10_000)
    device.waitForIdle()
    val shot = File(context.getExternalFilesDir(null), "emulator_notification_shade.png")
    device.takeScreenshot(shot)
  }
}
