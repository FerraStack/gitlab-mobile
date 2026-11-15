package com.cboyxd.gitlab

import android.app.NotificationManager
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import com.cboyxd.gitlab.notifications.GitLabNotificationMonitor
import com.cboyxd.gitlab.notifications.PollLog
import com.cboyxd.gitlab.widget.GitLabWidgetProvider
import com.cboyxd.gitlab.widget.WidgetPipelineSummary
import com.cboyxd.gitlab.widget.WidgetSnapshot
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Tests cached widget state, RemoteViews rendering, and monitor cache feeding (#78). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WidgetTest {

  private lateinit var context: Context
  private lateinit var store: InstanceStore
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    WidgetStore.clear(context)
    PollLog(context).clear()
    context.getSharedPreferences("gitlab_notification_state", Context.MODE_PRIVATE).edit().clear().commit()
    store = InstanceStore.get(context)
    store.resetAll()
    context.getSystemService(NotificationManager::class.java).cancelAll()

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
    WidgetStore.clear(context)
  }

  @Test
  fun `WidgetStore sums multi-account todo counts, retains untouched accounts, and prunes removed accounts`() {
    WidgetStore.saveAccountTodos(
      context,
      activeAccountIds = setOf("a1", "a2"),
      observedCounts = mapOf("a1" to 4, "a2" to 3),
      now = 100L,
    )
    var snap = WidgetStore.read(context)
    assertTrue(snap.hasAccounts)
    assertEquals(7, snap.todoCount)
    assertEquals(100L, snap.updatedAt)

    // Updating only a1 preserves a2's previously cached count.
    WidgetStore.saveAccountTodos(
      context,
      activeAccountIds = setOf("a1", "a2"),
      observedCounts = mapOf("a1" to 2),
      now = 200L,
    )
    snap = WidgetStore.read(context)
    assertEquals(5, snap.todoCount)

    // Negative counts clamp to zero and removed accounts are dropped from the sum.
    WidgetStore.saveAccountTodos(
      context,
      activeAccountIds = setOf("a1"),
      observedCounts = mapOf("a1" to -3),
      now = 300L,
    )
    snap = WidgetStore.read(context)
    assertEquals(0, snap.todoCount)

    // Removing the last signed-in account clears the widget store completely.
    WidgetStore.saveAccountTodos(
      context,
      activeAccountIds = emptySet(),
      observedCounts = emptyMap(),
      now = 400L,
    )
    snap = WidgetStore.read(context)
    assertFalse(snap.hasAccounts)
    assertEquals(0, snap.todoCount)
  }

  @Test
  fun `WidgetStore caps stored pipelines at 3 and preserves metadata`() {
    val pipelines = (1L..5L).map { id ->
      WidgetPipelineSummary(
        id = id,
        projectId = 100 + id,
        projectPath = "group/repo-$id",
        ref = "branch-$id",
        status = if (id % 2L == 0L) "failed" else "success",
      )
    }
    WidgetStore.savePipelines(context, pipelines, now = 999L)

    val snap = WidgetStore.read(context)
    assertEquals(3, snap.pipelines.size)
    assertEquals(listOf(1L, 2L, 3L), snap.pipelines.map { it.id })
    assertEquals("group/repo-2", snap.pipelines[1].projectPath)
    assertEquals("branch-2", snap.pipelines[1].ref)
    assertEquals("failed", snap.pipelines[1].status)
    assertEquals(999L, snap.updatedAt)
  }

  @Test
  fun `buildRemoteViews renders guest, empty, singular, overflow todo count, and pipeline rows`() {
    val guestView = GitLabWidgetProvider.buildRemoteViews(context, WidgetSnapshot(hasAccounts = false))
      .apply(context, FrameLayout(context))
    assertEquals("Guest", guestView.findViewById<TextView>(R.id.widget_todo_count).text.toString())
    assertEquals(View.VISIBLE, guestView.findViewById<View>(R.id.widget_empty).visibility)
    assertEquals(View.GONE, guestView.findViewById<View>(R.id.widget_pipeline_1).visibility)

    val singleTodoEmpty = GitLabWidgetProvider.buildRemoteViews(
      context,
      WidgetSnapshot(hasAccounts = true, todoCount = 1, pipelines = emptyList()),
    ).apply(context, FrameLayout(context))
    assertEquals("1 to-do", singleTodoEmpty.findViewById<TextView>(R.id.widget_todo_count).text.toString())
    assertEquals("No recent pipelines", singleTodoEmpty.findViewById<TextView>(R.id.widget_empty).text.toString())
    assertEquals(View.VISIBLE, singleTodoEmpty.findViewById<View>(R.id.widget_empty).visibility)

    val populated = GitLabWidgetProvider.buildRemoteViews(
      context,
      WidgetSnapshot(
        hasAccounts = true,
        todoCount = 120,
        pipelines = listOf(
          WidgetPipelineSummary(id = 40L, projectId = 10L, projectPath = "team/rocket", ref = "main", status = "success"),
          WidgetPipelineSummary(id = 41L, projectId = 11L, projectPath = "", ref = "", status = "failed"),
        ),
      ),
    ).apply(context, FrameLayout(context))

    assertEquals("99+ to-dos", populated.findViewById<TextView>(R.id.widget_todo_count).text.toString())
    assertEquals(View.GONE, populated.findViewById<View>(R.id.widget_empty).visibility)
    assertEquals(View.VISIBLE, populated.findViewById<View>(R.id.widget_pipeline_1).visibility)
    assertEquals("Passed", populated.findViewById<TextView>(R.id.widget_pipeline_1_status).text.toString())
    assertEquals("rocket · main", populated.findViewById<TextView>(R.id.widget_pipeline_1_label).text.toString())
    assertEquals(View.VISIBLE, populated.findViewById<View>(R.id.widget_pipeline_2).visibility)
    assertEquals("Failed", populated.findViewById<TextView>(R.id.widget_pipeline_2_status).text.toString())
    assertEquals("#41", populated.findViewById<TextView>(R.id.widget_pipeline_2_label).text.toString())
    assertEquals(View.GONE, populated.findViewById<View>(R.id.widget_pipeline_3).visibility)
  }

  @Test
  fun `GitLabWidgetProvider statusLabel maps known and custom statuses`() {
    assertEquals("Passed", GitLabWidgetProvider.statusLabel("success"))
    assertEquals("Failed", GitLabWidgetProvider.statusLabel("failed"))
    assertEquals("Running", GitLabWidgetProvider.statusLabel("running"))
    assertEquals("Pending", GitLabWidgetProvider.statusLabel("pending"))
    assertEquals("Canceled", GitLabWidgetProvider.statusLabel("canceled"))
    assertEquals("Skipped", GitLabWidgetProvider.statusLabel("skipped"))
    assertEquals("Manual", GitLabWidgetProvider.statusLabel("manual"))
    assertEquals("Scheduled", GitLabWidgetProvider.statusLabel("scheduled"))
    assertEquals("Unknown", GitLabWidgetProvider.statusLabel(""))
  }

  @Test
  fun `notification monitor poll populates WidgetStore pending todo count without extra calls`() = runBlocking {
    val account = GitLabInstance(
      id = "acc-widget",
      name = "Work",
      url = server.url("/").toString(),
      token = "glpat-widget",
      username = "dev",
      isActive = true,
      isCustom = true,
      authKind = GitLabAuth.KIND_PAT,
    )
    store.addInstance(account)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val path = url.encodedPath
        return when {
          path.endsWith("/api/v4/todos") && url.queryParameter("state") == "pending" ->
            MockResponse().addHeader("Content-Type", "application/json").setBody(
              """
              [
                {"id":1,"action_name":"assigned","target_type":"Issue","target":{"iid":10,"title":"A","project_id":1},"state":"pending","created_at":"2026-11-15T08:00:00Z","author":{"id":2,"username":"alice","name":"Alice"}},
                {"id":2,"action_name":"mentioned","target_type":"MergeRequest","target":{"iid":11,"title":"B","project_id":1},"state":"pending","created_at":"2026-11-15T08:01:00Z","author":{"id":3,"username":"bob","name":"Bob"}}
              ]
              """.trimIndent(),
            )
          path.endsWith("/api/v4/todos") && url.queryParameter("state") == "done" ->
            MockResponse().addHeader("Content-Type", "application/json").setBody(
              """
              [
                {"id":3,"action_name":"assigned","target_type":"Issue","target":{"iid":12,"title":"Done","project_id":1},"state":"done","created_at":"2026-11-14T08:00:00Z","author":{"id":2,"username":"alice","name":"Alice"}}
              ]
              """.trimIndent(),
            )
          path.endsWith("/api/v4/merge_requests") ->
            MockResponse().addHeader("Content-Type", "application/json").setBody("[]")
          else -> MockResponse().setResponseCode(404)
        }
      }
    }

    val ok = GitLabNotificationMonitor.poll(context)
    assertTrue(ok)
    val snapshot = WidgetStore.read(context)
    assertTrue(snapshot.hasAccounts)
    // Only pending to-dos count toward the widget badge, not the "done" page entries.
    assertEquals(2, snapshot.todoCount)

    // After signing out, the next poll clears cached widget state.
    store.deleteInstance(account.id)
    GitLabNotificationMonitor.poll(context)
    val cleared = WidgetStore.read(context)
    assertFalse(cleared.hasAccounts)
    assertEquals(0, cleared.todoCount)
  }
}
