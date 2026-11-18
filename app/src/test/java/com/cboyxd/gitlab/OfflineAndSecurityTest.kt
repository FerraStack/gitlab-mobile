package com.cboyxd.gitlab

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.data.storage.AppLockPolicy
import com.cboyxd.gitlab.data.storage.AppLockTimeout
import com.cboyxd.gitlab.data.storage.AppPreferencesStore
import com.cboyxd.gitlab.data.storage.DashboardSnapshot
import com.cboyxd.gitlab.data.storage.InstanceStore
import com.cboyxd.gitlab.data.storage.OfflineSnapshotStore
import com.cboyxd.gitlab.ui.viewmodel.BottomTab
import com.cboyxd.gitlab.ui.viewmodel.GitLabNotification
import com.cboyxd.gitlab.ui.viewmodel.GitLabViewModel
import com.cboyxd.gitlab.ui.viewmodel.NavigationStateCodec
import com.cboyxd.gitlab.ui.viewmodel.NotificationType
import com.cboyxd.gitlab.ui.viewmodel.PersistedNavState
import com.cboyxd.gitlab.ui.viewmodel.ScreenDestination
import com.cboyxd.gitlab.ui.viewmodel.UiMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OfflineAndSecurityTest {

  private lateinit var app: GitLabApplication
  private lateinit var server: MockWebServer
  private lateinit var cacheDir: File
  private lateinit var instance: GitLabInstance
  private val repository = GitLabRepository()

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    cacheDir = File(app.cacheDir, "test_http_cache_${System.nanoTime()}").apply { mkdirs() }
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
    instance = GitLabInstance(
      id = "acc-${System.nanoTime()}",
      name = "Test",
      url = server.url("/").toString(),
      token = "glpat-test",
      authKind = GitLabAuth.KIND_PAT,
    )
  }

  @After
  fun tearDown() {
    GitLabApiClient.clientOverride = null
    GitLabApiClient.configureCache(app.cacheDir.resolve("http_cache")) { app.online.value }
    cacheDir.deleteRecursively()
    runCatching { server.shutdown() }
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
  fun `offline snapshot store persists dashboard, recent items and deduplicates`() {
    val dir = File(app.filesDir, "snap_test_${System.nanoTime()}")
    try {
      val store = OfflineSnapshotStore(dir)
      val notification = GitLabNotification(
        id = "n1",
        type = NotificationType.ISSUE,
        title = "#7 Launch bug",
        projectPath = "example-org/rocket",
        authorName = "Tanuki",
        authorUsername = "tanuki",
        authorAvatarUrl = null,
        timestamp = "1h ago",
        isUnread = true,
        actionName = "assigned",
        targetType = "Issue",
        targetProjectId = 1L,
        targetIid = 7L,
        targetUrl = "https://gitlab.com/example-org/rocket/-/issues/7",
      )
      val snap = DashboardSnapshot(
        savedAtMs = 123456L,
        currentUser = Fakes.user,
        projects = Fakes.projects,
        issues = Fakes.issues,
        mergeRequests = Fakes.mergeRequests,
        notifications = listOf(notification),
      )
      store.write("acc-1", snap)

      val read = store.read("acc-1")
      assertNotNull(read)
      assertEquals(123456L, read!!.savedAtMs)
      assertEquals(Fakes.user.username, read.currentUser?.username)
      assertEquals(3, read.projects.size)
      assertEquals(1, read.notifications.size)

      store.recordRecentIssue("acc-1", Fakes.issue(7, "First"))
      store.recordRecentIssue("acc-1", Fakes.issue(6, "Second"))
      store.recordRecentIssue("acc-1", Fakes.issue(7, "Updated first"), maxItems = 2)
      val recent = store.read("acc-1")!!.recentIssues
      assertEquals(listOf(7L, 6L), recent.map { it.iid })
      assertEquals("Updated first", recent.first().title)

      store.recordRecentMergeRequest("acc-1", Fakes.mergeRequest(10, "Old"), maxItems = 1)
      store.recordRecentMergeRequest("acc-1", Fakes.mergeRequest(12, "New"), maxItems = 1)
      val mrs = store.read("acc-1")!!.recentMergeRequests
      assertEquals(listOf(12L), mrs.map { it.iid })

      store.delete("acc-1")
      assertNull(store.read("acc-1"))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `http disk cache serves cached response offline and on 5xx errors`() = runBlocking {
    val online = AtomicBoolean(true)
    GitLabApiClient.configureCache(cacheDir) { online.get() }

    server.enqueue(
      MockResponse()
        .addHeader("Content-Type", "application/json")
        .addHeader("Cache-Control", "no-store")
        .setBody("""{"id":1,"name":"Rocket","path_with_namespace":"org/rocket"}""")
    )
    server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
    server.enqueue(
      MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("""{"id":1,"name":"Rocket v2","path_with_namespace":"org/rocket"}""")
    )

    val first = repository.getProject(instance, 1L).getOrThrow()
    assertEquals("Rocket", first.name)

    // Online + 503 -> stale-if-error returns cached 200.
    val onError = repository.getProject(instance, 1L).getOrThrow()
    assertEquals("Rocket", onError.name)

    // Online + 200 -> fetches fresh server response rather than pinning stale data.
    val refreshed = repository.getProject(instance, 1L).getOrThrow()
    assertEquals("Rocket v2", refreshed.name)

    // Offline -> returns cached response immediately without calling the server.
    online.set(false)
    val countBeforeOffline = server.requestCount
    val offlineResult = repository.getProject(instance, 1L).getOrThrow()
    assertEquals("Rocket v2", offlineResult.name)
    assertEquals(countBeforeOffline, server.requestCount)
  }

  @Test
  fun `http disk cache falls back to cached response when network throws IOException`() = runBlocking {
    GitLabApiClient.configureCache(cacheDir) { true }

    server.enqueue(
      MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("""{"id":1,"name":"Rocket","path_with_namespace":"org/rocket"}""")
    )
    val first = repository.getProject(instance, 1L).getOrThrow()
    assertEquals("Rocket", first.name)

    server.shutdown()

    val cached = repository.getProject(instance, 1L).getOrThrow()
    assertEquals("Rocket", cached.name)
  }

  @Test
  fun `app lock policy enforces initial launch and timeout intervals`() {
    assertFalse(AppLockPolicy.shouldLock(enabled = false, backgroundedAtMs = 1_000L, nowMs = 999_000L, timeout = AppLockTimeout.IMMEDIATE, isInitialLaunch = true))
    assertTrue(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 0L, nowMs = 1_000L, timeout = AppLockTimeout.FIFTEEN_MINUTES, isInitialLaunch = true))
    assertFalse(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 0L, nowMs = 5_000L, timeout = AppLockTimeout.IMMEDIATE, isInitialLaunch = false))

    assertTrue(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 1_000L, nowMs = 1_000L, timeout = AppLockTimeout.IMMEDIATE, isInitialLaunch = false))
    assertFalse(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 1_000L, nowMs = 60_999L, timeout = AppLockTimeout.ONE_MINUTE, isInitialLaunch = false))
    assertTrue(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 1_000L, nowMs = 61_000L, timeout = AppLockTimeout.ONE_MINUTE, isInitialLaunch = false))
    assertFalse(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 1_000L, nowMs = 300_999L, timeout = AppLockTimeout.FIVE_MINUTES, isInitialLaunch = false))
    assertTrue(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 1_000L, nowMs = 301_000L, timeout = AppLockTimeout.FIVE_MINUTES, isInitialLaunch = false))
    assertFalse(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 1_000L, nowMs = 900_999L, timeout = AppLockTimeout.FIFTEEN_MINUTES, isInitialLaunch = false))
    assertTrue(AppLockPolicy.shouldLock(enabled = true, backgroundedAtMs = 1_000L, nowMs = 901_000L, timeout = AppLockTimeout.FIFTEEN_MINUTES, isInitialLaunch = false))
  }

  @Test
  fun `app preferences store persists lock and screen security settings`() {
    val first = AppPreferencesStore(app)
    first.setAppLockEnabled(true)
    first.setLockTimeout(AppLockTimeout.FIVE_MINUTES)
    first.setScreenSecurityEnabled(true)

    val second = AppPreferencesStore(app)
    assertTrue(second.preferences.value.appLockEnabled)
    assertEquals(AppLockTimeout.FIVE_MINUTES, second.preferences.value.lockTimeout)
    assertTrue(second.preferences.value.screenSecurityEnabled)

    second.setAppLockEnabled(false)
    second.setScreenSecurityEnabled(false)
  }

  @Test
  fun `viewmodel restores destination after process death, emits retry on error, and refreshes on reconnect`() {
    val projectCalls = AtomicInteger(0)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
          path.startsWith("/api/v4/user_status") -> MockResponse().setBody("{}")
          path.startsWith("/api/v4/user") -> MockResponse().setBody("""{"id":1,"username":"dev","name":"Dev"}""")
          path.startsWith("/api/v4/version") -> MockResponse().setBody("""{"version":"17.8.0"}""")
          request.requestUrl?.encodedPath == "/api/v4/projects/12" -> {
            projectCalls.incrementAndGet()
            MockResponse().setBody("""{"id":12,"name":"rocket","name_with_namespace":"Org / rocket","path_with_namespace":"org/rocket","default_branch":"main","web_url":"https://gitlab.com/org/rocket"}""")
          }
          path.startsWith("/api/v4/projects/12") -> MockResponse().setBody("[]")
          else -> MockResponse().setBody("[]")
        }
      }
    }

    val store = InstanceStore.get(app)
    store.resetAll()
    val saved = store.addInstance(instance.copy(isActive = true, username = "dev"))

    val navJson = NavigationStateCodec.encode(
      PersistedNavState(
        instanceId = saved.id,
        tab = BottomTab.PROJECTS,
        backStack = listOf(ScreenDestination.ProjectDetail(12L)),
      )
    )
    val handle = SavedStateHandle(mapOf(GitLabViewModel.KEY_NAV_STATE to navJson))

    val vm = GitLabViewModel(app, handle)
    waitUntil { vm.uiState.value.bootstrapped && projectCalls.get() >= 1 }

    assertEquals(BottomTab.PROJECTS, vm.uiState.value.currentTab)
    assertEquals(ScreenDestination.ProjectDetail(12L), vm.uiState.value.destination)

    // Going offline and coming back online refreshes the active screen automatically.
    val callsBeforeReconnect = projectCalls.get()
    vm.onConnectivityChanged(false)
    assertTrue(vm.uiState.value.isOffline)
    vm.onConnectivityChanged(true)
    assertFalse(vm.uiState.value.isOffline)
    waitUntil { projectCalls.get() > callsBeforeReconnect }

    // Failed action emits UiMessage with Retry action that re-runs the failed action.
    val retryLatch = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.method == "POST" && request.path.orEmpty().contains("/star")) {
          return MockResponse().setResponseCode(500).setBody("""{"message":"Server error"}""")
        }
        return MockResponse().setBody("{}")
      }
    }

    var capturedMessage: UiMessage? = null
    val msgLatch = CountDownLatch(1)
    val collectorJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
      capturedMessage = vm.messages.first { it.isError }
      msgLatch.countDown()
    }
    vm.toggleProjectStar(12L)
    waitUntil { msgLatch.count == 0L }
    collectorJob.cancel()
    assertNotNull(capturedMessage)
    assertEquals("Retry", capturedMessage!!.actionLabel)
    assertNotNull(capturedMessage!!.action)

    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.method == "POST" && request.path.orEmpty().contains("/star")) {
          retryLatch.countDown()
          return MockResponse().setBody("""{"id":12,"name":"rocket","name_with_namespace":"Org / rocket","path_with_namespace":"org/rocket","star_count":1}""")
        }
        return MockResponse().setBody("{}")
      }
    }
    capturedMessage!!.action!!.invoke()
    assertTrue(retryLatch.await(5, TimeUnit.SECONDS))

    store.resetAll()
  }
}
