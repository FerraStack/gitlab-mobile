package com.cboyxd.gitlab

import androidx.compose.ui.text.LinkAnnotation
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.api.parseRetryAfterSeconds
import com.cboyxd.gitlab.data.api.rateLimitInterceptor
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabDiffPosition
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.data.repository.decodeFileBytes
import com.cboyxd.gitlab.data.repository.utf8CompletePrefixLength
import com.cboyxd.gitlab.ui.components.DiffFileMetrics
import com.cboyxd.gitlab.ui.components.DiffKind
import com.cboyxd.gitlab.ui.components.DiffLine
import com.cboyxd.gitlab.ui.components.DiscussionPreviewCache
import com.cboyxd.gitlab.ui.components.MAX_DIFF_COLUMNS
import com.cboyxd.gitlab.ui.components.MarkdownCache
import com.cboyxd.gitlab.ui.components.MarkdownContext
import com.cboyxd.gitlab.ui.components.MdBlock
import com.cboyxd.gitlab.ui.components.computeDiffFileMetrics
import com.cboyxd.gitlab.ui.components.highlight
import com.cboyxd.gitlab.ui.components.highlightForPath
import com.cboyxd.gitlab.ui.components.normalizeLineEndings
import com.cboyxd.gitlab.ui.components.parseUnifiedDiff
import com.cboyxd.gitlab.ui.components.prepareDiffLines
import com.cboyxd.gitlab.ui.components.sliceDiscussionDiffPreview
import com.cboyxd.gitlab.ui.components.splitLines
import com.cboyxd.gitlab.ui.theme.DarkGlColors
import com.cboyxd.gitlab.ui.theme.LightGlColors
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException

class PerformanceAndCorrectnessTest {

  @Before
  fun setUp() {
    MarkdownCache.clear()
    DiscussionPreviewCache.clear()
  }

  @After
  fun tearDown() {
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    MarkdownCache.clear()
    DiscussionPreviewCache.clear()
  }

  // -------------------------------------------------------------------------------------------
  // Issue #47: CRLF and CR normalization
  // -------------------------------------------------------------------------------------------

  @Test
  fun `normalizeLineEndings converts CRLF and lone CR to LF`() {
    assertEquals("a\nb\nc\n", normalizeLineEndings("a\r\nb\rc\n"))
    assertEquals("plain\ntext", normalizeLineEndings("plain\ntext"))
  }

  @Test
  fun `decodeFileBytes normalizes CRLF and lone CR line endings`() {
    val bytes = "first\r\nsecond\rthird\n".toByteArray(Charsets.UTF_8)
    val decoded = decodeFileBytes(bytes, truncated = false)
    assertEquals("first\nsecond\nthird\n", decoded)
    assertFalse(decoded.contains('\r'))
  }

  @Test
  fun `highlight normalizes CRLF and preserves line count for multi-line and line-by-line languages`() {
    val kotlin = highlight("val a = 1\r\nval b = 2\rval c = 3", SyntaxLanguage.KOTLIN, LightGlColors)
    assertEquals(listOf("val a = 1", "val b = 2", "val c = 3"), kotlin.splitLines().map { it.text })

    val python = highlight("x = 1\r\ny = 2\r# note", SyntaxLanguage.PYTHON, LightGlColors)
    assertEquals(listOf("x = 1", "y = 2", "# note"), python.splitLines().map { it.text })

    val markdown = highlightForPath("# Title\r\nbody\rline", "README.md", LightGlColors)
    assertEquals("# Title\nbody\nline", markdown.text)
  }

  // -------------------------------------------------------------------------------------------
  // Issue #69: UTF-8 safe truncation and binary/404 handling
  // -------------------------------------------------------------------------------------------

