package com.cboyxd.gitlab

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.model.GitLabIssueUpdate
import com.cboyxd.gitlab.data.model.GitLabMergeRequestUpdate
import com.cboyxd.gitlab.data.repository.GitLabRepository
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

class TriageSearchReactionsRepositoryTest {

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

  private fun json(body: String, code: Int = 200) = MockResponse()
    .setResponseCode(code)
    .addHeader("Content-Type", "application/json")
    .setBody(body)

  @Test
  fun `triageIssue sends PUT with assignees, labels and milestone`() = runBlocking {
    server.enqueue(
      json("""{"id":10,"iid":12,"project_id":7,"title":"Fix crash","assignees":[{"id":2,"username":"alice"}],"labels":["bug"],"milestone":{"id":5,"title":"v1.0"}}""")
    )

    val result = repository.triageIssue(
      instance,
      projectId = 7L,
      issueIid = 12L,
      update = GitLabIssueUpdate(
        assigneeIds = listOf(2L, 9L),
        addLabels = "bug",
        removeLabels = "triage",
        milestoneId = 5L,
      ),
    ).getOrThrow()

    val req = server.takeRequest()
    assertEquals("PUT", req.method)
    assertEquals("/api/v4/projects/7/issues/12", req.path)
    val body = req.body.readUtf8()
    assertTrue(body.contains("\"assignee_ids\":[2,9]"))
    assertTrue(body.contains("\"add_labels\":\"bug\""))
    assertTrue(body.contains("\"remove_labels\":\"triage\""))
    assertTrue(body.contains("\"milestone_id\":5"))
    assertEquals(12L, result.iid)
    assertEquals(5L, result.milestone?.id)
  }

  @Test
  fun `updateMergeRequest sends PUT with title, description, reviewers and milestone`() = runBlocking {
    server.enqueue(
      json("""{"id":20,"iid":34,"project_id":7,"title":"New MR title","description":"Updated","reviewers":[{"id":3,"username":"bob"}]}""")
    )

    val result = repository.updateMergeRequest(
      instance,
      projectId = 7L,
      mrIid = 34L,
      update = GitLabMergeRequestUpdate(
        title = "New MR title",
        description = "Updated",
        assigneeIds = listOf(1L),
        reviewerIds = listOf(3L),
        milestoneId = 0L,
      ),
    ).getOrThrow()

    val req = server.takeRequest()
    assertEquals("PUT", req.method)
    assertEquals("/api/v4/projects/7/merge_requests/34", req.path)
    val body = req.body.readUtf8()
    assertTrue(body.contains("\"title\":\"New MR title\""))
    assertTrue(body.contains("\"description\":\"Updated\""))
    assertTrue(body.contains("\"assignee_ids\":[1]"))
    assertTrue(body.contains("\"reviewer_ids\":[3]"))
    assertTrue(body.contains("\"milestone_id\":0"))
    assertEquals("New MR title", result.title)
  }

  @Test
  fun `project members and milestones endpoints return deduplicated lists`() = runBlocking {
    server.enqueue(json("""[{"id":1,"username":"alice"},{"id":1,"username":"alice"},{"id":2,"username":"bob"}]"""))
    server.enqueue(json("""[{"id":4,"title":"17.0","state":"active","due_date":"2026-12-01"}]"""))

    val members = repository.getProjectMembers(instance, 7L).getOrThrow()
    val milestones = repository.getProjectMilestones(instance, 7L).getOrThrow()

    assertEquals(listOf(1L, 2L), members.map { it.id })
    assertEquals(listOf("17.0"), milestones.map { it.title })
    assertEquals("/api/v4/projects/7/members/all?per_page=100&page=1", server.takeRequest().path)
    assertEquals("/api/v4/projects/7/milestones?state=active&per_page=100&page=1", server.takeRequest().path)
  }

  @Test
  fun `searchBlobs, searchCommits and searchNotes support global and project scopes`() = runBlocking {
    server.enqueue(json("""[{"basename":"Main","path":"src/Main.kt","filename":"Main.kt","ref":"main","startline":42,"project_id":7,"data":"fun main()"}]"""))
    server.enqueue(json("""[{"id":"abc12345","short_id":"abc12345","title":"Fix search","author_name":"Dev","project_id":7}]"""))
    server.enqueue(json("""[{"id":88,"body":"Found it","author":{"id":1,"username":"dev"},"noteable_type":"Issue","noteable_iid":9,"project_id":0}]"""))

    val blobs = repository.searchBlobs(instance, "main").getOrThrow()
    val commits = repository.searchCommits(instance, "search", projectId = 7L).getOrThrow()
    val notes = repository.searchNotes(instance, "Found", projectId = 7L).getOrThrow()

    assertEquals("src/Main.kt", blobs.single().path)
    assertEquals(7L, commits.single().projectId)
    assertEquals(7L, notes.single().projectId)
    assertEquals("/api/v4/search?search=main&scope=blobs&per_page=30", server.takeRequest().path)
    assertEquals("/api/v4/projects/7/search?search=search&scope=commits&per_page=30", server.takeRequest().path)
    assertEquals("/api/v4/projects/7/search?search=Found&scope=notes&per_page=30", server.takeRequest().path)
  }

