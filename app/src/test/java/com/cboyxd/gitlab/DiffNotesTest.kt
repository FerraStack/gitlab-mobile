package com.cboyxd.gitlab

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.DiffLineAnchor
import com.cboyxd.gitlab.data.model.GitLabDiffPosition
import com.cboyxd.gitlab.data.model.GitLabDiffRefs
import com.cboyxd.gitlab.data.model.GitLabDiscussion
import com.cboyxd.gitlab.data.model.GitLabFileDiff
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.model.GitLabNote
import com.cboyxd.gitlab.data.model.diffNotePosition
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.ui.components.anchorIn
import com.cboyxd.gitlab.ui.components.commentedLines
import com.cboyxd.gitlab.ui.components.parseUnifiedDiff
import com.cboyxd.gitlab.ui.components.rawNewLine
import com.cboyxd.gitlab.ui.screens.withSuggestion
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
import java.net.URLDecoder

/** Anchoring comments to diff lines, and posting them. */
class DiffNotesTest {

  private val refs = GitLabDiffRefs(baseSha = "base111", startSha = "start222", headSha = "head333")
  private val file = GitLabFileDiff(oldPath = "src/old.kt", newPath = "src/new.kt", diff = "@@ -10,3 +10,3 @@\n keep()\n-  return\n+  ignite()\n")

  @Test
  fun `each kind of line names the sides GitLab expects`() {
    val (_, context, removed, added) = parseUnifiedDiff(file.diff).map { it.anchorIn(file) }
    assertEquals(DiffLineAnchor("src/old.kt", "src/new.kt", oldLine = 10, newLine = 10), context)
    assertEquals(DiffLineAnchor("src/old.kt", "src/new.kt", oldLine = 11, newLine = null), removed)
    assertEquals(DiffLineAnchor("src/old.kt", "src/new.kt", oldLine = null, newLine = 11), added)
    assertEquals(null, parseUnifiedDiff(file.diff).first().anchorIn(file))
  }

  @Test
  fun `the position carries the version and only the line's sides`() {
    val position = diffNotePosition(refs, DiffLineAnchor("src/old.kt", "src/new.kt", oldLine = null, newLine = 11))
    assertEquals(
      mapOf(
        "position[position_type]" to "text",
        "position[base_sha]" to "base111",
        "position[start_sha]" to "start222",
        "position[head_sha]" to "head333",
        "position[old_path]" to "src/old.kt",
        "position[new_path]" to "src/new.kt",
        "position[new_line]" to "11",
      ),
      position,
    )
    val context = diffNotePosition(refs, DiffLineAnchor("a", "a", oldLine = 3, newLine = 4))
    assertEquals("3", context["position[old_line]"])
    assertEquals("4", context["position[new_line]"])
  }

  @Test
  fun `only threads on the version shown mark lines`() {
    fun thread(id: String, head: String, old: Int?, new: Int?) = GitLabDiscussion(
      id, notes = listOf(GitLabNote(id = id.hashCode().toLong(), position = GitLabDiffPosition(headSha = head, oldPath = "src/old.kt", newPath = "src/new.kt", oldLine = old, newLine = new))),
    )
    val marked = commentedLines(
      listOf(
        thread("a", "head333", old = null, new = 11),
        thread("b", "head333", old = 11, new = null),
        thread("c", "older00", old = null, new = 12),
        GitLabDiscussion("d", individualNote = true, notes = listOf(GitLabNote(id = 9, body = "General comment"))),
      ),
      headSha = "head333",
    )
    assertEquals(setOf("new:src/new.kt:11", "old:src/old.kt:11"), marked)
    assertEquals(emptySet<String>(), commentedLines(listOf(thread("a", "head333", null, 11)), headSha = null))
  }

  @Test
  fun `suggestions copy the line as written, tabs and all`() {
    val tabbed = "@@ -1,3 +1,4 @@\n func main() {\n-\treturn\n+\tlaunch()\n+\t\tcount++\n }\n\\ No newline at end of file\n"
    assertEquals("func main() {", rawNewLine(tabbed, 1))
    assertEquals("\tlaunch()", rawNewLine(tabbed, 2))
    assertEquals("\t\tcount++", rawNewLine(tabbed, 3))
    assertEquals("}", rawNewLine(tabbed, 4))
    assertEquals(null, rawNewLine(tabbed, 5))
    // The displayed text has the tabs expanded; the suggestion must not.
    assertEquals("    launch()", parseUnifiedDiff(tabbed)[3].text)
  }

  @Test
  fun `a suggestion starts with the line selected for editing`() {
    val started = withSuggestion(TextFieldValue(""), "  ignite()")
    assertEquals("```suggestion:-0+0\n  ignite()\n```\n", started.text)
    assertEquals("  ignite()", started.text.substring(started.selection.start, started.selection.end))

    val afterText = withSuggestion(TextFieldValue("Maybe:  "), "x")
    assertEquals("Maybe:\n\n```suggestion:-0+0\nx\n```\n", afterText.text)
    assertEquals(TextRange(27, 28), afterText.selection)
  }

  // ---- Posting ----

  private lateinit var server: MockWebServer
  private lateinit var instance: GitLabInstance
  private val repository = GitLabRepository()

  @Before
  fun setUp() {
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).addTrustedCertificate(certificate.certificate).build()
    server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.start()
    GitLabApiClient.clearCache()
    GitLabApiClient.clientOverride = OkHttpClient.Builder().sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager).build()
    instance = GitLabInstance(id = "test-" + System.nanoTime(), name = "Test", url = server.url("/").toString(), token = "glpat-test", authKind = GitLabAuth.KIND_PAT)
  }

  @After
  fun tearDown() {
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    server.shutdown()
  }

  @Test
  fun `the merge request carries the versions to anchor to`() = runBlocking {
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json")
        .setBody("""{"id":1,"iid":12,"project_id":1,"title":"Launch","diff_refs":{"base_sha":"base111","head_sha":"head333","start_sha":"start222"}}"""),
    )
    assertEquals(refs, repository.getMergeRequest(instance, 1, 12).getOrThrow().diffRefs)
  }

  @Test
  fun `a line comment is posted as a positioned thread`() = runBlocking {
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json")
        .setBody("""{"id":"abc","individual_note":false,"notes":[{"id":77,"body":"Rename?","type":"DiffNote","resolvable":true,"resolved":false,"position":{"base_sha":"base111","head_sha":"head333","old_path":"src/old.kt","new_path":"src/new.kt","position_type":"text","new_line":11}}]}"""),
    )
    val thread = repository.createDiffNote(instance, 1, 12, refs, DiffLineAnchor("src/old.kt", "src/new.kt", oldLine = null, newLine = 11), "Rename?").getOrThrow()

    assertEquals("abc", thread.id)
    assertEquals(11, thread.notes.single().position?.newLine)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/v4/projects/1/merge_requests/12/discussions", request.path)
    val form = request.body.readUtf8().split("&").associate { pair ->
      val (key, value) = pair.split("=", limit = 2)
      URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }
    assertEquals("Rename?", form["body"])
    assertEquals("head333", form["position[head_sha]"])
    assertEquals("11", form["position[new_line]"])
    assertEquals(null, form["position[old_line]"])
  }

  @Test
  fun `a line that moved on explains what to do`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"400 Bad request - Note {:line_code=>[\"can't be blank\"]}"}"""))
    val error = repository.createDiffNote(instance, 1, 12, refs, DiffLineAnchor("a", "a", null, 1), "Hi").exceptionOrNull()
    assertTrue(error is GitLabApiException && error.message!!.contains("refresh and try again"))
  }
}
