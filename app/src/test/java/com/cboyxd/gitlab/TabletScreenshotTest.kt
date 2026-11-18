package com.cboyxd.gitlab

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import com.cboyxd.gitlab.ui.GlMaxContentWidth
import com.cboyxd.gitlab.ui.GlMaxTwoPaneWidth
import com.cboyxd.gitlab.ui.MainScreen
import com.cboyxd.gitlab.ui.screens.HomeScreen
import com.cboyxd.gitlab.ui.screens.IssueDetailScreen
import com.cboyxd.gitlab.ui.screens.IssuesScreen
import com.cboyxd.gitlab.ui.theme.GitLabTheme
import com.cboyxd.gitlab.ui.theme.Gl
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.cboyxd.gitlab.ui.viewmodel.GitLabViewModel
import com.cboyxd.gitlab.widget.WidgetStore
import com.cboyxd.gitlab.ui.viewmodel.Loadable
import com.cboyxd.gitlab.ui.viewmodel.ScreenDestination
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1024dp-h800dp-xhdpi", sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TabletScreenshotTest {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var app: GitLabApplication
  private lateinit var server: MockWebServer
  private lateinit var viewModelStore: ViewModelStore
  private var testClient: OkHttpClient? = null

  @Before
  fun setUp() {
    InstanceStore.resetForTests()
    app = ApplicationProvider.getApplicationContext()
    WidgetStore.clear(app)
    InstanceStore.get(app).resetAll()
    viewModelStore = ViewModelStore()
    val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certs = HandshakeCertificates.Builder()
      .heldCertificate(cert)
      .addTrustedCertificate(cert.certificate)
      .build()
    server = MockWebServer()
    server.useHttps(certs.sslSocketFactory(), false)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        val body = when {
          path == "/api/v4/user" -> """{"id":1,"username":"tanuki","name":"Tanuki Tester"}"""
          path == "/api/v4/user_status" || path == "/api/v4/user/status" -> "{}"
          path == "/api/v4/version" -> """{"version":"17.8.0"}"""
          path == "/api/v4/projects" -> """[{"id":1,"name":"rocket","name_with_namespace":"Example Org / rocket","path_with_namespace":"example-org/rocket","default_branch":"main","visibility":"public","web_url":"https://gitlab.com/example-org/rocket"}]"""
          path == "/api/v4/issues" -> """[{"id":107,"iid":7,"project_id":1,"title":"Launch button does nothing on tablets","description":"Steps to reproduce","state":"opened","created_at":"2026-11-10T10:00:00Z","updated_at":"2026-11-10T10:00:00Z","author":{"id":1,"username":"tanuki","name":"Tanuki Tester"},"labels":["bug"],"user_notes_count":1,"web_url":"https://gitlab.com/example-org/rocket/-/issues/7","references":{"short":"#7","full":"example-org/rocket#7"}}]"""
          path == "/api/v4/projects/1/issues/7" -> """{"id":107,"iid":7,"project_id":1,"title":"Launch button does nothing on tablets","description":"Steps to reproduce","state":"opened","created_at":"2026-11-10T10:00:00Z","updated_at":"2026-11-10T10:00:00Z","author":{"id":1,"username":"tanuki","name":"Tanuki Tester"},"labels":["bug"],"user_notes_count":1,"web_url":"https://gitlab.com/example-org/rocket/-/issues/7","references":{"short":"#7","full":"example-org/rocket#7"}}"""
          path == "/api/v4/projects/1/issues/7/notes" -> """[{"id":1,"body":"Confirmed in two-pane view.","author":{"id":1,"username":"tanuki","name":"Tanuki Tester"},"created_at":"2026-11-10T11:00:00Z","system":false}]"""
          else -> "[]"
        }
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body)
      }
    }
    server.start()
    GitLabApiClient.resetCacheForTest()
    GitLabApiClient.clearCache()
    testClient = OkHttpClient.Builder()
      .sslSocketFactory(certs.sslSocketFactory(), certs.trustManager)
      .build()
    GitLabApiClient.clientOverride = testClient
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    GitLabApiClient.clientOverride = null
    GitLabApiClient.resetCacheForTest()
    GitLabApiClient.clearCache()
    testClient?.dispatcher?.executorService?.shutdownNow()
    testClient?.connectionPool?.evictAll()
    testClient = null
    server.shutdown()
    InstanceStore.get(app).resetAll()
    InstanceStore.resetForTests()
    WidgetStore.clear(app)
  }

  private fun createViewModel(): GitLabViewModel =
    ViewModelProvider(
      viewModelStore,
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GitLabViewModel(app) as T
      },
    )[GitLabViewModel::class.java]

  private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
    rule.setContent { GitLabTheme(darkTheme = dark, reducedMotion = true, content = content) }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(filePath = "src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png")
  }

  @Test
  fun tablet_uses_navigation_rail_and_two_pane_inbox() {
    InstanceStore.get(app).addInstance(
      GitLabInstance(
        name = "Local",
        url = server.url("/").toString(),
        token = "glpat-test",
        username = "tanuki",
        isActive = true,
        authKind = GitLabAuth.KIND_PAT,
      )
    )
    val vm = createViewModel()
    rule.setContent {
      GitLabTheme(darkTheme = false, reducedMotion = true) {
        MainScreen(viewModel = vm, onExit = {})
      }
    }
    rule.waitUntil(timeoutMillis = 15_000) { vm.uiState.value.bootstrapped }
    rule.onNodeWithTag("main_nav_rail").assertIsDisplayed()
    rule.onNodeWithTag("main_bottom_nav").assertDoesNotExist()

    rule.onNodeWithTag("nav_item_inbox").performClick()
    rule.waitForIdle()
    rule.onNodeWithTag("two_pane_detail").assertIsDisplayed()
    rule.onNodeWithText("Select a to-do").assertIsDisplayed()
  }

  @Test
  fun tablet_two_pane_issues_loads_selected_issue_inline() {
    InstanceStore.get(app).addInstance(
      GitLabInstance(
        name = "Local",
        url = server.url("/").toString(),
        token = "glpat-test",
        username = "tanuki",
        isActive = true,
        authKind = GitLabAuth.KIND_PAT,
      )
    )
    val vm = createViewModel()
    rule.setContent {
      GitLabTheme(darkTheme = true, reducedMotion = true) {
        MainScreen(viewModel = vm, onExit = {})
      }
    }
    rule.waitUntil(timeoutMillis = 15_000) {
      val s = vm.uiState.value
      if (s.bootstrapped && !s.issues.loading && s.issues.error != null) {
        throw AssertionError("Issues failed to load: ${s.issues.error}")
      }
      s.bootstrapped && s.issues.data.isNotEmpty()
    }
    rule.runOnUiThread { vm.navigateTo(ScreenDestination.IssuesList) }
    rule.waitUntil(timeoutMillis = 15_000) {
      rule.onAllNodesWithText("Launch button does nothing on tablets").fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithText("Select an issue").assertIsDisplayed()

    rule.onNodeWithText("Launch button does nothing on tablets").performClick()
    rule.waitUntil(timeoutMillis = 15_000) {
      rule.onAllNodesWithText("Confirmed in two-pane view.").fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNode(hasTestTag("two_pane_detail").and(hasAnyDescendant(hasText("Confirmed in two-pane view."))))
      .assertIsDisplayed()
    assertEquals(ScreenDestination.IssuesList, vm.uiState.value.destination)
  }

  @Composable
  private fun TabletHomeShell() {
    val c = Gl.colors
    Row(Modifier.fillMaxSize().background(c.bg)) {
      NavigationRail(
        containerColor = c.surface,
        windowInsets = WindowInsets(0, 0, 0, 0),
      ) {
        val railColors = NavigationRailItemDefaults.colors(
          selectedIconColor = c.accent,
          selectedTextColor = c.textPrimary,
          unselectedIconColor = c.textSecondary,
          unselectedTextColor = c.textPrimary,
          indicatorColor = c.accentBg,
        )
        NavigationRailItem(
          selected = true,
          onClick = {},
          icon = { Icon(Icons.Filled.Home, contentDescription = null) },
          label = { Text("Home", style = Gl.type.badge) },
          colors = railColors,
        )
        NavigationRailItem(
          selected = false,
          onClick = {},
          icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
          label = { Text("Projects", style = Gl.type.badge) },
          colors = railColors,
        )
        NavigationRailItem(
          selected = false,
          onClick = {},
          icon = { Icon(Icons.Outlined.Inbox, contentDescription = null) },
          label = { Text("Inbox", style = Gl.type.badge) },
          colors = railColors,
        )
      }
      Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
      Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = GlMaxContentWidth).fillMaxWidth()) {
          HomeScreen(
            state = Fakes.signedInState(),
            onRefresh = {},
            onOpenProfile = {},
            onOpenSearch = {},
            onAddServer = {},
            onSignInAgain = {},
            onNavigate = {},
            onSwitchTab = {},
            onToggleStar = {},
          )
        }
      }
    }
  }

  @Composable
  private fun TabletTwoPaneIssues() {
    val c = Gl.colors
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
      Row(Modifier.fillMaxHeight().widthIn(max = GlMaxTwoPaneWidth).fillMaxWidth()) {
        IssuesScreen(
          issues = Loadable(Fakes.issues, loaded = true),
          projects = Fakes.projects,
          onSelectIssue = {},
          onCreateIssue = { _, _, _, _, _ -> },
          onRetry = {},
          onBack = {},
          modifier = Modifier.weight(0.42f).fillMaxHeight(),
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        IssueDetailScreen(
          state = Fakes.issueDetail(),
          labels = Fakes.labels,
          canWrite = true,
          onLink = { false },
          onRetry = {},
          onComment = { _, _ -> },
          onSetClosed = {},
          onEdit = { _, _, _ -> },
          onOpenUser = {},
          onOpenInBrowser = {},
          onBack = {},
          showBack = false,
          modifier = Modifier.weight(0.58f).fillMaxHeight(),
        )
      }
    }
  }

  @Test fun tablet_home_light() = capture("tablet_home", dark = false) { TabletHomeShell() }
  @Test fun tablet_home_dark() = capture("tablet_home", dark = true) { TabletHomeShell() }
  @Test fun tablet_two_pane_issues_light() = capture("tablet_two_pane_issues", dark = false) { TabletTwoPaneIssues() }
  @Test fun tablet_two_pane_issues_dark() = capture("tablet_two_pane_issues", dark = true) { TabletTwoPaneIssues() }
}
