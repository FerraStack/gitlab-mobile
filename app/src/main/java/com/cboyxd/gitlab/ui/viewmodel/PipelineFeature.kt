package com.cboyxd.gitlab.ui.viewmodel

import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.model.GitLabJob
import com.cboyxd.gitlab.data.model.GitLabPipeline
import com.cboyxd.gitlab.data.repository.JobTrace
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

private val ACTIVE = setOf("running", "pending", "created", "preparing", "waiting_for_resource", "scheduled", "canceling")
private const val POLL_MS = 8_000L

/** When a running pipeline or job refreshes next. */
internal object LivePolling {
  const val INTERVAL = POLL_MS
  const val MAX_BACKOFF = 60_000L

  /**
   * Delay before the next refresh, or null to stop. A finished pipeline or job stops; a failed
   * request (offline, timeout, 5xx, 429) keeps the last known state and retries with backoff as
   * long as that state was still running. Client errors such as 401 or 404 won't fix themselves.
   * [failures] counts consecutive failed refreshes, including this one.
   */
  fun next(status: Result<String?>, lastKnownStatus: String?, failures: Int): Long? {
    val error = status.exceptionOrNull() ?: return if (status.getOrNull() in ACTIVE) INTERVAL else null
    if (error is GitLabApiException && error.code in 400..499 && error.code != 408 && error.code != 429) return null
    if (lastKnownStatus !in ACTIVE) return null
    return min(INTERVAL shl min(failures, 4), MAX_BACKOFF)
  }
}

/** Pipeline detail with its jobs, and job detail with the log. Active ones refresh while open. */
class PipelineFeature internal constructor(private val host: SessionHost) {

  private var pipelineJob: Job? = null
  private var jobJob: Job? = null

  internal fun reset() {
    pipelineJob?.cancel()
    jobJob?.cancel()
  }

  /** Stops polling when the user leaves the screen. */
  internal fun onDestinationChanged(destination: ScreenDestination) {
    if (destination !is ScreenDestination.PipelineDetail) pipelineJob?.cancel()
    if (destination !is ScreenDestination.JobDetail) jobJob?.cancel()
  }

  /** Waits up to [delayMs] in the foreground; if backgrounded, pauses until foreground returns and refreshes at once. */
  private suspend fun awaitNextPoll(delayMs: Long) {
    val backgrounded = withTimeoutOrNull(delayMs) {
      host.foreground.first { !it }
      true
    } ?: false
    if (backgrounded) {
      host.foreground.first { it }
    }
  }

  /** Runs a pipeline on [ref], opens it, and lets [onResult] know (null on success, else the reason). */
  fun runPipeline(projectId: Long, ref: String, onResult: (String?) -> Unit) {
    host.session.launch {
      host.api { host.repository.runPipeline(it, projectId, ref) }.fold(
        onSuccess = { pipeline ->
          onResult(null)
          host.notify("Started pipeline #${pipeline.id} on ${pipeline.ref.ifBlank { ref }}")
          openPipeline(projectId, pipeline.id)
        },
        onFailure = { onResult(it.userMessage()) },
      )
    }
  }

  fun openPipeline(projectId: Long, pipelineId: Long) = host.navigateTo(ScreenDestination.PipelineDetail(projectId, pipelineId))

  fun openJob(projectId: Long, jobId: Long) = host.navigateTo(ScreenDestination.JobDetail(projectId, jobId))

