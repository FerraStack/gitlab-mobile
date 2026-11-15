package com.cboyxd.gitlab

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.ui.viewmodel.GitLabUiState
import com.cboyxd.gitlab.ui.viewmodel.ScreenDestination
import com.cboyxd.gitlab.ui.viewmodel.SessionHost
import com.cboyxd.gitlab.ui.viewmodel.SnippetFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
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

class SnippetsAndCiTest {

  private lateinit var server: MockWebServer
  private lateinit var instance: GitLabInstance
  private val repository = GitLabRepository()

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
  }

  @After
  fun tearDown() {
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    server.shutdown()
  }

  @Test
  fun `personal and project snippets and raw contents decode cleanly`() = runBlocking {
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json").setBody(
        """[{"id":5,"title":"Deploy helper","file_name":"deploy.sh","visibility":"private","files":[{"path":"deploy.sh","raw_url":"https://gitlab.com/-/snippets/5/raw"}]}]"""
      )
    )
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("[]"))
    val personal = repository.getSnippets(instance).getOrThrow()
    assertEquals(1, personal.size)
    assertEquals("Deploy helper", personal.first().title)
    assertEquals("private", personal.first().visibility)

    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json").setBody(
        """[{"id":9,"project_id":12,"title":"Proj script","file_name":"run.py","visibility":"public"}]"""
      )
    )
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("[]"))
    val projectSnippets = repository.getProjectSnippets(instance, 12L).getOrThrow()
    assertEquals(1, projectSnippets.size)
    assertEquals(12L, projectSnippets.first().projectId)

    server.enqueue(MockResponse().setBody("#!/bin/bash\necho hi"))
    val raw = repository.getSnippetRaw(instance, 9L, projectId = 12L).getOrThrow()
    assertEquals("#!/bin/bash\necho hi", raw)
  }

  @Test
  fun `project variables sort by key and translate 403 for non-maintainers`() = runBlocking {
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json").setBody(
        """[
          {"key":"Z_TOKEN","variable_type":"env_var","value":"secret-z","masked":true,"hidden":false,"protected":true,"environment_scope":"production"},
          {"key":"A_REGION","variable_type":"env_var","value":"us-east-1","masked":false,"hidden":false,"protected":false,"environment_scope":"*"}
        ]"""
      )
    )
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("[]"))

    val vars = repository.getProjectVariables(instance, 12L).getOrThrow()
    assertEquals(listOf("A_REGION", "Z_TOKEN"), vars.map { it.key })
    assertTrue(vars[1].masked)

    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"403 Forbidden"}"""))
    val forbidden = repository.getProjectVariables(instance, 12L).exceptionOrNull()
    assertTrue(forbidden is GitLabApiException)
    assertTrue(forbidden!!.message!!.contains("Maintainer or Owner"))
  }

  @Test
  fun `project environments decode tier, external url and last deployment`() = runBlocking {
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json").setBody(
        """[
          {
            "id": 31,
            "name": "production",
            "slug": "production",
            "state": "available",
            "tier": "production",
            "external_url": "https://app.example.com",
            "last_deployment": {
              "id": 501,
              "iid": 44,
              "ref": "main",
              "sha": "abcdef1234567890",
              "status": "success",
              "user": {"id": 1, "username": "tanuki", "name": "Tanuki"}
            }
          }
        ]"""
      )
    )
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("[]"))

    val envs = repository.getProjectEnvironments(instance, 12L).getOrThrow()
    assertEquals(1, envs.size)
    val env = envs.first()
    assertEquals("production", env.name)
    assertEquals("https://app.example.com", env.externalUrl)
    assertEquals("main", env.lastDeployment?.ref)
    assertEquals("success", env.lastDeployment?.status)
    assertEquals("tanuki", env.lastDeployment?.user?.username)
  }

  @Test
  fun `snippet feature loads snippet metadata and raw content`() = runBlocking {
    server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
      override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
          path.endsWith("/raw") -> MockResponse().setBody("print('hello')")
          else -> MockResponse().addHeader("Content-Type", "application/json")
            .setBody("""{"id":7,"project_id":12,"title":"Hello","file_name":"hello.py","visibility":"public"}""")
        }
      }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(GitLabUiState(activeInstance = instance))
    val host = object : SessionHost {
      override val repository = this@SnippetsAndCiTest.repository
      override val session = scope
      override val state = state
      override val foreground: StateFlow<Boolean> = MutableStateFlow(true)
      override suspend fun <T> api(block: suspend (GitLabInstance) -> Result<T>): Result<T> = block(instance)
      override fun notify(text: String) = Unit
      override fun notifyError(text: String, retryAction: (() -> Unit)?) = Unit
      override fun navigateTo(destination: ScreenDestination) = Unit
      override fun openExternal(url: String) = Unit
    }

    try {
      val feature = SnippetFeature(host)
      feature.loadDetail(snippetId = 7L, projectId = 12L, force = true)
      val deadline = System.currentTimeMillis() + 5_000
      while (System.currentTimeMillis() < deadline) {
        val detail = state.value.snippetDetail
        if (detail.snippet.loaded && detail.rawContent.loaded) break
        Thread.sleep(10)
      }
      val settled = state.value.snippetDetail
      assertEquals(7L, settled.snippetId)
      assertEquals("Hello", settled.snippet.data?.title)
      assertEquals("print('hello')", settled.rawContent.data)
    } finally {
      scope.cancel()
    }
  }
}