  @Test
  fun `utf8CompletePrefixLength drops incomplete trailing multibyte sequences`() {
    val prefix = "abc".toByteArray(Charsets.UTF_8)
    assertEquals(prefix.size, utf8CompletePrefixLength(prefix))

    val twoByte = "é".toByteArray(Charsets.UTF_8) // 2 bytes
    assertEquals(prefix.size + 2, utf8CompletePrefixLength(prefix + twoByte))
    assertEquals(prefix.size, utf8CompletePrefixLength(prefix + twoByte.copyOf(1)))

    val threeByte = "€".toByteArray(Charsets.UTF_8) // 3 bytes
    assertEquals(prefix.size + 3, utf8CompletePrefixLength(prefix + threeByte))
    assertEquals(prefix.size, utf8CompletePrefixLength(prefix + threeByte.copyOf(1)))
    assertEquals(prefix.size, utf8CompletePrefixLength(prefix + threeByte.copyOf(2)))

    val fourByte = "🚀".toByteArray(Charsets.UTF_8) // 4 bytes
    assertEquals(prefix.size + 4, utf8CompletePrefixLength(prefix + fourByte))
    assertEquals(prefix.size, utf8CompletePrefixLength(prefix + fourByte.copyOf(1)))
    assertEquals(prefix.size, utf8CompletePrefixLength(prefix + fourByte.copyOf(2)))
    assertEquals(prefix.size, utf8CompletePrefixLength(prefix + fourByte.copyOf(3)))
  }

  @Test
  fun `decodeFileBytes trims split UTF-8 codepoint on truncation and detects binary files`() {
    val splitRocket = "hello".toByteArray(Charsets.UTF_8) + "🚀".toByteArray(Charsets.UTF_8).copyOf(2)
    val decoded = decodeFileBytes(splitRocket, truncated = true)
    assertFalse(decoded.contains('\uFFFD'))
    assertTrue(decoded.startsWith("hello\n\n… The file is longer than 400 KB."))

    val binary = byteArrayOf('A'.code.toByte(), 0, 'B'.code.toByte())
    assertEquals("Binary file (3 bytes). Open it on the web to view it.", decodeFileBytes(binary, truncated = false))
  }

