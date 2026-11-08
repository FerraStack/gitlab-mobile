package com.cboyxd.gitlab

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.api.normalizeBaseUrl
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.data.model.MergeOptions
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.data.repository.contentRangeStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GitLabRepositoryTest {

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
      authKind = GitLabAuth.KIND_PAT
    )
  }

  @After
  fun tearDown() {
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    server.shutdown()
  }

  @Test
  fun `commit history is bounded to one page even when the server has more`() = runBlocking {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val page = request.requestUrl?.queryParameter("page")?.toInt() ?: 1
        return MockResponse()
          .setBody("""[{"id":"sha$page","title":"commit $page"}]""")
          .addHeader("Content-Type", "application/json")
          .addHeader("X-Next-Page", (page + 1).toString())
      }
    }

    val commits = repository.getRepositoryCommits(instance, 1, "main").getOrThrow()

    assertEquals(1, commits.size)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `explicit nulls fall back to model defaults instead of failing the list`() = runBlocking {
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json").setBody(
        """[
          {"id":1,"project":null,"author":null,"action_name":"assigned","target_type":"Issue",
           "target":{"iid":7,"title":"Fix login","project_id":3,"state":"opened"},"body":null,"state":"pending","created_at":null},
          {"id":2,"author":{"id":5,"username":"dev","name":null},"action_name":"mentioned","state":"done"}
        ]"""
      )
    )
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("[]"))

    val todos = repository.getTodos(instance).getOrThrow()

    assertEquals(listOf(1L, 2L), todos.map { it.id }.sorted())
    val first = todos.first { it.id == 1L }
    assertEquals("", first.author.username)
    assertEquals(7L, first.target?.iid)
    assertEquals("", todos.first { it.id == 2L }.author.name)
  }

  @Test
  fun `http errors carry a message that says how to recover`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(429).setBody("Retry later"))

    val error = repository.getProject(instance, 1).exceptionOrNull()

    assertTrue(error is GitLabApiException)
    assertEquals(429, (error as GitLabApiException).code)
  }

  @Test
  fun `html answers from a proxy become a readable error`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "text/html").setBody("<html>Sign in</html>"))

    val error = repository.getProject(instance, 1).exceptionOrNull()

    assertTrue(error is GitLabApiException)
    assertTrue(error!!.message!!.contains("GitLab server"))
  }

  @Test
  fun `cancellation propagates instead of turning into a failure`() = runBlocking {
    server.enqueue(
      MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody("{}")
        .setHeadersDelay(5, TimeUnit.SECONDS)
    )
    val call = async { repository.getProject(instance, 1) }
    Thread.sleep(200)
    call.cancel()

    val outcome = runCatching { withTimeout(2_000) { call.await() } }

    assertTrue(outcome.exceptionOrNull() is CancellationException)
  }

  @Test
  fun `file content is read past the first buffer segment`() = runBlocking {
    val body = "x".repeat(50_000)
    server.enqueue(MockResponse().setBody(body))

    val content = repository.getFileContent(instance, 1, "big.txt", "main").getOrThrow()

    assertEquals(50_000, content.length)
  }

  @Test
  fun `star on an already starred project is not an error`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(304))
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json")
        .setBody("""{"id":9,"name":"app","star_count":3}""")
    )

    val project = repository.setProjectStarred(instance, 9, starred = true).getOrThrow()

    assertTrue(project.isStarred)
    assertEquals(3, project.starCount)
  }

  @Test
  fun `without a token the project feed is popular public projects`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""[{"id":1,"name":"gitlab"}]"""))

    val projects = repository.getProjects(instance.copy(token = "", authKind = GitLabAuth.KIND_PUBLIC)).getOrThrow()

    assertEquals(listOf(1L), projects.map { it.id })
    val url = server.takeRequest().requestUrl!!
    assertEquals("public", url.queryParameter("visibility"))
    assertEquals("star_count", url.queryParameter("order_by"))
    assertEquals(null, url.queryParameter("membership"))
  }

  @Test
  fun `a pasted project path opens that project first`() = runBlocking {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val body = if (request.path!!.startsWith("/api/v4/projects/gitlab-org%2Fgitlab")) """{"id":7,"name":"gitlab"}"""
        else """[{"id":8,"name":"gitlab-runner"},{"id":7,"name":"gitlab"}]"""
        return MockResponse().addHeader("Content-Type", "application/json").setBody(body)
      }
    }

    val projects = repository.getProjects(instance.copy(token = ""), search = "gitlab-org/gitlab").getOrThrow()

    assertEquals(listOf(7L, 8L), projects.map { it.id })
  }

  @Test
  fun `job logs keep only the tail`() = runBlocking {
    val log = "a".repeat(300_000) + "END"
    server.enqueue(MockResponse().setBody(log))

    val trace = repository.getJobTrace(instance, 1, 2).getOrThrow()

    assertTrue(trace.truncated)
    assertTrue(trace.text.endsWith("END"))
    assertTrue(trace.text.length <= 256_000)
    assertEquals("bytes=-256000", server.takeRequest().getHeader("Range"))
  }

  @Test
  fun `a ranged job log tail starts at a whole line`() = runBlocking {
    server.enqueue(
      MockResponse().setResponseCode(206)
        .addHeader("Content-Range", "bytes 744000-744022/744023")
        .setBody("ial line\nstep 2\nEND\n"),
    )

    val trace = repository.getJobTrace(instance, 1, 2).getOrThrow()

    assertTrue(trace.truncated)
    assertEquals("step 2\nEND\n", trace.text)
  }

  @Test
  fun `a short log answered with its full range is complete`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-11/12").setBody("step 1\nEND\n"))

    val trace = repository.getJobTrace(instance, 1, 2).getOrThrow()

    assertTrue(!trace.truncated)
    assertEquals("step 1\nEND\n", trace.text)
  }

  @Test
  fun `an empty log that can't satisfy the range is just empty`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(416))

    val trace = repository.getJobTrace(instance, 1, 2).getOrThrow()

    assertEquals("", trace.text)
    assertTrue(!trace.truncated)
  }

  @Test
  fun `content-range starts are parsed`() {
    assertEquals(744000L, contentRangeStart("bytes 744000-744022/744023"))
    assertEquals(0L, contentRangeStart("bytes 0-11/12"))
    assertEquals(null, contentRangeStart(null))
    assertEquals(null, contentRangeStart("garbage"))
  }

  @Test
  fun `languages come back largest first`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"Ruby":20.5,"Go":70.1,"Shell":9.4}"""))

    val languages = repository.getLanguages(instance, 1).getOrThrow()

    assertEquals(listOf("Go", "Ruby", "Shell"), languages.map { it.first })
  }

  @Test
  fun `missing approvals api is not an error`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404))

    val approvals = repository.getMergeRequestApprovals(instance, 1, 2).getOrThrow()

    assertEquals(null, approvals)
  }

  @Test
  fun `hidden contributor stats read as an empty list`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(403))

    assertEquals(emptyList<Any>(), repository.getContributors(instance, 1).getOrThrow())
  }

  @Test
  fun `detailed labels keep names and carry colors`() = runBlocking {
    server.enqueue(
      MockResponse().addHeader("Content-Type", "application/json").setBody(
        """[{"id":1,"iid":2,"project_id":3,"title":"t","labels":[{"id":9,"name":"bug","color":"#dc143c","text_color":"#FFFFFF"}]}]"""
      )
    )

    val issue = repository.getProjectIssues(instance, 3).getOrThrow().items.single()

    assertEquals(listOf("bug"), issue.labels)
    assertEquals("#dc143c", issue.labelDetails.single().color)
    assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("with_labels_details"))
  }

  @Test
  fun `api root urls are normalised`() {
    assertEquals("https://gitlab.example.com/", normalizeBaseUrl("https://gitlab.example.com/api/v4/"))
    assertEquals("https://gitlab.example.com/gitlab/", normalizeBaseUrl("https://gitlab.example.com/gitlab/api/v4?x=1#top"))
  }

  @Test
  fun `personal access tokens from current gitlab are recognised`() {
    assertEquals("glpat-new", GitLabAuth.createdAccessToken("""{"new_token":"glpat-new","active_access_tokens":[]}"""))
    assertEquals("glpat-old", GitLabAuth.createdAccessToken("""{"token":"glpat-old"}"""))
  }

  @Test
  fun `merging pins the commit the user reviewed`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"id":1,"iid":12,"project_id":1,"title":"Fix launch","state":"merged","sha":"abc123"}""").addHeader("Content-Type", "application/json"))
    val mr = GitLabMergeRequest(id = 1, iid = 12, projectId = 1, title = "Fix launch", sha = "abc123")
    assertEquals("merged", repository.mergeMergeRequest(instance, mr).getOrThrow().state)
    val request = server.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("/api/v4/projects/1/merge_requests/12/merge", request.path)
    assertTrue(request.body.readUtf8().contains("sha=abc123"))
  }

  @Test
  fun `a branch that moved after the review is refused, not merged`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"SHA does not match HEAD of source branch"}"""))
    val error = repository.mergeMergeRequest(instance, GitLabMergeRequest(id = 1, iid = 12, projectId = 1, title = "Fix launch", sha = "old")).exceptionOrNull()
    assertTrue(error is GitLabApiException && error.code == 409 && error.message!!.contains("source branch changed"))
  }

  @Test
  fun `merge requests without a known head merge without a sha`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"id":1,"iid":12,"project_id":1,"title":"Fix launch","state":"merged"}""").addHeader("Content-Type", "application/json"))
    repository.mergeMergeRequest(instance, GitLabMergeRequest(id = 1, iid = 12, projectId = 1, title = "Fix launch")).getOrThrow()
    assertTrue(!server.takeRequest().body.readUtf8().contains("sha="))
  }

  @Test
  fun `project lists ask the server for one state and one page`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").addHeader("X-Next-Page", "3")
      .setBody("""[{"id":1,"iid":2,"project_id":3,"title":"t","state":"closed"}]"""))

    val page = repository.getProjectIssues(instance, 3, state = "closed", page = 2).getOrThrow()

    assertEquals(3, page.nextPage)
    assertEquals(1, page.items.size)
    val url = server.takeRequest().requestUrl!!
    assertEquals("closed", url.queryParameter("state"))
    assertEquals("2", url.queryParameter("page"))
  }

  @Test
  fun `the last page has no next page`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("[]"))
    assertEquals(null, repository.getProjectMergeRequests(instance, 3, state = "merged").getOrThrow().nextPage)
    assertEquals("merged", server.takeRequest().requestUrl!!.queryParameter("state"))
  }

  @Test
  fun `merge request counts come per state from X-Total`() = runBlocking {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val total = when (request.requestUrl!!.queryParameter("state")) {
          "opened" -> "4012"
          "merged" -> "18000"
          "closed" -> "77"
          else -> null // GitLab leaves X-Total out for very large results.
        }
        return MockResponse().addHeader("Content-Type", "application/json").setBody("[]")
          .apply { if (total != null) addHeader("X-Total", total) }
      }
    }

    val counts = repository.getProjectMergeRequestStateCounts(instance, 3).getOrThrow()

    assertEquals(mapOf("opened" to 4012, "merged" to 18000, "closed" to 77), counts)
  }

  @Test
  fun `issue counts come from issue statistics`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json")
      .setBody("""{"statistics":{"counts":{"all":5000,"opened":4012,"closed":988}}}"""))

    val counts = repository.getProjectIssueStateCounts(instance, 3).getOrThrow()

    assertEquals(mapOf("opened" to 4012, "closed" to 988, "all" to 5000), counts)
  }

  @Test
  fun `approving pins the reviewed commit`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"approved":true,"approved_by":[{"user":{"id":1,"username":"tanuki"}}]}"""))
    val mr = GitLabMergeRequest(id = 1, iid = 12, projectId = 1, title = "Fix launch", sha = "abc123")

    val approvals = repository.approveMergeRequest(instance, mr).getOrThrow()

    assertEquals("tanuki", approvals.approvedBy.single().user.username)
    val request = server.takeRequest()
    assertEquals("/api/v4/projects/1/merge_requests/12/approve", request.path)
    assertTrue(request.body.readUtf8().contains("sha=abc123"))
  }

  @Test
  fun `approval refusals explain themselves`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"401 Unauthorized"}"""))
    val error = repository.approveMergeRequest(instance, GitLabMergeRequest(id = 1, iid = 12, projectId = 1, title = "t")).exceptionOrNull()
    assertTrue(error is GitLabApiException && error.message!!.contains("can't approve"))
  }

  @Test
  fun `revoking an approval that isn't there is fine`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404))
    assertTrue(repository.unapproveMergeRequest(instance, GitLabMergeRequest(id = 1, iid = 12, projectId = 1, title = "t")).isSuccess)
    assertEquals("/api/v4/projects/1/merge_requests/12/unapprove", server.takeRequest().path)
  }

  @Test
  fun `replies go to their thread`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"id":9,"body":"Done"}"""))
    val note = repository.replyToDiscussion(instance, 1, 12, "abc123", "Done").getOrThrow()
    assertEquals(9L, note.id)
    val request = server.takeRequest()
    assertEquals("/api/v4/projects/1/merge_requests/12/discussions/abc123/notes", request.path)
    assertEquals("body=Done", request.body.readUtf8())
  }

  @Test
  fun `threads are resolved and reopened`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json")
      .setBody("""{"id":"abc123","notes":[{"id":5,"resolvable":true,"resolved":true}]}"""))
    val thread = repository.resolveDiscussion(instance, 1, 12, "abc123", resolved = true).getOrThrow()
    assertEquals(true, thread.notes.single().resolved)
    val request = server.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("/api/v4/projects/1/merge_requests/12/discussions/abc123?resolved=true", request.path)
  }

  @Test
  fun `merge options are sent, and unset ones keep the merge request's own settings`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"id":1,"iid":12,"project_id":1,"title":"t","state":"merged"}"""))
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"id":1,"iid":12,"project_id":1,"title":"t","state":"opened","merge_when_pipeline_succeeds":true}"""))
    val mr = GitLabMergeRequest(id = 1, iid = 12, projectId = 1, title = "t", sha = "abc")

    repository.mergeMergeRequest(instance, mr, MergeOptions(deleteSourceBranch = true, squash = true)).getOrThrow()
    val auto = repository.mergeMergeRequest(instance, mr, MergeOptions(whenPipelineSucceeds = true)).getOrThrow()

    val first = server.takeRequest().body.readUtf8()
    assertTrue(first.contains("should_remove_source_branch=true"))
    assertTrue(first.contains("squash=true"))
    assertTrue(!first.contains("merge_when_pipeline_succeeds"))
    val second = server.takeRequest().body.readUtf8()
    assertTrue(second.contains("merge_when_pipeline_succeeds=true"))
    // No longer forces "keep the branch" over the author's choice.
    assertTrue(!second.contains("should_remove_source_branch"))
    assertEquals(true, auto.mergeWhenPipelineSucceeds)
  }

  @Test
  fun `auto-merge can be canceled`() = runBlocking {
    server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody("""{"id":1,"iid":12,"project_id":1,"title":"t","state":"opened","merge_when_pipeline_succeeds":false}"""))
    val mr = repository.cancelAutoMerge(instance, GitLabMergeRequest(id = 1, iid = 12, projectId = 1, title = "t")).getOrThrow()
    assertEquals(false, mr.mergeWhenPipelineSucceeds)
    assertEquals("/api/v4/projects/1/merge_requests/12/cancel_merge_when_pipeline_succeeds", server.takeRequest().path)
  }
}