  internal fun loadPipeline(projectId: Long, pipelineId: Long) {
    pipelineJob?.cancel()
    host.state.update { s ->
      val previous = s.pipelineDetail.takeIf { it.projectId == projectId && it.pipelineId == pipelineId }
      val known = previous?.pipeline?.data ?: (s.pipelines.data + s.project.pipelines.data + s.mergeRequestDetail.pipelines.data).firstOrNull { it.id == pipelineId }
      s.copy(
        pipelineDetail = PipelineDetailState(
          projectId, pipelineId,
          pipeline = Loadable<GitLabPipeline?>(known, loaded = known != null).start(),
          jobs = (previous?.jobs ?: emptyFeed<GitLabJob>()).start(),
        )
      )
    }
    val knownPipelineStatus = host.state.value.pipelineDetail.pipeline.data?.status
    pipelineJob = host.session.launch {
      var lastKnown = knownPipelineStatus
      var failures = 0
      while (true) {
        host.foreground.first { it }
        val (pipeline, jobs) = coroutineScope {
          val summary = async { host.api { host.repository.getPipeline(it, projectId, pipelineId) } }
          val list = async { host.api { host.repository.getPipelineJobs(it, projectId, pipelineId) } }
          summary.await() to list.await()
        }
        host.state.update { s ->
          if (s.pipelineDetail.pipelineId != pipelineId) s
          else s.copy(pipelineDetail = s.pipelineDetail.copy(
            pipeline = s.pipelineDetail.pipeline.settle(pipeline.map { it as GitLabPipeline? }),
            jobs = s.pipelineDetail.jobs.settle(jobs),
          ))
        }
        pipeline.getOrNull()?.let { lastKnown = it.status }
        failures = if (pipeline.isFailure) failures + 1 else 0
        val delayMs = LivePolling.next(pipeline.map { it.status }, lastKnown, failures) ?: break
        awaitNextPoll(delayMs)
      }
    }
  }

  fun retryLoadPipeline() {
    val d = host.state.value.pipelineDetail
    if (d.projectId > 0) loadPipeline(d.projectId, d.pipelineId)
  }

  internal fun loadJob(projectId: Long, jobId: Long) {
    jobJob?.cancel()
    host.state.update { s ->
      // Seed from the pipeline's job list so the header shows at once (and even when the job
      // endpoints need an account, as on GitLab.com).
      val previous = s.jobDetail.takeIf { it.projectId == projectId && it.jobId == jobId }
      val known = previous?.job?.data ?: s.pipelineDetail.jobs.data.firstOrNull { it.id == jobId }
      s.copy(jobDetail = JobDetailState(projectId, jobId, Loadable<GitLabJob?>(known, loaded = known != null).start(), (previous?.trace ?: Loadable<JobTrace?>(null)).start()))
    }
    val knownJobStatus = host.state.value.jobDetail.job.data?.status
    jobJob = host.session.launch {
      var lastKnown = knownJobStatus
      var failures = 0
      while (true) {
        host.foreground.first { it }
        val (job, trace) = coroutineScope {
          val summary = async { host.api { host.repository.getJob(it, projectId, jobId) } }
          val log = async { host.api { host.repository.getJobTrace(it, projectId, jobId) } }
          summary.await() to log.await()
        }
        host.state.update { s ->
          if (s.jobDetail.jobId != jobId) s
          else s.copy(jobDetail = s.jobDetail.copy(
            job = s.jobDetail.job.settle(job.map { it as GitLabJob? }),
            trace = s.jobDetail.trace.settle(trace.map { it as JobTrace? }),
          ))
        }
        job.getOrNull()?.let { lastKnown = it.status }
        failures = if (job.isFailure) failures + 1 else 0
        val delayMs = LivePolling.next(job.map { it.status }, lastKnown, failures) ?: break
        awaitNextPoll(delayMs)
      }
    }
  }

  fun retryLoadJob() {
    val d = host.state.value.jobDetail
    if (d.projectId > 0) loadJob(d.projectId, d.jobId)
  }

