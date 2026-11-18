package com.cboyxd.gitlab.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import com.cboyxd.gitlab.widget.WidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Unit tests for [GitLabViewModel] navigation, session isolation, optimistic to-do actions, and widget actions (#62). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class GitLabViewModelTest {

  private lateinit var app: Application
  private lateinit var store: InstanceStore
  private lateinit var server: MockWebServer
  private lateinit var account1: GitLabInstance
  private lateinit var account2: GitLabInstance

  private val failTodoActions = AtomicBoolean(false)
  private val todosFetchCount = AtomicInteger(0)
  @Volatile private var issueGate: CountDownLatch? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    app = ApplicationProvider.getApplicationContext()
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
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val path = url.encodedPath
        val auth = request.getHeader("PRIVATE-TOKEN") ?: request.getHeader("Authorization").orEmpty()
        return when {
          path.endsWith("/api/v4/user") -> json("""{"id":1,"username":"dev","name":"Dev User"}""")
          path.endsWith("/api/v4/user/status") -> json("""{"message":"Focused"}""")
          path.endsWith("/api/v4/projects") -> {
            if (auth.contains("token-2")) {
              json("""[{"id":20,"name":"beta","path_with_namespace":"org/beta","default_branch":"main","web_url":"https://example/org/beta"}]""")
            } else {
              json("""[{"id":1,"name":"alpha","path_with_namespace":"org/alpha","default_branch":"main","web_url":"https://example/org/alpha"}]""")
            }
          }
          path.endsWith("/api/v4/projects/1") ->
            json("""{"id":1,"name":"alpha","path_with_namespace":"org/alpha","default_branch":"main","web_url":"https://example/org/alpha"}""")
          path.endsWith("/api/v4/projects/1/repository/tree") -> {
            val treePath = url.queryParameter("path")
            if (treePath == "src/main") {
              json("""[{"id":"f1","name":"App.kt","type":"blob","path":"src/main/App.kt"}]""")
            } else {
              json("""[{"id":"d1","name":"main","type":"tree","path":"src/main"}]""")
            }
          }
          path.endsWith("/api/v4/projects/1/issues/56") -> {
            issueGate?.await(5, TimeUnit.SECONDS)
            json("""{"id":560,"iid":56,"project_id":1,"title":"Blocked issue","state":"opened"}""")
          }
          path.endsWith("/api/v4/projects/1/issues/55") ->
            json("""{"id":550,"iid":55,"project_id":1,"title":"Ready issue","state":"opened"}""")
          path.endsWith("/api/v4/projects/1/issues/55/notes") || path.endsWith("/api/v4/projects/1/issues/56/notes") ->
            json("[]")
          path.endsWith("/api/v4/projects/1/pipelines") -> json("[]")
          path.endsWith("/api/v4/projects/1/pipelines/99") ->
            json("""{"id":99,"project_id":1,"status":"success","ref":"main"}""")
          path.endsWith("/api/v4/projects/1/pipelines/99/jobs") -> json("[]")
          path.endsWith("/api/v4/projects/1/labels") ||
            path.endsWith("/api/v4/projects/1/releases") ||
            path.endsWith("/api/v4/projects/1/languages") -> json("[]")
          path.endsWith("/api/v4/projects/1/issues_statistics") ->
            json("""{"statistics":{"counts":{"all":1,"opened":1,"closed":0}}}""")
          path.endsWith("/api/v4/projects/1/merge_requests") -> json("[]").addHeader("X-Total", "0")
          path.endsWith("/api/v4/groups") ||
            path.endsWith("/api/v4/issues") ||
            path.endsWith("/api/v4/merge_requests") ||
            path.endsWith("/api/v4/events") -> json("[]")
          path.endsWith("/api/v4/todos") -> {
            todosFetchCount.incrementAndGet()
            if (url.queryParameter("state") == "pending") {
              json(
                """
                [
                  {"id":101,"action_name":"assigned","target_type":"Issue","target":{"iid":55,"title":"First todo","project_id":1},"state":"pending","created_at":"2026-11-15T08:00:00Z","author":{"id":2,"username":"alice","name":"Alice"}},
                  {"id":102,"action_name":"mentioned","target_type":"MergeRequest","target":{"iid":12,"title":"Second todo","project_id":1},"state":"pending","created_at":"2026-11-15T08:01:00Z","author":{"id":3,"username":"bob","name":"Bob"}}
                ]
                """.trimIndent(),
              )
            } else {
              json("[]")
            }
          }
          path.endsWith("/api/v4/todos/101/mark_as_done") || path.endsWith("/api/v4/todos/mark_as_done") -> {
            if (failTodoActions.get()) MockResponse().setResponseCode(500).setBody("""{"message":"boom"}""")
            else MockResponse().setResponseCode(200).setBody("{}")
          }
          path.endsWith("/api/v4/version") -> json("""{"version":"17.5.0"}""")
          else -> MockResponse().setResponseCode(404)
        }
      }
    }
    server.start()
    GitLabApiClient.clearCache()
    GitLabApiClient.clientOverride = OkHttpClient.Builder()
      .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
      .build()

    account1 = store.addInstance(
      GitLabInstance(
        id = "acc-1",
        name = "Primary",
        url = server.url("/").toString(),
        token = "token-1",
        username = "dev",
        isActive = true,
        isCustom = true,
        authKind = GitLabAuth.KIND_PAT,
      ),
    )
    account2 = store.addInstance(
      GitLabInstance(
        id = "acc-2",
        name = "Secondary",
        url = server.url("/").toString(),
        token = "token-2",
        username = "dev2",
        isActive = false,
        isCustom = true,
        authKind = GitLabAuth.KIND_PAT,
      ),
    )
  }

  @After
  fun tearDown() {
    issueGate?.countDown()
    Dispatchers.resetMain()
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    server.shutdown()
    store.resetAll()
    WidgetStore.clear(app)
  }

  private fun json(body: String) = MockResponse().addHeader("Content-Type", "application/json").setBody(body)

  private fun awaitUntil(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!predicate() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
    assertTrue("Timed out waiting for condition", predicate())
  }

  private fun createBootstrappedViewModel(): GitLabViewModel {
    val vm = GitLabViewModel(app)
    vm.todoUndoDelayMs = 0L
    awaitUntil {
      val s = vm.uiState.value
      s.bootstrapped && !s.refreshing && s.notifications.loaded && s.projects.loaded
    }
    return vm
  }

  @Test
  fun `navigation gates destination on readiness and navigateBack cancels pending and steps up code tree`() {
    val vm = createBootstrappedViewModel()

    // Navigate to ready issue #55
    vm.navigateTo(ScreenDestination.IssueDetail(1, 55))
    awaitUntil { vm.uiState.value.destination == ScreenDestination.IssueDetail(1, 55) }
    assertNull(vm.uiState.value.pendingDestination)

    // Hold issue #56 response so pendingDestination stays active, then cancel with navigateBack().
    val gate = CountDownLatch(1)
    issueGate = gate
    vm.navigateTo(ScreenDestination.IssueDetail(1, 56))
    assertEquals(ScreenDestination.IssueDetail(1, 56), vm.uiState.value.pendingDestination)
    assertEquals(ScreenDestination.IssueDetail(1, 55), vm.uiState.value.destination)

    assertTrue(vm.navigateBack())
    assertNull(vm.uiState.value.pendingDestination)
    assertEquals(ScreenDestination.IssueDetail(1, 55), vm.uiState.value.destination)
    gate.countDown()
    issueGate = null

    // navigateBack pops back to MainTabs, then returns false at root.
    assertTrue(vm.navigateBack())
    awaitUntil { vm.uiState.value.destination == ScreenDestination.MainTabs }
    assertFalse(vm.navigateBack())

    // ProjectCode backs up through folders before popping destination.
    vm.navigateTo(ScreenDestination.ProjectDetail(1))
    awaitUntil { vm.uiState.value.destination == ScreenDestination.ProjectDetail(1) }
    vm.code.openCode()
    awaitUntil { vm.uiState.value.destination == ScreenDestination.ProjectCode(1) }
    vm.code.openPath("src/main")
    awaitUntil { vm.uiState.value.project.treePath == "src/main" && !vm.uiState.value.project.tree.loading }

    assertTrue(vm.navigateBack())
    assertEquals(ScreenDestination.ProjectCode(1), vm.uiState.value.destination)
    assertEquals("src", vm.uiState.value.project.treePath)
  }

  @Test
  fun `switchInstance resets navigation, clears previous session data, and loads new server`() {
    val vm = createBootstrappedViewModel()
    vm.navigateTo(ScreenDestination.Search())
    vm.switchTab(BottomTab.PROJECTS)
    assertEquals(1L, vm.uiState.value.projects.data.single().id)

    vm.switchInstance(account2.id)
    awaitUntil {
      val s = vm.uiState.value
      s.activeInstance?.id == account2.id && s.projects.loaded && s.projects.data.firstOrNull()?.id == 20L
    }

    val s = vm.uiState.value
    assertEquals(BottomTab.HOME, s.currentTab)
    assertTrue(s.backStack.isEmpty())
    assertEquals(20L, s.projects.data.single().id)
    val msg = runBlocking { kotlinx.coroutines.withTimeout(5_000L) { vm.messages.first() } }
    assertTrue(msg.text.contains("Secondary"))
  }

  @Test
  fun `markNotificationDone and markAllNotificationsDone update optimistically and roll back on error`() {
    val vm = createBootstrappedViewModel()
    assertEquals(2, vm.uiState.value.unreadCount)
    assertEquals(2, WidgetStore.read(app).todoCount)

    // Failure on single mark-done updates optimistically, emits Undo snackbar, then rolls back on HTTP 500.
    failTodoActions.set(true)
    vm.markNotificationDone("101")
    val undoMsg = runBlocking { kotlinx.coroutines.withTimeout(5_000L) { vm.messages.first() } }
    assertFalse(undoMsg.isError)
    assertEquals("Marked as done", undoMsg.text)
    val errorMsg = runBlocking { kotlinx.coroutines.withTimeout(5_000L) { vm.messages.first { it.isError } } }
    assertTrue(errorMsg.isError)
    assertTrue(vm.uiState.value.notifications.data.first { it.id == "101" }.isUnread)
    assertEquals(2, vm.uiState.value.unreadCount)
    assertEquals(2, WidgetStore.read(app).todoCount)

    // Success on mark-all clears unread count and syncs widget to 0.
    failTodoActions.set(false)
    vm.markAllNotificationsDone()
    awaitUntil { vm.uiState.value.unreadCount == 0 && WidgetStore.read(app).todoCount == 0 }
    val doneMsg = runBlocking { kotlinx.coroutines.withTimeout(5_000L) { vm.messages.first() } }
    assertFalse(doneMsg.isError)
    assertEquals("All to-dos marked as done", doneMsg.text)
  }

  @Test
  fun `markAllNotificationsDone rolls back state and widget count on HTTP 500`() {
    val vm = createBootstrappedViewModel()
    assertEquals(2, vm.uiState.value.unreadCount)

    failTodoActions.set(true)
    vm.markAllNotificationsDone()

    val undoMsg = runBlocking { kotlinx.coroutines.withTimeout(5_000L) { vm.messages.first() } }
    assertFalse(undoMsg.isError)
    assertEquals("All to-dos marked as done", undoMsg.text)
    val errorMsg = runBlocking { kotlinx.coroutines.withTimeout(5_000L) { vm.messages.first { it.isError } } }
    assertTrue(errorMsg.isError)
    assertEquals(2, vm.uiState.value.unreadCount)
    assertEquals(2, WidgetStore.read(app).todoCount)
  }

  @Test
  fun `widget shortcuts navigate to Inbox and PipelineDetail when signed in`() {
    val vm = createBootstrappedViewModel()
    vm.openWidgetInbox()
    awaitUntil { vm.uiState.value.currentTab == BottomTab.INBOX && vm.uiState.value.destination == ScreenDestination.MainTabs }

    vm.openWidgetPipeline(1, 99)
    awaitUntil { vm.uiState.value.destination == ScreenDestination.PipelineDetail(1, 99) }

    // Signed-out pipeline shortcut falls back to Home tab.
    store.deleteInstance(account1.id)
    store.deleteInstance(account2.id)
    val guestVm = GitLabViewModel(app)
    awaitUntil { guestVm.uiState.value.bootstrapped }
    guestVm.switchTab(BottomTab.PROJECTS)
    guestVm.openWidgetPipeline(1, 99)
    awaitUntil { guestVm.uiState.value.currentTab == BottomTab.HOME }
    assertEquals(ScreenDestination.MainTabs, guestVm.uiState.value.destination)
  }
}
