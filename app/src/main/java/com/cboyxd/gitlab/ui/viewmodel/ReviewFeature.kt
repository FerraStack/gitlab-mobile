package com.cboyxd.gitlab.ui.viewmodel

import com.cboyxd.gitlab.data.model.GitLabAwardEmoji
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.data.model.GitLabMergeRequestUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Merge request detail: overview, discussion, commits, pipelines, approvals, triage and changes. */
class ReviewFeature internal constructor(
  private val host: SessionHost,
  private val labels: (Long) -> Unit,
) {

  private var job: Job? = null

  private val detail get() = host.state.value.mergeRequestDetail

  internal fun reset() {
    job?.cancel()
  }

  fun open(mr: GitLabMergeRequest) {
    host.state.update {
      it.copy(mergeRequestDetail = MergeRequestDetailState(mr.projectId, mr.iid, Loadable<GitLabMergeRequest?>(mr, loaded = true)))
    }
    host.navigateTo(ScreenDestination.MergeRequestDetail(mr.projectId, mr.iid))
  }

  /** Loads everything the detail screen shows, in parallel. Keeps a seeded summary if present. */
  internal fun load(projectId: Long, iid: Long) {
    job?.cancel()
    labels(projectId)
    host.state.update { s ->
      val seeded: Loadable<GitLabMergeRequest?> = s.mergeRequestDetail.takeIf { it.projectId == projectId && it.iid == iid }?.mergeRequest
        ?: (s.mergeRequests.data + s.project.mergeRequests.data + s.recentMergeRequests).firstOrNull { it.projectId == projectId && it.iid == iid }
          ?.let { Loadable<GitLabMergeRequest?>(it, loaded = true) }
        ?: Loadable(null)
      s.copy(
        mergeRequestDetail = MergeRequestDetailState(
          projectId = projectId,
          iid = iid,
          mergeRequest = seeded.start(),
          diffs = emptyFeed<com.cboyxd.gitlab.data.model.GitLabFileDiff>().start(),
          notes = emptyFeed<com.cboyxd.gitlab.data.model.GitLabNote>().start(),
          discussions = emptyFeed<com.cboyxd.gitlab.data.model.GitLabDiscussion>().start(),
          commits = emptyFeed<com.cboyxd.gitlab.data.model.GitLabCommit>().start(),
          pipelines = emptyFeed<com.cboyxd.gitlab.data.model.GitLabPipeline>().start(),
          approvals = Loadable<com.cboyxd.gitlab.data.model.GitLabApprovals?>(null).start(),
        )
      )
    }
    val repo = host.repository
    job = host.session.launch {
      launch {
        val r = host.api { repo.getMergeRequest(it, projectId, iid) }
        // The single-MR endpoint has no label colors; keep the ones the list already delivered.
        apply(projectId, iid) { d ->
          val known = d.mergeRequest.data?.labelDetails.orEmpty()
          d.copy(mergeRequest = d.mergeRequest.settle(r.map { mr -> mr.copy(labelDetails = mr.labelDetails.ifEmpty { known }) as GitLabMergeRequest? }))
        }
      }
      launch {
        val r = host.api { repo.getMergeRequestDiffsPage(it, projectId, iid) }
        val slice = r.getOrNull()
        apply(projectId, iid) {
          it.copy(
            diffs = it.diffs.settle(r.map { page -> page.items }),
            diffsTotalCount = slice?.totalCount,
            diffsTruncated = slice?.truncated == true,
          )
        }
      }
      launch {
        val r = host.api { repo.getMergeRequestDiscussionsPage(it, projectId, iid) }
        val slice = r.getOrNull()
        val threadsResult = r.map { it.items }
        // Show the threads now; their diff previews need a compare request per revision pair.
        apply(projectId, iid) {
          it.copy(
            discussions = it.discussions.settle(threadsResult),
            notes = it.notes.settle(threadsResult.map { threads -> threads.flatMap { d -> d.notes }.distinctBy { n -> n.id } }),
            discussionsTotalCount = slice?.totalCount,
            discussionsTruncated = slice?.truncated == true,
          )
        }
        val threads = slice?.items
        if (threads != null) {
          val noteIds = threads.flatMap { it.notes }.filterNot { it.system }.map { it.id }.distinct()
          if (noteIds.isNotEmpty()) {
            launch {
              val gate = Semaphore(6)
              val awards = coroutineScope {
                noteIds.map { noteId ->
                  async {
                    gate.withPermit {
                      host.api { repo.getMergeRequestNoteAwardEmojis(it, projectId, iid, noteId) }
                        .getOrNull()?.let { noteId to it }
                    }
                  }
                }.awaitAll().filterNotNull().toMap()
              }
              if (awards.isNotEmpty()) apply(projectId, iid) { it.copy(noteAwardEmojis = it.noteAwardEmojis + awards) }
            }
          }
          val previews = host.api { Result.success(repo.getDiscussionDiffs(it, projectId, threads)) }.getOrDefault(emptyMap())
          if (previews.isNotEmpty()) apply(projectId, iid) { it.copy(discussionDiffs = previews) }
        }
      }
      launch {
        val r = host.api { repo.getMergeRequestAwardEmojis(it, projectId, iid) }
        r.onSuccess { awards -> apply(projectId, iid) { it.copy(awardEmojis = awards, awardEmojisLoaded = true) } }
      }
      launch { val r = host.api { repo.getMergeRequestCommits(it, projectId, iid) }; apply(projectId, iid) { it.copy(commits = it.commits.settle(r)) } }
      launch { val r = host.api { repo.getMergeRequestPipelines(it, projectId, iid) }; apply(projectId, iid) { it.copy(pipelines = it.pipelines.settle(r)) } }
      launch { val r = host.api { repo.getMergeRequestApprovals(it, projectId, iid) }; apply(projectId, iid) { it.copy(approvals = it.approvals.settle(r)) } }
    }
  }

  fun retry() {
    val d = detail
    if (d.projectId > 0) load(d.projectId, d.iid)
  }

  fun setClosed(closed: Boolean) {
    val mr = detail.mergeRequest.data ?: return
    if (detail.busy) return
    apply(mr.projectId, mr.iid) { it.copy(busy = true) }
    host.session.launch {
      host.api { host.repository.setMergeRequestState(it, mr, closed) }.fold(
        onSuccess = { updated ->
          replace(updated)
          host.notify(if (closed) "Merge request closed" else "Merge request reopened")
        },
        onFailure = { error ->
          apply(mr.projectId, mr.iid) { it.copy(busy = false) }
          host.notifyError(error.userMessage()) { setClosed(closed) }
        }
      )
    }
  }

  fun merge(options: com.cboyxd.gitlab.data.model.MergeOptions = com.cboyxd.gitlab.data.model.MergeOptions()) {
    val mr = detail.mergeRequest.data ?: return
    if (detail.busy) return
    apply(mr.projectId, mr.iid) { it.copy(busy = true) }
    host.session.launch {
      host.api { host.repository.mergeMergeRequest(it, mr, options) }.fold(
        onSuccess = { updated ->
          replace(updated)
          host.notify(if (updated.state == "merged") "Merged !${updated.iid}" else "!${updated.iid} will merge when the pipeline succeeds")
        },
        onFailure = { error ->
          apply(mr.projectId, mr.iid) { it.copy(busy = false) }
          host.notifyError(error.userMessage()) { merge(options) }
        }
      )
    }
  }

  fun markReady() {
    val mr = detail.mergeRequest.data ?: return
    if (detail.busy) return
    apply(mr.projectId, mr.iid) { it.copy(busy = true) }
    host.session.launch {
      host.api { host.repository.markMergeRequestReady(it, mr) }.fold(
        onSuccess = { updated ->
          replace(updated)
          host.notify("!${updated.iid} is ready")
        },
        onFailure = { error ->
          apply(mr.projectId, mr.iid) { it.copy(busy = false) }
          host.notifyError(error.userMessage()) { markReady() }
        },
      )
    }
  }

  /** Starts a rebase, then reloads the merge request once GitLab had time to finish it. */
  fun rebase() {
    val mr = detail.mergeRequest.data ?: return
    if (detail.busy) return
    apply(mr.projectId, mr.iid) { it.copy(busy = true) }
    host.session.launch {
      val started = host.api { host.repository.rebaseMergeRequest(it, mr) }
      started.exceptionOrNull()?.let { error ->
        apply(mr.projectId, mr.iid) { it.copy(busy = false) }
        host.notifyError(error.userMessage()) { rebase() }
        return@launch
      }
      host.notify("Rebasing !${mr.iid} onto ${mr.targetBranch}…")
      kotlinx.coroutines.delay(REBASE_SETTLE_MS)
      val fresh = host.api { host.repository.getMergeRequest(it, mr.projectId, mr.iid) }.getOrNull()
      if (fresh != null) replace(fresh) else apply(mr.projectId, mr.iid) { it.copy(busy = false) }
    }
  }

  fun cancelAutoMerge() {
    val mr = detail.mergeRequest.data ?: return
    if (detail.busy) return
    apply(mr.projectId, mr.iid) { it.copy(busy = true) }
    host.session.launch {
      host.api { host.repository.cancelAutoMerge(it, mr) }.fold(
        onSuccess = { updated ->
          replace(updated)
          host.notify("Auto-merge canceled")
        },
        onFailure = { error ->
          apply(mr.projectId, mr.iid) { it.copy(busy = false) }
          host.notifyError(error.userMessage()) { cancelAutoMerge() }
        },
      )
    }
  }

  fun postNote(body: String, onResult: (String?) -> Unit) {
    val d = detail
    val mr = d.mergeRequest.data ?: return
    if (d.posting) return
    apply(mr.projectId, mr.iid) { it.copy(posting = true) }
    host.session.launch {
      host.api { host.repository.createMergeRequestNote(it, mr.projectId, mr.iid, body) }.fold(
        onSuccess = { note ->
          apply(mr.projectId, mr.iid) { it.copy(posting = false, notes = it.notes.copy(data = it.notes.data + note), discussions = it.discussions.copy(data = it.discussions.data + com.cboyxd.gitlab.data.model.GitLabDiscussion("note-${note.id}", individualNote = true, notes = listOf(note)))) }
          onResult(null)
        },
        onFailure = { error ->
          apply(mr.projectId, mr.iid) { it.copy(posting = false) }
          onResult(error.userMessage())
        }
      )
    }
  }

  /**
   * Starts a thread on one line of the changes. The comment is anchored to the version the page
   * shows ([GitLabMergeRequest.diffRefs]); [onResult] gets null on success or the error message.
   */
  fun commentOnLine(anchor: com.cboyxd.gitlab.data.model.DiffLineAnchor, body: String, onResult: (String?) -> Unit) {
    val d = detail
    val mr = d.mergeRequest.data ?: return
    if (d.posting) return
    val refs = mr.diffRefs ?: return onResult("The merge request is still loading. Try again in a moment.")
    apply(mr.projectId, mr.iid) { it.copy(posting = true) }
    host.session.launch {
      host.api { host.repository.createDiffNote(it, mr.projectId, mr.iid, refs, anchor, body) }.fold(
        onSuccess = { thread ->
          apply(mr.projectId, mr.iid) { s ->
            s.copy(
              posting = false,
              notes = s.notes.copy(data = s.notes.data + thread.notes),
              discussions = s.discussions.copy(data = s.discussions.data + thread),
            )
          }
          onResult(null)
        },
        onFailure = { error ->
          apply(mr.projectId, mr.iid) { it.copy(posting = false) }
          onResult(error.userMessage())
        },
      )
    }
  }

  /** Replies in thread [discussionId]; [onResult] gets null on success or the error message. */
  fun reply(discussionId: String, body: String, onResult: (String?) -> Unit) {
    val d = detail
    val mr = d.mergeRequest.data ?: return
    if (d.posting) return
    apply(mr.projectId, mr.iid) { it.copy(posting = true) }
    host.session.launch {
      host.api { host.repository.replyToDiscussion(it, mr.projectId, mr.iid, discussionId, body) }.fold(
        onSuccess = { note ->
          apply(mr.projectId, mr.iid) { s ->
            s.copy(
              posting = false,
              notes = s.notes.copy(data = s.notes.data + note),
              discussions = s.discussions.copy(data = s.discussions.data.map { t ->
                if (t.id == discussionId) t.copy(individualNote = false, notes = t.notes + note) else t
              }),
            )
          }
          onResult(null)
        },
        onFailure = { error ->
          apply(mr.projectId, mr.iid) { it.copy(posting = false) }
          onResult(error.userMessage())
        },
      )
    }
  }

  /** Resolves or reopens a thread, then refreshes mergeability ("Unresolved threads" may clear). */
  fun setResolved(discussionId: String, resolved: Boolean) {
    val mr = detail.mergeRequest.data ?: return
    if (discussionId in detail.resolvingThreads) return
    apply(mr.projectId, mr.iid) { it.copy(resolvingThreads = it.resolvingThreads + discussionId) }
    val repo = host.repository
    host.session.launch {
      val result = host.api { repo.resolveDiscussion(it, mr.projectId, mr.iid, discussionId, resolved) }
      apply(mr.projectId, mr.iid) { s ->
        val updated = result.getOrNull()
        s.copy(
          resolvingThreads = s.resolvingThreads - discussionId,
          discussions = if (updated == null) s.discussions else s.discussions.copy(data = s.discussions.data.map { t ->
            // Keep our display-formatted notes; take the resolved flags from GitLab's answer.
            if (t.id != discussionId) t else t.copy(notes = t.notes.map { n -> updated.notes.firstOrNull { it.id == n.id }?.let { u -> n.copy(resolved = u.resolved) } ?: n })
          }),
        )
      }
      result.exceptionOrNull()?.let {
        host.notifyError(it.userMessage()) { setResolved(discussionId, resolved) }
        return@launch
      }
      host.api { repo.getMergeRequest(it, mr.projectId, mr.iid) }.getOrNull()?.let { fresh ->
        apply(mr.projectId, mr.iid) { d ->
          val known = d.mergeRequest.data?.labelDetails.orEmpty()
          d.copy(mergeRequest = d.mergeRequest.success(fresh.copy(labelDetails = fresh.labelDetails.ifEmpty { known })))
        }
      }
    }
  }

  suspend fun branches(projectId: Long, search: String): Result<List<com.cboyxd.gitlab.data.model.GitLabBranch>> =
    host.api { host.repository.getBranches(it, projectId, search) }

  fun create(projectId: Long, title: String, source: String, target: String, description: String?, onResult: (String?) -> Unit) {
    host.session.launch {
      host.api {
        host.repository.createMergeRequest(it, projectId, title.trim(), source.trim(), target.trim(), description?.takeIf { d -> d.isNotBlank() })
      }.fold(
        onSuccess = { mr ->
          host.state.update { s ->
            s.copy(
              mergeRequests = s.mergeRequests.copy(data = listOf(mr) + s.mergeRequests.data.filterNot { it.projectId == mr.projectId && it.iid == mr.iid }),
              project = if (s.project.projectId == mr.projectId) s.project.copy(
                mergeRequests = s.project.mergeRequests.copy(data = listOf(mr) + s.project.mergeRequests.data.filterNot { it.iid == mr.iid }),
              ) else s.project,
            )
          }
          onResult(null)
          host.notify("Merge request !${mr.iid} created")
          open(mr)
        },
        onFailure = { error -> onResult(error.userMessage()) }
      )
    }
  }

  /** Approves ([approve] true) or revokes the user's approval, then refreshes approvals and mergeability. */
  fun setApproved(approve: Boolean) {
    val mr = detail.mergeRequest.data ?: return
    if (detail.busy) return
    apply(mr.projectId, mr.iid) { it.copy(busy = true) }
    val repo = host.repository
    host.session.launch {
      val result = host.api { if (approve) repo.approveMergeRequest(it, mr).map { } else repo.unapproveMergeRequest(it, mr) }
      result.onFailure { error ->
        apply(mr.projectId, mr.iid) { it.copy(busy = false) }
        host.notifyError(error.userMessage()) { setApproved(approve) }
        return@launch
      }
      val approvals = host.api { repo.getMergeRequestApprovals(it, mr.projectId, mr.iid) }
      val refreshed = host.api { repo.getMergeRequest(it, mr.projectId, mr.iid) }
      apply(mr.projectId, mr.iid) { d ->
        val known = d.mergeRequest.data?.labelDetails.orEmpty()
        d.copy(
          busy = false,
          approvals = d.approvals.settle(approvals),
          mergeRequest = refreshed.getOrNull()?.let { d.mergeRequest.success(it.copy(labelDetails = it.labelDetails.ifEmpty { known })) } ?: d.mergeRequest,
        )
      }
      host.notify(if (approve) "Approved !${mr.iid}" else "Approval revoked")
    }
  }

  /** Updates MR title, description, assignees, reviewers, labels, or milestone. */
  fun updateMergeRequest(update: GitLabMergeRequestUpdate, onResult: ((String?) -> Unit)? = null) {
    val mr = detail.mergeRequest.data ?: return
    if (detail.busy) {
      val msg = "An update is already in progress."
      if (onResult != null) onResult(msg) else host.notifyError(msg)
      return
    }
    apply(mr.projectId, mr.iid) { it.copy(busy = true) }
    host.session.launch {
      host.api { host.repository.updateMergeRequest(it, mr.projectId, mr.iid, update) }.fold(
        onSuccess = { updated ->
          replace(updated)
          onResult?.invoke(null)
          host.notify("Merge request updated")
        },
        onFailure = { error ->
          apply(mr.projectId, mr.iid) { it.copy(busy = false) }
          val message = error.userMessage()
          if (onResult != null) onResult(message) else host.notifyError(message)
        },
      )
    }
  }

  /** Adds or removes an emoji reaction on the merge request itself. */
  fun toggleReaction(name: String) {
    val mr = detail.mergeRequest.data ?: return
    val key = "mr:$name"
    if (key in detail.togglingReactions) return
    val existing = detail.awardEmojis.firstOrNull { it.name == name && isCurrentUsersAward(it) }
    apply(mr.projectId, mr.iid) { it.copy(togglingReactions = it.togglingReactions + key) }
    val repo = host.repository
    host.session.launch {
      if (existing != null) {
        host.api { repo.deleteMergeRequestAwardEmoji(it, mr.projectId, mr.iid, existing.id) }.fold(
          onSuccess = {
            apply(mr.projectId, mr.iid) { d ->
              d.copy(
                awardEmojis = d.awardEmojis.filterNot { it.id == existing.id },
                togglingReactions = d.togglingReactions - key,
              )
            }
          },
          onFailure = { error ->
            apply(mr.projectId, mr.iid) { it.copy(togglingReactions = it.togglingReactions - key) }
            host.notifyError(error.userMessage())
          },
        )
      } else {
        host.api { repo.addMergeRequestAwardEmoji(it, mr.projectId, mr.iid, name) }.fold(
          onSuccess = { added ->
            apply(mr.projectId, mr.iid) { d ->
              d.copy(
                awardEmojis = (d.awardEmojis.filterNot { it.id == added.id } + added),
                awardEmojisLoaded = true,
                togglingReactions = d.togglingReactions - key,
              )
            }
          },
          onFailure = { error ->
            apply(mr.projectId, mr.iid) { it.copy(togglingReactions = it.togglingReactions - key) }
            host.notifyError(error.userMessage())
          },
        )
      }
    }
  }

  /** Adds or removes an emoji reaction on a merge request comment/note. */
  fun toggleNoteReaction(noteId: Long, name: String) {
    val mr = detail.mergeRequest.data ?: return
    val key = "note:$noteId:$name"
    if (key in detail.togglingReactions) return
    val existing = detail.noteAwardEmojis[noteId].orEmpty().firstOrNull { it.name == name && isCurrentUsersAward(it) }
    apply(mr.projectId, mr.iid) { it.copy(togglingReactions = it.togglingReactions + key) }
    val repo = host.repository
    host.session.launch {
      if (existing != null) {
        host.api { repo.deleteMergeRequestNoteAwardEmoji(it, mr.projectId, mr.iid, noteId, existing.id) }.fold(
          onSuccess = {
            apply(mr.projectId, mr.iid) { d ->
              val updated = d.noteAwardEmojis[noteId].orEmpty().filterNot { it.id == existing.id }
              d.copy(
                noteAwardEmojis = d.noteAwardEmojis + (noteId to updated),
                togglingReactions = d.togglingReactions - key,
              )
            }
          },
          onFailure = { error ->
            apply(mr.projectId, mr.iid) { it.copy(togglingReactions = it.togglingReactions - key) }
            host.notifyError(error.userMessage())
          },
        )
      } else {
        host.api { repo.addMergeRequestNoteAwardEmoji(it, mr.projectId, mr.iid, noteId, name) }.fold(
          onSuccess = { added ->
            apply(mr.projectId, mr.iid) { d ->
              val updated = d.noteAwardEmojis[noteId].orEmpty().filterNot { it.id == added.id } + added
              d.copy(
                noteAwardEmojis = d.noteAwardEmojis + (noteId to updated),
                togglingReactions = d.togglingReactions - key,
              )
            }
          },
          onFailure = { error ->
            apply(mr.projectId, mr.iid) { it.copy(togglingReactions = it.togglingReactions - key) }
            host.notifyError(error.userMessage())
          },
        )
      }
    }
  }

  private fun isCurrentUsersAward(award: GitLabAwardEmoji): Boolean {
    val s = host.state.value
    val uid = s.currentUser.data?.id ?: 0L
    val uname = (s.currentUser.data?.username ?: s.activeInstance?.username).orEmpty()
    return (uid > 0L && award.user.id == uid) || (uname.isNotBlank() && award.user.username.equals(uname, ignoreCase = true))
  }

  private fun replace(updated: GitLabMergeRequest) {
    host.state.update { s ->
      val currentDetail = s.mergeRequestDetail
      val labelMap = s.labels[updated.projectId].orEmpty() + currentDetail.mergeRequest.data?.labelDetails.orEmpty().associateBy { it.name }
      val withColors = if (updated.labelDetails.isEmpty() && updated.labels.isNotEmpty()) {
        updated.copy(labelDetails = updated.labels.mapNotNull { labelMap[it] })
      } else updated
      fun List<GitLabMergeRequest>.swap() = map { if (it.projectId == withColors.projectId && it.iid == withColors.iid) withColors else it }
      s.copy(
        mergeRequests = s.mergeRequests.copy(data = s.mergeRequests.data.swap()),
        project = s.project.copy(mergeRequests = s.project.mergeRequests.copy(data = s.project.mergeRequests.data.swap())),
        mergeRequestDetail = if (currentDetail.projectId == withColors.projectId && currentDetail.iid == withColors.iid) {
          currentDetail.copy(mergeRequest = currentDetail.mergeRequest.success(withColors), busy = false)
        } else currentDetail,
      )
    }
  }

  private fun apply(projectId: Long, iid: Long, transform: (MergeRequestDetailState) -> MergeRequestDetailState) {
    host.state.update { s ->
      if (s.mergeRequestDetail.projectId == projectId && s.mergeRequestDetail.iid == iid) s.copy(mergeRequestDetail = transform(s.mergeRequestDetail)) else s
    }
  }
}

private const val REBASE_SETTLE_MS = 4_000L