  @Test
  fun `getFileContent reports target ref when server responds 404`() = runBlocking {
    val (server, instance) = httpsServer()
    try {
      server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"404 File Not Found"}"""))
      val error = GitLabRepository().getFileContent(instance, 1L, "src/Main.kt", "feature/perf").exceptionOrNull()
      assertTrue(error is GitLabApiException)
      assertEquals(404, (error as GitLabApiException).code)
      assertTrue(error.message.orEmpty().contains("feature/perf"))
    } finally {
      server.shutdown()
    }
  }

  // -------------------------------------------------------------------------------------------
  // Issue #48: 429 Retry-After interceptor
  // -------------------------------------------------------------------------------------------

  @Test
  fun `parseRetryAfterSeconds accepts 0 to max and rejects invalid values`() {
    assertEquals(0L, parseRetryAfterSeconds("0"))
    assertEquals(5L, parseRetryAfterSeconds(" 5 "))
    assertEquals(GitLabApiClient.MAX_RETRY_AFTER_SECONDS, parseRetryAfterSeconds(GitLabApiClient.MAX_RETRY_AFTER_SECONDS.toString()))
    assertNull(parseRetryAfterSeconds((GitLabApiClient.MAX_RETRY_AFTER_SECONDS + 1L).toString()))
    assertNull(parseRetryAfterSeconds("-1"))
    assertNull(parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
    assertNull(parseRetryAfterSeconds(null))
  }

  @Test
  fun `rateLimitInterceptor retries idempotent GET and HEAD once with Retry-After delay`() {
    val server = MockWebServer()
    server.start()
    try {
      val slept = mutableListOf<Long>()
      val client = OkHttpClient.Builder()
        .addInterceptor(rateLimitInterceptor { ms -> slept += ms })
        .build()

      server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
      server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
      client.newCall(Request.Builder().url(server.url("/get")).get().build()).execute().use { resp ->
        assertEquals(200, resp.code)
        assertEquals("ok", resp.body?.string())
      }
      assertEquals(listOf(2_000L), slept)
      assertEquals(2, server.requestCount)

      slept.clear()
      server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
      server.enqueue(MockResponse().setResponseCode(200))
      client.newCall(Request.Builder().url(server.url("/head")).head().build()).execute().use { resp ->
        assertEquals(200, resp.code)
      }
      assertEquals(listOf(1_000L), slept)
      assertEquals(4, server.requestCount)

      slept.clear()
      server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
      server.enqueue(MockResponse().setResponseCode(200).setBody("fast"))
      client.newCall(Request.Builder().url(server.url("/zero")).get().build()).execute().use { resp ->
        assertEquals(200, resp.code)
        assertEquals("fast", resp.body?.string())
      }
      assertTrue(slept.isEmpty())
      assertEquals(6, server.requestCount)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `rateLimitInterceptor does not retry POST or missing or excessive Retry-After`() {
    val server = MockWebServer()
    server.start()
    try {
      val slept = mutableListOf<Long>()
      val client = OkHttpClient.Builder()
        .addInterceptor(rateLimitInterceptor { ms -> slept += ms })
        .build()

      server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
      val postBody = "{}".toRequestBody("application/json".toMediaType())
      client.newCall(Request.Builder().url(server.url("/post")).post(postBody).build()).execute().use { resp ->
        assertEquals(429, resp.code)
      }

      server.enqueue(MockResponse().setResponseCode(429))
      client.newCall(Request.Builder().url(server.url("/no-header")).get().build()).execute().use { resp ->
        assertEquals(429, resp.code)
      }

      server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
      client.newCall(Request.Builder().url(server.url("/too-long")).get().build()).execute().use { resp ->
        assertEquals(429, resp.code)
      }

      assertTrue(slept.isEmpty())
      assertEquals(3, server.requestCount)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `rateLimitInterceptor propagates interrupt during retry backoff`() {
    val server = MockWebServer()
    server.start()
    try {
      server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
      val client = OkHttpClient.Builder()
        .addInterceptor(rateLimitInterceptor { throw InterruptedException("stopped") })
        .build()

      var caught: InterruptedIOException? = null
      try {
        client.newCall(Request.Builder().url(server.url("/interrupt")).get().build()).execute().close()
      } catch (e: InterruptedIOException) {
        caught = e
      } finally {
        // Clear interrupt flag on the test runner thread so later tests stay clean.
        assertTrue(Thread.interrupted())
      }
      assertNotNull(caught)
      assertEquals(1, server.requestCount)
    } finally {
      server.shutdown()
    }
  }

  // -------------------------------------------------------------------------------------------
  // Issue #44: Markdown LRU cache and DiscussionDiffPreview slicing
  // -------------------------------------------------------------------------------------------

  @Test
  fun `MarkdownCache hits on matching inputs, updates link callback, and misses on theme or context changes`() {
    val ctx1 = MarkdownContext(webBase = "https://gitlab.com/group/app", rawBase = "https://gitlab.com/group/app/-/raw/main/")
    val clicked = mutableListOf<String>()

    val first = MarkdownCache.getOrParse("[doc](https://example.com/a)", LightGlColors, ctx1) { url ->
      clicked += "first:$url"
    }
    val second = MarkdownCache.getOrParse("[doc](https://example.com/a)", LightGlColors, ctx1) { url ->
      clicked += "second:$url"
    }

    assertSame(first, second)
    assertEquals(1, MarkdownCache.size())

    val paragraph = second.blocks.single() as MdBlock.Paragraph
    val link = paragraph.text.getLinkAnnotations(0, paragraph.text.length).single().item as LinkAnnotation.Clickable
    link.linkInteractionListener?.onClick(link)
    assertEquals(listOf("second:https://example.com/a"), clicked)

    val dark = MarkdownCache.getOrParse("[doc](https://example.com/a)", DarkGlColors, ctx1) {}
    assertNotSame(first, dark)
    val otherCtx = MarkdownContext(webBase = "https://gitlab.com/group/other", rawBase = ctx1.rawBase)
    val other = MarkdownCache.getOrParse("[doc](https://example.com/a)", LightGlColors, otherCtx) {}
    assertNotSame(first, other)
    assertEquals(3, MarkdownCache.size())
  }

  @Test
  fun `MarkdownCache evicts eldest entries beyond cap`() {
    val ctx = MarkdownContext()
    for (i in 1..140) {
      MarkdownCache.getOrParse("entry $i", LightGlColors, ctx) {}
    }
    assertEquals(128, MarkdownCache.size())
  }

  @Test
  fun `sliceDiscussionDiffPreview slices target hunk window before invoking prepare`() {
    val diff = buildString {
      appendLine("@@ -1,3 +1,3 @@")
      appendLine(" early1")
      appendLine(" early2")
      appendLine(" early3")
      appendLine("@@ -100,15 +100,15 @@")
      for (n in 100..114) {
        appendLine(if (n == 107) "+val target = $n" else " val line$n = $n")
      }
    }
    val position = GitLabDiffPosition(oldPath = "A.kt", newPath = "A.kt", newLine = 107)
    var preparedCount = 0
    val preview = sliceDiscussionDiffPreview(diff, position, "A.kt", LightGlColors) { window, path, colors ->
      preparedCount = window.size
      prepareDiffLines(window, path, colors)
    }

    assertNotNull(preview)
    val (lines, texts) = preview!!
    assertEquals(preparedCount, lines.size)
    assertEquals(lines.size, texts.size)
    assertTrue("Expected a narrow window around target line, got ${lines.size}", lines.size <= 7)
    assertTrue(lines.none { it.kind == DiffKind.Hunk })
    assertTrue(lines.any { it.newNo == 107 && it.kind == DiffKind.Add })

    val missing = GitLabDiffPosition(oldPath = "A.kt", newPath = "A.kt", newLine = 999)
    assertNull(sliceDiscussionDiffPreview(diff, missing, "A.kt", LightGlColors))
  }

  // -------------------------------------------------------------------------------------------
  // Issues #82 & #35: Diff metrics, preparation, and capped MR diff pagination
  // -------------------------------------------------------------------------------------------

  @Test
  fun `computeDiffFileMetrics computes max line and capped column width`() {
    assertEquals(DiffFileMetrics(maxLine = 0, maxColumns = 0), computeDiffFileMetrics(emptyList()))

    val lines = listOf(
      DiffLine(DiffKind.Hunk, null, null, "@@ -10,2 +420,2 @@"),
      DiffLine(DiffKind.Remove, 10, null, "short"),
      DiffLine(DiffKind.Add, null, 420, "x".repeat(MAX_DIFF_COLUMNS + 250)),
    )
    assertEquals(
      DiffFileMetrics(maxLine = 420, maxColumns = MAX_DIFF_COLUMNS),
      computeDiffFileMetrics(lines),
    )
  }

  @Test
  fun `parseUnifiedDiff and prepareDiffLines preserve lines and highlight inline edits`() {
    val diff = "@@ -1,2 +1,2 @@\r\n-val count = 1\r\n+val count = 2\r\n \tkept\r\n"
    val parsed = parseUnifiedDiff(diff)
    assertEquals(4, parsed.size)
    assertEquals(listOf(DiffKind.Hunk, DiffKind.Remove, DiffKind.Add, DiffKind.Context), parsed.map { it.kind })
    assertEquals("    kept", parsed[3].text)
    assertNotNull(parsed[1].changed)
    assertNotNull(parsed[2].changed)

    val prepared = prepareDiffLines(parsed, "Sample.kt", LightGlColors)
    assertEquals(parsed.size, prepared.size)
    assertEquals("val count = 2", prepared[2].text)
    assertTrue(prepared[2].spanStyles.isNotEmpty())
  }

  @Test
  fun `getMergeRequestDiffsPage stops at page cap and surfaces truncation state`() = runBlocking {
    val (server, instance) = httpsServer()
    try {
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val page = request.requestUrl?.queryParameter("page")?.toInt() ?: 1
          return MockResponse()
            .setBody("""[{"old_path":"f$page.kt","new_path":"f$page.kt","diff":"@@ -1 +1 @@\n+a"}]""")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Total", "900")
            .addHeader("X-Next-Page", (page + 1).toString())
        }
      }

      val slice = GitLabRepository().getMergeRequestDiffsPage(instance, 1L, 7L).getOrThrow()
      assertEquals(5, slice.items.size)
      assertEquals(5, server.requestCount)
      assertTrue(slice.truncated)
      assertEquals(900, slice.totalCount)
    } finally {
      server.shutdown()
    }
  }

  private fun httpsServer(): Pair<MockWebServer, GitLabInstance> {
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certificates = HandshakeCertificates.Builder()
      .heldCertificate(certificate)
      .addTrustedCertificate(certificate.certificate)
      .build()
    val server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.start()
    GitLabApiClient.clearCache()
    GitLabApiClient.clientOverride = OkHttpClient.Builder()
      .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
      .build()
    val instance = GitLabInstance(
      id = "perf-test-" + System.nanoTime(),
      name = "Test",
      url = server.url("/").toString(),
      token = "glpat-test",
      authKind = GitLabAuth.KIND_PAT,
    )
    return server to instance
  }
}
