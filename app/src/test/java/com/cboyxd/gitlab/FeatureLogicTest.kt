package com.cboyxd.gitlab.ui.viewmodel

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.DiffLineAnchor
import com.cboyxd.gitlab.data.model.GitLabDiffRefs
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.data.model.GitLabUser
import com.cboyxd.gitlab.data.model.MergeOptions
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Unit tests for [ReviewFeature], [PipelineFeature], and [ExploreFeature.handleLink] (#62). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class FeatureLogicTest {

  private lateinit var server: MockWebServer
  private lateinit var instance: GitLabInstance
  private val failAction = AtomicBoolean(false)
  private val mrState = java.util.concurrent.atomic.AtomicReference("opened")
  private val mrTitle = java.util.concurrent.atomic.AtomicReference("Draft: Fix launch")
  private val mrAutoMerge = AtomicBoolean(true)
  private val pipelineStatus = java.util.concurrent.atomic.AtomicReference("running")
  private val recordedRequests = Collections.synchronizedList(mutableListOf<String>())

  @Before
  fun setUp() {
    pipelineStatus.set("running")
    val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    val certificates = HandshakeCertificates.Builder()
      .heldCertificate(certificate)
      .addTrustedCertificate(certificate.certificate)
      .build()
    server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val method = request.method.orEmpty()
        val url = request.requestUrl!!
        val path = url.encodedPath
        recordedRequests += "$method $path"
        if (failAction.get() && method != "GET") {
          return when {
            path.endsWith("/merge") -> MockResponse().setResponseCode(409).setBody("""{"message":"Conflict"}""")
            path.endsWith("/pipeline") -> MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}""")
            else -> MockResponse().setResponseCode(500).setBody("""{"message":"Server error"}""")
          }
        }
        return when {
          // Merge request endpoints
          method == "GET" && path.endsWith("/projects/1/merge_requests/12") ->
            json("""{"id":212,"iid":12,"project_id":1,"title":"${mrTitle.get()}","state":"${mrState.get()}","source_branch":"feat","target_branch":"main","sha":"headsha","merge_when_pipeline_succeeds":${mrAutoMerge.get()},"diff_refs":{"base_sha":"b","start_sha":"s","head_sha":"h"}}""")
          method == "PUT" && path.endsWith("/projects/1/merge_requests/12/merge") -> {
            mrState.set("merged")
            json("""{"id":212,"iid":12,"project_id":1,"title":"${mrTitle.get()}","state":"merged","source_branch":"feat","target_branch":"main"}""")
          }
          method == "PUT" && path.endsWith("/projects/1/merge_requests/12/rebase") ->
            MockResponse().setResponseCode(202).setBody("""{"rebase_in_progress":true}""")
          method == "POST" && path.endsWith("/projects/1/merge_requests/12/cancel_merge_when_pipeline_succeeds") -> {
            mrAutoMerge.set(false)
            json("""{"id":212,"iid":12,"project_id":1,"title":"${mrTitle.get()}","state":"opened","merge_when_pipeline_succeeds":false}""")
          }
          method == "PUT" && path.endsWith("/projects/1/merge_requests/12") -> {
            val body = request.body.readUtf8()
            if (body.contains("state_event=close")) mrState.set("closed")
            if (body.contains("state_event=reopen")) mrState.set("opened")
            if (body.contains("title=")) mrTitle.set("Fix launch")
            json("""{"id":212,"iid":12,"project_id":1,"title":"${mrTitle.get()}","state":"${mrState.get()}"}""")
          }
          method == "POST" && path.endsWith("/projects/1/merge_requests/12/approve") ->
            json("""{"approved":true,"approved_by":[{"user":{"id":1,"username":"dev","name":"Dev"}}]}""")
          method == "POST" && path.endsWith("/projects/1/merge_requests/12/unapprove") ->
            MockResponse().setResponseCode(201).setBody("{}")
          method == "GET" && path.endsWith("/projects/1/merge_requests/12/approvals") ->
            json("""{"approved":true,"approvals_left":0,"approved_by":[{"user":{"id":1,"username":"dev","name":"Dev"}}]}""")
          method == "POST" && path.endsWith("/projects/1/merge_requests/12/notes") ->
            json("""{"id":501,"body":"LGTM","author":{"id":1,"username":"dev","name":"Dev"},"created_at":"2026-11-15T08:00:00Z"}""")
          method == "POST" && path.endsWith("/projects/1/merge_requests/12/discussions") ->
            json("""{"id":"disc-1","individual_note":false,"notes":[{"id":601,"body":"Inline","resolvable":true,"resolved":false,"author":{"id":1,"username":"dev","name":"Dev"},"created_at":"2026-11-15T08:00:00Z"}]}""")
          method == "POST" && path.endsWith("/projects/1/merge_requests/12/discussions/disc-1/notes") ->
            json("""{"id":602,"body":"Reply","resolvable":true,"resolved":false,"author":{"id":1,"username":"dev","name":"Dev"},"created_at":"2026-11-15T08:01:00Z"}""")
          method == "PUT" && path.endsWith("/projects/1/merge_requests/12/discussions/disc-1") -> {
            val resolved = url.queryParameter("resolved") == "true"
            json("""{"id":"disc-1","individual_note":false,"notes":[{"id":601,"body":"Inline","resolvable":true,"resolved":$resolved,"author":{"id":1,"username":"dev","name":"Dev"},"created_at":"2026-11-15T08:00:00Z"}]}""")
          }
          path.endsWith("/diffs") || path.endsWith("/discussions") || path.endsWith("/commits") || path.endsWith("/pipelines") || path.endsWith("/labels") || path.contains("/award_emoji") ->
            json("[]")
          // Pipeline & Job endpoints
          method == "POST" && path.endsWith("/projects/1/pipeline") -> {
            pipelineStatus.set("running")
            json("""{"id":99,"project_id":1,"status":"created","ref":"${url.queryParameter("ref") ?: "main"}"}""")
          }
          method == "GET" && path.endsWith("/projects/1/pipelines/99") ->
            json("""{"id":99,"project_id":1,"status":"${pipelineStatus.get()}","ref":"main"}""")
          method == "GET" && path.endsWith("/projects/1/pipelines/99/jobs") ->
            json("""[{"id":42,"name":"deploy","stage":"deploy","status":"manual"},{"id":41,"name":"test","stage":"test","status":"running"}]""")
          method == "POST" && path.endsWith("/projects/1/pipelines/99/cancel") -> {
            pipelineStatus.set("canceled")
            json("""{"id":99,"project_id":1,"status":"canceled","ref":"main"}""")
          }
          method == "POST" && path.endsWith("/projects/1/jobs/42/play") ->
            json("""{"id":42,"name":"deploy","stage":"deploy","status":"pending"}""")
          method == "POST" && path.endsWith("/projects/1/jobs/41/cancel") ->
            json("""{"id":41,"name":"test","stage":"test","status":"canceled"}""")
          method == "POST" && path.endsWith("/projects/1/jobs/41/retry") ->
            json("""{"id":43,"name":"test","stage":"test","status":"pending"}""")
          method == "GET" && path.endsWith("/projects/1/jobs/42") ->
            json("""{"id":42,"name":"deploy","stage":"deploy","status":"success"}""")
          method == "GET" && path.endsWith("/projects/1/jobs/41") ->
            json("""{"id":41,"name":"test","stage":"test","status":"canceled"}""")
          method == "GET" && path.endsWith("/trace") -> MockResponse().setBody("ok\n")
          // Explore link lookup
          path.endsWith("/api/v4/projects/team%2Frocket") || path.endsWith("/gitlab/api/v4/projects/team%2Frocket") ->
            json("""{"id":77,"name":"rocket","path_with_namespace":"team/rocket","default_branch":"main","web_url":"https://gitlab.example/team/rocket"}""")
          path.endsWith("/api/v4/projects/missing%2Frepo") ->
            MockResponse().setResponseCode(404)
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
      id = "test-feat-" + System.nanoTime(),
      name = "Test",
      url = server.url("/").toString(),
      token = "glpat-test",
      username = "dev",
      authKind = GitLabAuth.KIND_PAT,
    )
  }

  @After
  fun tearDown() {
    GitLabApiClient.clientOverride = null
    GitLabApiClient.clearCache()
    server.shutdown()
  }

  private fun json(body: String) = MockResponse().addHeader("Content-Type", "application/json").setBody(body)

  private class Harness(
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val inst: () -> GitLabInstance,
  ) {
    val foreground = MutableStateFlow(true)
    val state = MutableStateFlow(GitLabUiState(activeInstance = inst()))
    val messages = mutableListOf<String>()
    val errors = mutableListOf<String>()
    val navigated = mutableListOf<ScreenDestination>()
    val externalUrls = mutableListOf<String>()
    val completedCalls = AtomicInteger(0)

    val host = object : SessionHost {
      override val repository = GitLabRepository()
      override val session = CoroutineScope(dispatcher + SupervisorJob())
      override val state = this@Harness.state
      override val foreground: StateFlow<Boolean> = this@Harness.foreground.asStateFlow()
      override suspend fun <T> api(block: suspend (GitLabInstance) -> Result<T>): Result<T> {
        val r = block(inst())
        completedCalls.incrementAndGet()
        return r
      }
      override fun notify(text: String) { messages += text }
      override fun notifyError(text: String, retryAction: (() -> Unit)?) { errors += text }
      override fun navigateTo(destination: ScreenDestination) { navigated += destination }
      override fun openExternal(url: String) { externalUrls += url }
    }
  }

  private suspend fun TestScope.awaitCalls(calls: AtomicInteger, expected: Int) {
    val deadline = System.currentTimeMillis() + 5_000L
    while (calls.get() < expected && System.currentTimeMillis() < deadline) {
      runCurrent()
      withContext(Dispatchers.IO) { Thread.sleep(10) }
    }
    runCurrent()
    assertTrue("Expected >= $expected calls, got ${calls.get()}", calls.get() >= expected)
  }

  private suspend fun TestScope.awaitCondition(description: String = "condition", predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000L
    while (!predicate() && System.currentTimeMillis() < deadline) {
      runCurrent()
      withContext(Dispatchers.IO) { Thread.sleep(10) }
    }
    runCurrent()
    assertTrue("Timed out waiting for $description", predicate())
  }

  private fun sampleMr(draft: Boolean = true) = GitLabMergeRequest(
    id = 212,
    iid = 12,
    projectId = 1,
    title = if (draft) "Draft: Fix launch" else "Fix launch",
    state = "opened",
    sourceBranch = "feat",
    targetBranch = "main",
    sha = "headsha",
    draft = draft,
    mergeWhenPipelineSucceeds = true,
    diffRefs = GitLabDiffRefs(baseSha = "b", startSha = "s", headSha = "h"),
    author = GitLabUser(id = 1, username = "dev", name = "Dev"),
  )

  @Test
  fun `ReviewFeature covers merge, close, markReady, rebase, cancelAutoMerge, approvals, and discussions`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val h = Harness(dispatcher) { instance }
    val review = ReviewFeature(h.host) {}

    // open() seeds state and navigates; openInPane() seeds and loads without navigating
    review.open(sampleMr())
    assertEquals(listOf(ScreenDestination.MergeRequestDetail(1, 12)), h.navigated)
    h.navigated.clear()

    review.openInPane(sampleMr())
    assertTrue(h.navigated.isEmpty())
    awaitCondition("openInPane loads all 6 MR sections") {
      val d = h.state.value.mergeRequestDetail
      d.mergeRequest.loaded && !d.mergeRequest.loading &&
        d.diffs.loaded && !d.diffs.loading &&
        d.discussions.loaded && !d.discussions.loading &&
        d.commits.loaded && !d.commits.loading &&
        d.pipelines.loaded && !d.pipelines.loading &&
        d.approvals.loaded && !d.approvals.loading
    }

    // markReady drops Draft prefix
    review.markReady()
    awaitCondition("markReady") { !h.state.value.mergeRequestDetail.busy && h.messages.contains("!12 is ready") }
    assertEquals("Fix launch", h.state.value.mergeRequestDetail.mergeRequest.data?.title)

    // cancelAutoMerge clears mergeWhenPipelineSucceeds
    review.cancelAutoMerge()
    awaitCondition("cancelAutoMerge") { !h.state.value.mergeRequestDetail.busy && h.messages.contains("Auto-merge canceled") }
    assertEquals(false, h.state.value.mergeRequestDetail.mergeRequest.data?.mergeWhenPipelineSucceeds)

    // setApproved(true) & (false)
    review.setApproved(true)
    awaitCondition("setApproved(true)") { !h.state.value.mergeRequestDetail.busy && h.messages.contains("Approved !12") }
    assertEquals(true, h.state.value.mergeRequestDetail.approvals.data?.approved)

    review.setApproved(false)
    awaitCondition("setApproved(false)") { !h.state.value.mergeRequestDetail.busy && h.messages.contains("Approval revoked") }

    // postNote, commentOnLine, reply, setResolved
    var noteResult: String? = "unset"
    review.postNote("LGTM") { noteResult = it }
    awaitCondition("postNote") { noteResult != "unset" }
    assertNull(noteResult)
    assertEquals(1, h.state.value.mergeRequestDetail.notes.data.size)

    var diffCommentResult: String? = "unset"
    review.commentOnLine(DiffLineAnchor("a.kt", "a.kt", null, 5), "Inline") { diffCommentResult = it }
    awaitCondition("commentOnLine") { diffCommentResult != "unset" }
    assertNull(diffCommentResult)
    assertTrue(h.state.value.mergeRequestDetail.discussions.data.any { it.id == "disc-1" })

    var replyResult: String? = "unset"
    review.reply("disc-1", "Reply") { replyResult = it }
    awaitCondition("reply") { replyResult != "unset" }
    assertNull(replyResult)
    assertEquals(2, h.state.value.mergeRequestDetail.discussions.data.first { it.id == "disc-1" }.notes.size)

    review.setResolved("disc-1", true)
    awaitCondition("setResolved") {
      !h.state.value.mergeRequestDetail.resolvingThreads.contains("disc-1") &&
        h.state.value.mergeRequestDetail.discussions.data.first { it.id == "disc-1" }.notes.first().resolved == true
    }

    // rebase delays 4s then reloads MR
    review.rebase()
    awaitCondition("rebase started") { h.messages.any { it.startsWith("Rebasing !12") } }
    assertTrue(h.state.value.mergeRequestDetail.busy)
    advanceTimeBy(4_100L)
    awaitCondition("rebase settled") { !h.state.value.mergeRequestDetail.busy }

    // setClosed
    review.setClosed(true)
    awaitCondition("setClosed") { !h.state.value.mergeRequestDetail.busy && h.state.value.mergeRequestDetail.mergeRequest.data?.state == "closed" }
    assertEquals("closed", h.state.value.mergeRequestDetail.mergeRequest.data?.state)

    // merge failure (409) resets busy and records error; merge success sets merged
    failAction.set(true)
    review.merge(MergeOptions(squash = true))
    awaitCondition("merge failure") { !h.state.value.mergeRequestDetail.busy && h.errors.isNotEmpty() }
    assertFalse(h.state.value.mergeRequestDetail.busy)
    assertEquals(1, h.errors.size)

    failAction.set(false)
    review.merge(MergeOptions(squash = true))
    awaitCondition("merge success") { !h.state.value.mergeRequestDetail.busy && h.messages.contains("Merged !12") }
    assertEquals("merged", h.state.value.mergeRequestDetail.mergeRequest.data?.state)
  }

  @Test
  fun `PipelineFeature covers runPipeline, job actions, pipeline cancel, and destination stop`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val h = Harness(dispatcher) { instance }
    val pipeline = PipelineFeature(h.host)

    // runPipeline failure returns message to callback and does not navigate
    failAction.set(true)
    var runError: String? = null
    pipeline.runPipeline(1, "main") { runError = it }
    awaitCondition("runPipeline error") { runError != null }
    assertTrue(runError!!.contains("can't run pipelines"))
    assertTrue(h.navigated.isEmpty())

    // runPipeline success opens PipelineDetail
    failAction.set(false)
    var runOk: String? = "pending"
    pipeline.runPipeline(1, "release") { runOk = it }
    awaitCondition("runPipeline ok") { runOk != "pending" }
    assertNull(runOk)
    assertEquals(listOf(ScreenDestination.PipelineDetail(1, 99)), h.navigated)

    // Load pipeline, play manual job, cancel running pipeline
    try {
      pipeline.loadPipeline(1, 99)
      awaitCondition("loadPipeline") {
        val pd = h.state.value.pipelineDetail
        pd.pipeline.loaded && !pd.pipeline.loading && pd.jobs.loaded && !pd.jobs.loading
      }
      assertEquals(2, h.state.value.pipelineDetail.jobs.data.size)

      pipeline.playJob(1, 42)
      awaitCondition("playJob") {
        h.messages.contains("Started deploy") &&
          !h.state.value.pipelineDetail.busy &&
          !h.state.value.pipelineDetail.pipeline.loading &&
          !h.state.value.pipelineDetail.jobs.loading
      }

      pipeline.cancelPipeline()
      awaitCondition("cancelPipeline") {
        !h.state.value.pipelineDetail.busy && h.messages.contains("Pipeline #99 canceled")
      }
      assertEquals("canceled", h.state.value.pipelineDetail.pipeline.data?.status)

      // Job actions: cancel & retry
      pipeline.loadJob(1, 41)
      awaitCondition("loadJob") {
        val jd = h.state.value.jobDetail
        jd.job.loaded && !jd.job.loading && jd.trace.loaded && !jd.trace.loading
      }
      pipeline.cancelJob()
      awaitCondition("cancelJob") {
        h.messages.contains("Canceled test") &&
          !h.state.value.jobDetail.busy &&
          !h.state.value.jobDetail.job.loading &&
          !h.state.value.pipelineDetail.pipeline.loading
      }

      pipeline.retryJob()
      awaitCondition("retryJob") {
        !h.state.value.jobDetail.busy && h.navigated.lastOrNull() == ScreenDestination.JobDetail(1, 43)
      }
      assertEquals(ScreenDestination.JobDetail(1, 43), h.navigated.last())
    } finally {
      pipeline.onDestinationChanged(ScreenDestination.MainTabs)
      runCurrent()
    }
  }

  @Test
  fun `ExploreFeature handleLink routes supported URLs, respects baseUrl prefix, and rejects others`() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val base = server.url("/gitlab").toString()
    val hostPrefixInstance = instance.copy(url = base)
    val h = Harness(dispatcher) { hostPrefixInstance }
    val explore = ExploreFeature(h.host)
    val hostAuthority = server.url("/").toString().trimEnd('/')

    // Cross-host and unsupported sub-routes return false immediately
    assertFalse(explore.handleLink("https://other.example/team/rocket/-/issues/1"))
    assertFalse(explore.handleLink("$hostAuthority/gitlab/team/rocket/-/settings/ci_cd"))
    assertFalse(explore.handleLink("$hostAuthority/gitlab/"))

    // Direct user route needs no project lookup
    assertTrue(explore.handleLink("$hostAuthority/gitlab/tanuki"))
    assertEquals(ScreenDestination.UserDetail(0, "tanuki"), h.navigated.removeAt(0))

    // Project route resolves team/rocket via /gitlab/api/v4/projects/team%2Frocket and caches id 77
    assertTrue(explore.handleLink("$hostAuthority/gitlab/team/rocket"))
    awaitCalls(h.completedCalls, 1)
    assertEquals(ScreenDestination.ProjectDetail(77), h.navigated.removeAt(0))

    // Subsequent sub-routes reuse cached projectId 77 without extra API lookups
    assertTrue(explore.handleLink("$hostAuthority/gitlab/team/rocket/-/issues/7"))
    assertTrue(explore.handleLink("$hostAuthority/gitlab/team/rocket/-/merge_requests/12"))
    assertTrue(explore.handleLink("$hostAuthority/gitlab/team/rocket/-/pipelines/99"))
    assertTrue(explore.handleLink("$hostAuthority/gitlab/team/rocket/-/jobs/55"))
    assertTrue(explore.handleLink("$hostAuthority/gitlab/team/rocket/-/commit/abc123"))
    assertTrue(explore.handleLink("$hostAuthority/gitlab/team/rocket/-/releases/v1.0%2Brc1"))
    assertTrue(explore.handleLink("$hostAuthority/gitlab/team/rocket/-/blob/main/src/App.kt"))

    assertEquals(
      listOf(
        ScreenDestination.IssueDetail(77, 7),
        ScreenDestination.MergeRequestDetail(77, 12),
        ScreenDestination.PipelineDetail(77, 99),
        ScreenDestination.JobDetail(77, 55),
        ScreenDestination.CommitDetail(77, "abc123"),
        ScreenDestination.ReleaseDetail(77, "v1.0+rc1"),
        ScreenDestination.FileViewer(77, "src/App.kt", "main"),
      ),
      h.navigated,
    )

    // Unknown project returns true immediately then falls back to openExternal on 404
    val missingUrl = "$hostAuthority/gitlab/missing/repo/-/issues/5"
    assertTrue(explore.handleLink(missingUrl))
    awaitCalls(h.completedCalls, 2)
    assertEquals(listOf(missingUrl), h.externalUrls)
  }
}
