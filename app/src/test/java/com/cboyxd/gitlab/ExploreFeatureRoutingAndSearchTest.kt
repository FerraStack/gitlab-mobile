package com.cboyxd.gitlab

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.ui.viewmodel.ExploreFeature
import com.cboyxd.gitlab.ui.viewmodel.GitLabUiState
import com.cboyxd.gitlab.ui.viewmodel.ProjectSection
import com.cboyxd.gitlab.ui.viewmodel.ScreenDestination
import com.cboyxd.gitlab.ui.viewmodel.SearchScope
import com.cboyxd.gitlab.ui.viewmodel.SessionHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExploreFeatureRoutingAndSearchTest {

  private lateinit var server: MockWebServer
  private lateinit var instance: GitLabInstance
  private lateinit var scope: CoroutineScope
  private lateinit var host: FakeHost
  private lateinit var explore: ExploreFeature
  private val repository = GitLabRepository()

  private class FakeHost(
    override val repository: GitLabRepository,
    override val session: CoroutineScope,
    private val instance: GitLabInstance,
  ) : SessionHost {
    override val state = MutableStateFlow(GitLabUiState(bootstrapped = true, activeInstance = instance))
    override val foreground: StateFlow<Boolean> = MutableStateFlow(true)
    val navigated = CopyOnWriteArrayList<ScreenDestination>()
    val external = CopyOnWriteArrayList<String>()
    override suspend fun <T> api(block: suspend (GitLabInstance) -> Result<T>): Result<T> = block(instance)
    override fun notify(text: String) = Unit
    override fun notifyError(text: String) = Unit
    override fun navigateTo(destination: ScreenDestination) {
      navigated += destination
    }
    override fun openExternal(url: String) {
      external += url
    }
  }

  @Before
  fun setUp() {
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
      id = "test-" + System.nanoTime(),
      name = "Test",
      url = server.url("/").toString(),
      token = "glpat-test",
      authKind = GitLabAuth.KIND_PAT,
    )
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    host = FakeHost(repository, scope, instance)
    explore = ExploreFeature(host)
  }

  @After
  fun tearDown() {
    scope.cancel()
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    server.shutdown()
  }

  private fun baseUrl(): String = instance.url.trimEnd('/')

  private fun json(body: String) = MockResponse()
    .setResponseCode(200)
    .addHeader("Content-Type", "application/json")
    .setBody(body)

  private suspend fun awaitNavigated(expectedCount: Int = 1): ScreenDestination {
    withTimeout(5_000) {
      while (host.navigated.size < expectedCount) delay(15)
    }
    return host.navigated.last()
  }

  @Test
  fun `single-segment group url resolves via namespaces endpoint instead of failing as user`() = runBlocking {
    server.enqueue(json("""{"id":91,"name":"Rocket Group","path":"rocket-group","full_path":"rocket-group","kind":"group"}"""))

    assertTrue(explore.handleLink("${baseUrl()}/rocket-group"))

    val dest = awaitNavigated()
    assertTrue("Expected GroupProjects, got $dest", dest is ScreenDestination.GroupProjects)
    val group = (dest as ScreenDestination.GroupProjects).group
    assertEquals(91L, group.id)
    assertEquals("rocket-group", group.fullPath)
    assertEquals("/api/v4/namespaces/rocket-group", server.takeRequest().path)
    assertTrue(host.external.isEmpty())
  }

  @Test
  fun `single-segment user url resolves via namespaces endpoint to UserDetail`() = runBlocking {
    server.enqueue(json("""{"id":14,"name":"Alice","path":"alice","full_path":"alice","kind":"user"}"""))

    assertTrue(explore.handleLink("${baseUrl()}/alice"))

    val dest = awaitNavigated()
    assertEquals(ScreenDestination.UserDetail(0L, "alice"), dest)
  }

  @Test
  fun `tree, snippet and pipelines links route to their in-app destinations`() = runBlocking {
    server.enqueue(json("""{"id":7,"name":"app","path_with_namespace":"org/app"}"""))

    assertTrue(explore.handleLink("${baseUrl()}/-/snippets/42"))
    assertEquals(ScreenDestination.FileViewer(0L, "snippet-42", snippetId = 42L), awaitNavigated(1))

    assertTrue(explore.handleLink("${baseUrl()}/org/app/-/tree/main/src/ui"))
    assertEquals(ScreenDestination.ProjectCode(7L, ref = "main", path = "src/ui"), awaitNavigated(2))

    assertTrue(explore.handleLink("${baseUrl()}/org/app/-/pipelines"))
    assertEquals(ScreenDestination.ProjectSectionList(7L, ProjectSection.PIPELINES), awaitNavigated(3))

    assertTrue(explore.handleLink("${baseUrl()}/org/app/-/snippets/99"))
    assertEquals(ScreenDestination.FileViewer(7L, "snippet-99", snippetId = 99L), awaitNavigated(4))
  }

  @Test
  fun `project-scoped search starts in ISSUES and queries project blobs`() = runBlocking {
    server.enqueue(
      json("""[{"basename":"Rocket","path":"src/Rocket.kt","filename":"Rocket.kt","ref":"main","startline":12,"project_id":7,"data":"class Rocket"}]""")
    )

    explore.openSearch(projectId = 7L, projectName = "Rocket")
    assertEquals(ScreenDestination.Search(7L, "Rocket"), host.navigated.single())
    assertEquals(SearchScope.ISSUES, host.state.value.search.scope)

    explore.setQuery("launch")
    explore.setScope(SearchScope.BLOBS)

    withTimeout(5_000) {
      while (!host.state.value.search.blobs.loaded) delay(15)
    }
    val blobs = host.state.value.search.blobs.data
    assertEquals(1, blobs.size)
    assertEquals("src/Rocket.kt", blobs.single().path)
    assertEquals("/api/v4/projects/7/search?scope=blobs&search=launch&per_page=30", server.takeRequest().path)
  }
}