  /** Runs a manual job, from the pipeline's job list or the job page, then follows it. */
  fun playJob(projectId: Long, jobId: Long) {
    if (host.state.value.jobDetail.busy || host.state.value.pipelineDetail.busy) return
    setBusy(true)
    host.session.launch {
      host.api { host.repository.playJob(it, projectId, jobId) }.fold(
        onSuccess = { started ->
          setBusy(false)
          host.notify("Started ${started.name.ifBlank { "job #${started.id}" }}")
          refreshAfterAction(projectId, started.id)
        },
        onFailure = { error ->
          setBusy(false)
          host.notifyError(error.userMessage())
        },
      )
    }
  }

  /**
   * Saves the job's artifacts into the stream [open] gives (a file the user picked). On failure,
   * [discard] removes that file so a half-written archive isn't left behind.
   */
  fun downloadArtifacts(open: () -> java.io.OutputStream?, discard: () -> Unit = {}) {
    val d = host.state.value.jobDetail
    val job = d.job.data ?: return
    if (d.busy) return
    host.state.update { it.copy(jobDetail = it.jobDetail.copy(busy = true)) }
    host.session.launch {
      val result = host.api { host.repository.downloadJobArtifacts(it, d.projectId, job.id, open) }
      host.state.update { it.copy(jobDetail = it.jobDetail.copy(busy = false)) }
      result.fold(
        onSuccess = { bytes -> host.notify("Saved artifacts (${formatBytes(bytes)})") },
        onFailure = {
          withContext(Dispatchers.IO) { runCatching(discard) }
          host.notifyError(it.userMessage())
        },
      )
    }
  }

  /** Cancels the running job on the job page. */
  fun cancelJob() {
    val d = host.state.value.jobDetail
    val job = d.job.data ?: return
    if (d.busy) return
    setBusy(true)
    host.session.launch {
      host.api { host.repository.cancelJob(it, d.projectId, job.id) }.fold(
        onSuccess = { canceled ->
          setBusy(false)
          host.notify("Canceled ${canceled.name.ifBlank { "job #${canceled.id}" }}")
          refreshAfterAction(d.projectId, canceled.id)
        },
        onFailure = { error ->
          setBusy(false)
          host.notifyError(error.userMessage())
        },
      )
    }
  }

  private fun setBusy(busy: Boolean) = host.state.update {
    it.copy(jobDetail = it.jobDetail.copy(busy = busy), pipelineDetail = it.pipelineDetail.copy(busy = busy))
  }

  /** Reloads whichever of the pipeline and job pages show [jobId], so live refresh resumes. */
  private fun refreshAfterAction(projectId: Long, jobId: Long) {
    val s = host.state.value
    if (s.jobDetail.jobId == jobId && s.jobDetail.projectId == projectId) loadJob(projectId, jobId)
    if (s.pipelineDetail.projectId == projectId && s.pipelineDetail.jobs.data.any { it.id == jobId }) {
      loadPipeline(projectId, s.pipelineDetail.pipelineId)
    }
  }

  fun retryJob() {
    val d = host.state.value.jobDetail
    val job = d.job.data ?: return
    if (d.busy) return
    host.state.update { it.copy(jobDetail = it.jobDetail.copy(busy = true)) }
    host.session.launch {
      host.api { host.repository.retryJob(it, d.projectId, job.id) }.fold(
        onSuccess = { retried ->
          host.state.update { it.copy(jobDetail = it.jobDetail.copy(busy = false)) }
          host.notify("Started job #${retried.id}")
          openJob(d.projectId, retried.id)
        },
        onFailure = { error ->
          host.state.update { it.copy(jobDetail = it.jobDetail.copy(busy = false)) }
          host.notifyError(error.userMessage())
        }
      )
    }
  }

  fun cancelPipeline() {
    val d = host.state.value.pipelineDetail
    val pipeline = d.pipeline.data ?: return
    if (d.busy) return
    host.state.update { it.copy(pipelineDetail = it.pipelineDetail.copy(busy = true)) }
    host.session.launch {
      host.api { host.repository.cancelPipeline(it, d.projectId, pipeline.id) }.fold(
        onSuccess = { canceled ->
          host.state.update { s ->
            s.copy(pipelineDetail = s.pipelineDetail.copy(busy = false, pipeline = s.pipelineDetail.pipeline.success(canceled.copy(stages = pipeline.stages))))
          }
          host.notify("Pipeline #${pipeline.id} canceled")
        },
        onFailure = { error ->
          host.state.update { it.copy(pipelineDetail = it.pipelineDetail.copy(busy = false)) }
          host.notifyError(error.userMessage())
        }
      )
    }
  }
}

/** "740 B", "3.1 MB", "12 KB": one decimal below 10, whole numbers above. */
fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes / 1024.0
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  return String.format(java.util.Locale.US, if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
}
