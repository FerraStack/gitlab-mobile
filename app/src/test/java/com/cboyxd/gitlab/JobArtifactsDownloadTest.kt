package com.cboyxd.gitlab

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.ui.screens.artifactsFileName
import com.cboyxd.gitlab.ui.viewmodel.formatBytes
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Downloading a job's artifacts archive into a file the user picked. */
class JobArtifactsDownloadTest {

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
  fun `the job's archive is read from the API`() = runBlocking {
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json")
        .setBody("""{"id":42,"name":"deploy","status":"success","artifacts_file":{"filename":"artifacts.zip","size":1536}}"""),
    )
    val job = repository.getJob(instance, 1, 42).getOrThrow()
    assertEquals("artifacts.zip", job.artifactsFile?.filename)
    assertEquals(1536L, job.artifactsFile?.size)
  }

  @Test
  fun `a job without artifacts has no archive`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"id":40,"name":"lint","status":"success"}"""))
    assertEquals(null, repository.getJob(instance, 1, 40).getOrThrow().artifactsFile)
  }

  @Test
  fun `artifacts stream into the picked file`() = runBlocking {
    // Bigger than one buffer, so it has to be copied in chunks rather than read whole.
    val archive = ByteArray(200_000) { (it % 251).toByte() }
    server.enqueue(MockResponse().addHeader("Content-Type", "application/zip").setBody(Buffer().write(archive)))
    val out = ByteArrayOutputStream()

    val written = repository.downloadJobArtifacts(instance, 1, 42) { out }.getOrThrow()

    assertEquals(archive.size.toLong(), written)
    assertArrayEquals(archive, out.toByteArray())
    val request = server.takeRequest()
    assertEquals("/api/v4/projects/1/jobs/42/artifacts", request.path)
    assertEquals("glpat-test", request.getHeader("PRIVATE-TOKEN"))
    assertEquals("*/*", request.getHeader("Accept"))
  }

  @Test
  fun `expired artifacts say so and never open the file`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"404 Not Found"}"""))
    var opened = false

    val error = repository.downloadJobArtifacts(instance, 1, 42) { opened = true; ByteArrayOutputStream() }.exceptionOrNull()

    assertTrue(error is GitLabApiException && error.message!!.contains("expired"))
    assertEquals(false, opened)
  }

  @Test
  fun `sizes read like a file manager's`() {
    assertEquals("740 B", formatBytes(740))
    assertEquals("1.5 KB", formatBytes(1536))
    assertEquals("12 KB", formatBytes(12_700))
    assertEquals("3.1 MB", formatBytes(3_250_000))
    assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
  }

  @Test
  fun `the suggested name keeps jobs apart`() {
    assertEquals("deploy-staging-42-artifacts.zip", artifactsFileName(GitLabJob(id = 42, name = "deploy-staging"), "artifacts.zip"))
    assertEquals("build-android-7-artifacts.tar.gz", artifactsFileName(GitLabJob(id = 7, name = "build: android"), "artifacts.tar.gz"))
    assertEquals("job-9-artifacts.zip", artifactsFileName(GitLabJob(id = 9, name = "🚀"), ""))
  }
}
