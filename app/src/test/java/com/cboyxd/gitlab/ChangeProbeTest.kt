package com.cboyxd.gitlab

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.notifications.ChangeProbe
import com.cboyxd.gitlab.notifications.ProbeResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The light per-account request that decides whether instant notifications read every feed. */
class ChangeProbeTest {

  private lateinit var server: MockWebServer
  private lateinit var account: GitLabInstance
  private val probe = ChangeProbe()

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
    account = GitLabInstance(
      id = "probe-" + System.nanoTime(),
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

  private fun counts(todos: Int, etag: String? = null) = MockResponse()
    .setBody("""{"merge_requests":2,"assigned_issues":1,"assigned_merge_requests":1,"review_requested_merge_requests":0,"todos":$todos}""")
    .addHeader("Content-Type", "application/json")
    .apply { if (etag != null) addHeader("ETag", etag) }

  @Test
  fun `the first probe reports a change and identical counts don't`() = runBlocking {
    server.enqueue(counts(3))
    server.enqueue(counts(3))
    server.enqueue(counts(4))
    assertEquals(ProbeResult.Changed, probe.check(account))
    assertEquals(ProbeResult.Unchanged, probe.check(account))
    assertEquals(ProbeResult.Changed, probe.check(account))
    assertEquals("/api/v4/user_counts", server.takeRequest().path)
  }

  @Test
  fun `the etag is sent back and a 304 means unchanged`() = runBlocking {
    server.enqueue(counts(3, etag = "W/\"abc\""))
    server.enqueue(MockResponse().setResponseCode(304))
    assertEquals(ProbeResult.Changed, probe.check(account))
    assertEquals(ProbeResult.Unchanged, probe.check(account))
    assertNull(server.takeRequest().getHeader("If-None-Match"))
    assertEquals("W/\"abc\"", server.takeRequest().getHeader("If-None-Match"))
  }

  @Test
  fun `servers without user_counts fall back to the newest to-do`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"404 Not Found"}"""))
    server.enqueue(MockResponse().setBody("""[{"id":10}]""").addHeader("X-Total", "1"))
    server.enqueue(MockResponse().setBody("""[{"id":10}]""").addHeader("X-Total", "1"))
    server.enqueue(MockResponse().setBody("""[{"id":11}]""").addHeader("X-Total", "2"))
    assertEquals(ProbeResult.Changed, probe.check(account))
    assertEquals(ProbeResult.Unchanged, probe.check(account))
    assertEquals(ProbeResult.Changed, probe.check(account))
    assertEquals("/api/v4/user_counts", server.takeRequest().path)
    val todos = server.takeRequest().requestUrl!!
    assertEquals("/api/v4/todos", todos.encodedPath)
    assertEquals("pending", todos.queryParameter("state"))
    assertEquals("1", todos.queryParameter("per_page"))
    // user_counts isn't asked again once the server said it doesn't have it.
    assertEquals("/api/v4/todos", server.takeRequest().requestUrl!!.encodedPath)
  }

  @Test
  fun `rate limits carry the server's retry-after`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
    assertEquals(ProbeResult.RateLimited(120_000), probe.check(account))
    assertEquals(60_000, ChangeProbe.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    assertEquals(60_000, ChangeProbe.retryAfterMs(null))
  }

  @Test
  fun `auth and server errors are failures, auth ones marked`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(502))
    val auth = probe.check(account)
    assertTrue(auth is ProbeResult.Failed && auth.authError)
    val server = probe.check(account)
    assertTrue(server is ProbeResult.Failed && !server.authError && server.message == "HTTP 502")
  }
}
