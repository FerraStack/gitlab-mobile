package com.cboyxd.gitlab.ui.viewmodel

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.repository.GitLabRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Pipeline and job polling pause when backgrounded and refresh immediately upon returning. */
class PipelinePollingTest {

  private lateinit var server: MockWebServer
  private lateinit var instance: GitLabInstance

  @Before
  fun setUp() {
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certificates = HandshakeCertificates.Builder()
      .heldCertificate(certificate)
      .addTrustedCertificate(certificate.certificate)
      .build()
    server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        return when {
          path.endsWith("/pipelines/10") -> MockResponse().addHeader("Content-Type", "application/json")
            .setBody("""{"id":10,"project_id":1,"status":"running","ref":"main"}""")
          path.endsWith("/pipelines/10/jobs") -> MockResponse().addHeader("Content-Type", "application/json")
            .setBody("""[{"id":20,"name":"test","stage":"test","status":"running"}]""")
          path.endsWith("/jobs/20") -> MockResponse().addHeader("Content-Type", "application/json")
            .setBody("""{"id":20,"name":"test","stage":"test","status":"running"}""")
          path.endsWith("/jobs/20/trace") -> MockResponse().setBody("step 1\n")
          else -> MockResponse().setResponseCode(404)
        }
      }
    }
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

  private fun TestScope.newHarness(): Triple<PipelineFeature, MutableStateFlow<Boolean>, java.util.concurrent.LinkedBlockingQueue<Unit>> {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val foreground = MutableStateFlow(true)
    val callEvents = java.util.concurrent.LinkedBlockingQueue<Unit>()
    val host = object : SessionHost {
      override val repository = GitLabRepository()
      override val session = CoroutineScope(dispatcher + SupervisorJob())
      override val state = MutableStateFlow(GitLabUiState())
      override val foreground: StateFlow<Boolean> = foreground.asStateFlow()
      override suspend fun <T> api(block: suspend (GitLabInstance) -> Result<T>): Result<T> {
        val result = block(instance)
        callEvents.offer(Unit)
        return result
      }
      override fun notify(text: String) = Unit
      override fun notifyError(text: String) = Unit
      override fun navigateTo(destination: ScreenDestination) = Unit
      override fun openExternal(url: String) = Unit
    }
    return Triple(PipelineFeature(host), foreground, callEvents)
  }

  private fun TestScope.awaitNewCalls(callEvents: java.util.concurrent.LinkedBlockingQueue<Unit>, count: Int) {
    runCurrent()
    repeat(count) {
      val event = callEvents.poll(5, java.util.concurrent.TimeUnit.SECONDS)
      org.junit.Assert.assertNotNull("Timed out waiting for API call", event)
    }
    runCurrent()
  }

  @Test
  fun `pipeline polling pauses in background and refreshes on return`() = runTest {
    val (feature, foreground, callEvents) = newHarness()
    try {
      feature.loadPipeline(1, 10)
      awaitNewCalls(callEvents, 2)
      assertEquals(2, server.requestCount)

      // Backgrounded: advancing virtual time well past the 8s interval must not poll.
      foreground.value = false
      runCurrent()
      advanceTimeBy(60_000)
      runCurrent()
      assertEquals(2, server.requestCount)

      // Returning to foreground refreshes immediately without waiting another 8s.
      foreground.value = true
      awaitNewCalls(callEvents, 2)
      assertEquals(4, server.requestCount)

      // Foreground polling resumes normally after 8s.
      advanceTimeBy(LivePolling.INTERVAL)
      awaitNewCalls(callEvents, 2)
      assertEquals(6, server.requestCount)
    } finally {
      feature.reset()
      runCurrent()
    }
  }

  @Test
  fun `job polling pauses in background and refreshes on return`() = runTest {
    val (feature, foreground, callEvents) = newHarness()
    try {
      feature.loadJob(1, 20)
      awaitNewCalls(callEvents, 2)
      assertEquals(2, server.requestCount)

      foreground.value = false
      runCurrent()
      advanceTimeBy(60_000)
      runCurrent()
      assertEquals(2, server.requestCount)

      foreground.value = true
      awaitNewCalls(callEvents, 2)
      assertEquals(4, server.requestCount)
    } finally {
      feature.reset()
      runCurrent()
    }
  }
}