  @Test
  fun `getNamespace encodes nested path and getSnippet handles top-level and project snippets`() = runBlocking {
    server.enqueue(json("""{"id":55,"name":"Sub Group","path":"sub","full_path":"parent/sub","kind":"group"}"""))
    server.enqueue(json("""{"id":12,"title":"Config","file_name":"config.yml"}"""))
    server.enqueue(MockResponse().setResponseCode(200).setBody("key: value"))
    server.enqueue(json("""{"id":15,"title":"Helper","file_name":"helper.kt"}"""))

    val ns = repository.getNamespace(instance, "parent/sub").getOrThrow()
    val topSnippet = repository.getSnippet(instance, projectId = 0L, snippetId = 12L).getOrThrow()
    val raw = repository.getSnippetRaw(instance, projectId = 0L, snippetId = 12L).getOrThrow()
    val projectSnippet = repository.getSnippet(instance, projectId = 7L, snippetId = 15L).getOrThrow()

    assertEquals("group", ns.kind)
    assertEquals("parent/sub", ns.fullPath)
    assertEquals("Config", topSnippet.title)
    assertEquals("key: value", raw)
    assertEquals("Helper", projectSnippet.title)
    assertEquals("/api/v4/namespaces/parent%2Fsub", server.takeRequest().path)
    assertEquals("/api/v4/snippets/12", server.takeRequest().path)
    assertEquals("/api/v4/snippets/12/raw", server.takeRequest().path)
    assertEquals("/api/v4/projects/7/snippets/15", server.takeRequest().path)
  }

  @Test
  fun `award emoji endpoints list, add and delete reactions on issues, MRs and notes`() = runBlocking {
    server.enqueue(json("""[{"id":1,"name":"thumbsup","user":{"id":10,"username":"alice"}}]"""))
    server.enqueue(json("""{"id":2,"name":"rocket","user":{"id":10,"username":"alice"}}"""))
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(json("""[{"id":3,"name":"eyes","user":{"id":11,"username":"bob"}}]"""))
    server.enqueue(json("""{"id":4,"name":"heart","user":{"id":10,"username":"alice"}}"""))
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(json("""{"id":5,"name":"tada","user":{"id":10,"username":"alice"}}"""))
    server.enqueue(MockResponse().setResponseCode(204))

    val issueAwards = repository.getIssueAwardEmojis(instance, 7L, 12L).getOrThrow()
    val addedIssue = repository.addIssueAwardEmoji(instance, 7L, 12L, "rocket").getOrThrow()
    repository.deleteIssueAwardEmoji(instance, 7L, 12L, addedIssue.id).getOrThrow()

    val mrAwards = repository.getMergeRequestAwardEmojis(instance, 7L, 34L).getOrThrow()
    val addedMrNote = repository.addMergeRequestNoteAwardEmoji(instance, 7L, 34L, 99L, "heart").getOrThrow()
    repository.deleteMergeRequestNoteAwardEmoji(instance, 7L, 34L, 99L, addedMrNote.id).getOrThrow()

    val addedIssueNote = repository.addIssueNoteAwardEmoji(instance, 7L, 12L, 88L, "tada").getOrThrow()
    repository.deleteIssueNoteAwardEmoji(instance, 7L, 12L, 88L, addedIssueNote.id).getOrThrow()

    assertEquals("thumbsup", issueAwards.single().name)
    assertEquals("rocket", addedIssue.name)
    assertEquals("eyes", mrAwards.single().name)
    assertEquals("/api/v4/projects/7/issues/12/award_emoji?per_page=100", server.takeRequest().path)
    val postIssue = server.takeRequest()
    assertEquals("POST", postIssue.method)
    assertEquals("/api/v4/projects/7/issues/12/award_emoji", postIssue.path)
    assertTrue(postIssue.body.readUtf8().contains("name=rocket"))
    val delIssue = server.takeRequest()
    assertEquals("DELETE", delIssue.method)
    assertEquals("/api/v4/projects/7/issues/12/award_emoji/2", delIssue.path)
    assertEquals("/api/v4/projects/7/merge_requests/34/award_emoji?per_page=100", server.takeRequest().path)
    assertEquals("/api/v4/projects/7/merge_requests/34/notes/99/award_emoji", server.takeRequest().path)
    assertEquals("/api/v4/projects/7/merge_requests/34/notes/99/award_emoji/4", server.takeRequest().path)
    assertEquals("/api/v4/projects/7/issues/12/notes/88/award_emoji", server.takeRequest().path)
    assertEquals("/api/v4/projects/7/issues/12/notes/88/award_emoji/5", server.takeRequest().path)
  }
}
