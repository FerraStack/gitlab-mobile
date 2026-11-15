package com.cboyxd.gitlab.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cboyxd.gitlab.data.api.GitLabApiException
import com.cboyxd.gitlab.data.api.parseGitLabMillis
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.auth.GitLabAuthException
import com.cboyxd.gitlab.data.auth.GitLabSessionTokens
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.model.GitLabMergeRequest
import com.cboyxd.gitlab.data.model.GitLabTodo
import com.cboyxd.gitlab.data.repository.GitLabRepository
import com.cboyxd.gitlab.data.storage.InstanceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

class GitLabNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    return try {
      if (!GitLabNotifications.allowed(applicationContext)) return Result.success()
      val trigger = if (GitLabNotifications.TAG_ONE_TIME in tags) PollTrigger.ONE_TIME else PollTrigger.PERIODIC
      if (GitLabNotificationMonitor.poll(applicationContext, trigger)) Result.success() else Result.retry()
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (_: Exception) {
      Result.retry()
    }
  }
}

/** Shared by periodic work and the foreground loop. First successful reads establish a baseline. */
object GitLabNotificationMonitor {
  private val lock = Mutex()
  private val repository = GitLabRepository()

  /**
   * Checks every signed-in account once and records the run for diagnostics. Returns false when
   * a failure is worth retrying soon (network, 429, 5xx).
   */
  suspend fun poll(context: Context, trigger: PollTrigger = PollTrigger.APP_OPEN): Boolean = withContext(Dispatchers.IO) {
    val log = PollLog(context)
    val started = System.currentTimeMillis()
    if (!lock.tryLock()) {
      // The in-app loop overlapping a background check is normal; don't flood the log with it.
      if (trigger != PollTrigger.APP_OPEN) log.record(PollRun(started, 0, trigger, PollOutcome.BUSY))
      return@withContext true
    }
    try {
      if (!GitLabNotifications.allowed(context)) {
        if (trigger != PollTrigger.APP_OPEN) {
          log.record(PollRun(started, 0, trigger, PollOutcome.NOTIFICATIONS_DISABLED))
        }
        return@withContext true
      }
      GitLabNotifications.createChannels(context)
      val store = InstanceStore.get(context)
      val accounts = store.getInstances().filter { it.token.isNotBlank() }
      val notifications = NotificationStore(context)
      notifications.prune(accounts.map { it.id }.toSet())
      // Clear notifications for accounts removed while other accounts remain signed in.
      val manager = context.getSystemService(android.app.NotificationManager::class.java)
      if (android.os.Build.VERSION.SDK_INT >= 23) manager.activeNotifications.forEach { notification ->
        // Only event notifications carry a tag; the instant service's ongoing one doesn't.
        val tag = notification.tag ?: return@forEach
        if (tag.substringBefore(':') !in accounts.map { it.id }) manager.cancel(tag, notification.id)
      }
      var success = true
      val results = accounts.map { account ->
        if (account.authFailure) {
          if (!notifications.isAuthFailureNotified(account.id) && GitLabNotifications.showAuthFailure(context, account)) {
            notifications.setAuthFailureNotified(account.id, true)
          }
          return@map AccountPoll(account.id, account.name, ok = false, error = AUTH_EXPIRED_ERROR, delivered = 0)
        }
        if (notifications.isAuthFailureNotified(account.id)) {
          notifications.setAuthFailureNotified(account.id, false)
          GitLabNotifications.clearAuthFailure(context, account.id)
        }
        val delivered = AtomicInteger()
        try {
          val errors = pollAccount(context, store, notifications, account, delivered)
          if (errors.any { it.retryable }) success = false
          AccountPoll(account.id, account.name, ok = errors.isEmpty(), error = errors.firstOrNull()?.message, delivered = delivered.get())
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (error: Exception) {
          success = false
          AccountPoll(account.id, account.name, ok = false, error = describe(error), delivered = delivered.get())
        }
      }
      log.record(PollRun(started, System.currentTimeMillis() - started, trigger, PollRun.outcomeOf(results), results))
      success
    } finally {
      lock.unlock()
    }
  }

  private suspend fun <T> api(store: InstanceStore, account: GitLabInstance, block: suspend (GitLabInstance) -> kotlin.Result<T>): kotlin.Result<T> {
    return try {
      val current = GitLabSessionTokens.fresh(store, account.id)
        ?: return kotlin.Result.failure(IllegalStateException("Account removed"))
      val result = block(current)
      if ((result.exceptionOrNull() as? GitLabApiException)?.code == 401 && current.authKind == GitLabAuth.KIND_OAUTH) {
        val renewed = GitLabSessionTokens.fresh(store, account.id, force = true, staleToken = current.token)
        if (renewed != null && renewed.token != current.token) block(renewed) else result
      } else result
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (error: Exception) {
      kotlin.Result.failure(error)
    }
  }

  private class FeedError(val message: String, val retryable: Boolean, val unauthorized: Boolean = false)

  private fun Throwable.isUnauthorizedError(): Boolean =
    (this as? GitLabApiException)?.code == 401 || (this as? GitLabAuthException)?.rejected == true

  private fun describe(error: Throwable): String = when (error) {
    is GitLabApiException -> "${error.code}: ${error.message.orEmpty()}".trimEnd(':', ' ')
    else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
  }

  /** Polls one account and returns the feeds' errors; empty when every feed was read. */
  private suspend fun pollAccount(
    context: Context,
    store: InstanceStore,
    saved: NotificationStore,
    account: GitLabInstance,
    delivered: AtomicInteger,
  ): List<FeedError> = coroutineScope {
    val started = System.currentTimeMillis()
    val since = saved.cursor(account.id, "authored")
    val todos = async { api(store, account) { repository.getTodosFeed(it) } }
    val assigned = async { api(store, account) { repository.getNotificationMergeRequestsFeed(it, "assigned_to_me") } }
    val reviews = async { api(store, account) { repository.getNotificationMergeRequestsFeed(it, "reviews_for_me") } }
    val authored = async { api(store, account) { repository.getNotificationMergeRequestsFeed(it, "created_by_me", since.takeIf { it > 0 }?.let { iso(it - 60_000) }) } }
    val assignedResult = assigned.await()
    val reviewResult = reviews.await()
    val todoResult = todos.await()
    val authoredResult = authored.await()
    // Signing out during a network request must not deliver events from that account.
    if (store.getInstances().none { it.id == account.id && it.token.isNotBlank() }) return@coroutineScope emptyList()

    val sharedServerTime = listOfNotNull(
      assignedResult.getOrNull()?.serverTimeMs,
      reviewResult.getOrNull()?.serverTimeMs,
      todoResult.getOrNull()?.serverTimeMs,
      authoredResult.getOrNull()?.serverTimeMs,
    ).maxOrNull()

    assignedResult.getOrNull()?.let { feed ->
      val newestMs = feed.items.mapNotNull { parseGitLabMillis(it.updatedAt) }.maxOrNull()
      val cursorMs = resolveCursor(feed.serverTimeMs ?: sharedServerTime, newestMs, saved.cursor(account.id, "assigned"), started)
      processMembership(context, saved, delivered, account, "assigned", feed.items, cursorMs, sharedServerTime ?: started)
    }
    reviewResult.getOrNull()?.let { feed ->
      val newestMs = feed.items.mapNotNull { parseGitLabMillis(it.updatedAt) }.maxOrNull()
      val cursorMs = resolveCursor(feed.serverTimeMs ?: sharedServerTime, newestMs, saved.cursor(account.id, "review"), started)
      processMembership(context, saved, delivered, account, "review", feed.items, cursorMs, sharedServerTime ?: started)
    }
    todoResult.getOrNull()?.let { feed ->
      val list = feed.items
      val todoSince = saved.cursor(account.id, "todos")
      val suppressedAssigned = assignedResult.getOrNull()?.items?.map { mrKey(it) }?.toSet().orEmpty()
      val suppressedReviews = reviewResult.getOrNull()?.items?.map { mrKey(it) }?.toSet().orEmpty()
      val fallbackTime = feed.serverTimeMs ?: sharedServerTime ?: started
      if (todoSince > 0) list.sortedBy { it.createdAt }.forEach { todo ->
        if ((parseGitLabMillis(todo.createdAt) ?: 0) < todoSince - 300_000) return@forEach
        val projectId = todo.target?.projectId ?: todo.project?.id ?: 0
        val key = "$projectId:${todo.target?.iid ?: 0}"
        if (todo.targetType == "MergeRequest" && (
            (todo.actionName == "assigned" && key in suppressedAssigned) ||
            (todo.actionName == "review_requested" && key in suppressedReviews)
          )) {
          eventForTodo(account, todo, fallbackTime)?.let { saved.record(it) }
          return@forEach
        }
        eventForTodo(account, todo, fallbackTime)?.let { event -> deliver(context, saved, delivered, event) }
        // If a membership endpoint failed, a to-do still establishes that membership and
        // prevents a duplicate announcement when the endpoint recovers.
        val feedName = when {
          todo.targetType != "MergeRequest" -> null
          todo.actionName == "assigned" && assignedResult.isFailure -> "assigned"
          todo.actionName == "review_requested" && reviewResult.isFailure -> "review"
          else -> null
        }
        if (feedName != null && projectId > 0) {
          val snapshot = saved.snapshot(account.id, feedName)
          snapshot.put(key, true)
          saved.saveSnapshot(account.id, feedName, snapshot)
        }
      }
      if (todoSince == 0L) list.forEach { todo -> eventForTodo(account, todo, fallbackTime)?.let { saved.record(it) } }
      val newestTodoMs = list.mapNotNull { parseGitLabMillis(it.createdAt) }.maxOrNull()
      val nextCursor = resolveCursor(feed.serverTimeMs ?: sharedServerTime, newestTodoMs, todoSince, started)
      saved.advance(account.id, "todos", nextCursor)
    }
    authoredResult.getOrNull()?.let { feed ->
      val list = feed.items
      val previous = saved.snapshot(account.id, "authored")
      val fallbackTime = feed.serverTimeMs ?: sharedServerTime ?: started
      if (since > 0) list.sortedBy { it.updatedAt }.forEach { mr ->
        val old = previous.optString(mrKey(mr))
        val changedAt = parseGitLabMillis(if (mr.state == "merged") mr.mergedAt.orEmpty() else mr.closedAt ?: mr.updatedAt) ?: 0
        val title = when {
          mr.state == "merged" && (old.isNotBlank() && old != "merged" || old.isBlank() && changedAt >= since) -> "Your merge request was merged"
          mr.state == "closed" && (old.isNotBlank() && old != "closed" || old.isBlank() && changedAt >= since) -> "Your merge request was closed"
          mr.state == "opened" && old in setOf("closed", "merged") -> "Your merge request was reopened"
          else -> null
        }
        if (title != null) {
          val actor = (if (mr.state == "merged") mr.mergeUser ?: mr.mergedBy else mr.closedBy)?.let { it.name.ifBlank { it.username } }
          deliver(context, saved, delivered, mrEvent(account, mr, "state:${mr.state}:${mr.mergedAt ?: mr.closedAt ?: mr.updatedAt}", title, GitLabNotifications.ACTIVITY, actor, changedAt.takeIf { it > 0 } ?: fallbackTime))
        }
      }
      list.forEach { previous.put(mrKey(it), it.state) }
      while (previous.length() > 2000) previous.remove(previous.keys().next())
      saved.saveSnapshot(account.id, "authored", previous)
      val newestAuthoredMs = list.flatMap { mr ->
        listOfNotNull(
          parseGitLabMillis(mr.updatedAt),
          mr.mergedAt?.let(::parseGitLabMillis),
          mr.closedAt?.let(::parseGitLabMillis),
        )
      }.maxOrNull()
      val nextCursor = resolveCursor(feed.serverTimeMs ?: sharedServerTime, newestAuthoredMs, since, started)
      saved.advance(account.id, "authored", nextCursor)
    }

    val errors = listOf(assignedResult, reviewResult, todoResult, authoredResult).mapNotNull { result ->
      result.exceptionOrNull()?.let { error ->
        FeedError(
          message = describe(error),
          retryable = error !is GitLabApiException || error.code == 429 || error.code >= 500,
          unauthorized = error.isUnauthorizedError(),
        )
      }
    }.distinctBy { it.message }

    if (errors.any { it.unauthorized }) {
      store.setAuthFailure(account.id, true)
      if (!saved.isAuthFailureNotified(account.id) && GitLabNotifications.showAuthFailure(context, account)) {
        saved.setAuthFailureNotified(account.id, true)
      }
    }
    errors
  }

  private fun processMembership(
    context: Context,
    saved: NotificationStore,
    delivered: AtomicInteger,
    account: GitLabInstance,
    feed: String,
    list: List<GitLabMergeRequest>,
    cursorMs: Long,
    fallbackTimeMs: Long,
  ) {
    val old = saved.snapshot(account.id, feed)
    if (saved.cursor(account.id, feed) > 0) list.forEach { mr ->
      if (!old.has(mrKey(mr))) {
        deliver(context, saved, delivered, mrEvent(account, mr, "$feed:${mr.updatedAt}", if (feed == "review") "Review requested from you" else "Merge request assigned to you", GitLabNotifications.REVIEWS, at = parseGitLabMillis(mr.updatedAt) ?: fallbackTimeMs))
      }
    }
    val next = JSONObject()
    list.forEach { next.put(mrKey(it), true) }
    saved.saveSnapshot(account.id, feed, next)
    saved.advance(account.id, feed, cursorMs)
  }

  private fun deliver(context: Context, saved: NotificationStore, delivered: AtomicInteger, event: GitLabNotificationEvent) {
    if (saved.record(event) && GitLabNotifications.show(context, event)) delivered.incrementAndGet()
  }

  private fun mrKey(mr: GitLabMergeRequest) = "${mr.projectId}:${mr.iid}"

  /** [at] is when it happened, as far as GitLab says; it decides whether delivery counts as late. */
  private fun mrEvent(account: GitLabInstance, mr: GitLabMergeRequest, suffix: String, title: String, channel: String, actor: String? = null, at: Long? = null): GitLabNotificationEvent {
    val project = mr.webUrl.substringBefore("/-/").substringAfter("://").substringAfter('/')
    return GitLabNotificationEvent("mr:${mrKey(mr)}:$suffix", account.id, title,
      listOfNotNull("!${mr.iid} ${mr.title}", project.takeIf { it.isNotBlank() }, actor?.takeIf { it.isNotBlank() }?.let { "By $it" }, account.name).joinToString("\n"),
      "MergeRequest", mr.projectId, mr.iid, mr.webUrl, channel, at ?: System.currentTimeMillis(), accountName = account.name)
  }

  private fun eventForTodo(account: GitLabInstance, todo: GitLabTodo, fallbackTimeMs: Long = System.currentTimeMillis()): GitLabNotificationEvent? {
    val title = when (todo.actionName) {
      "assigned" -> if (todo.targetType == "MergeRequest") "Merge request assigned to you" else "Issue assigned to you"
      "review_requested" -> "Review requested from you"
      "review_submitted" -> "A review was submitted"
      "mentioned", "directly_addressed" -> "You were mentioned"
      "approval_required" -> "Your approval is required"
      "build_failed" -> "Pipeline failed"
      "unmergeable" -> "Merge request has conflicts"
      "merge_train_removed" -> "Merge request removed from merge train"
      else -> return null
    }
    val targetTitle = todo.target?.title ?: todo.body?.lineSequence()?.firstOrNull().orEmpty()
    val channel = when (todo.actionName) {
      "assigned", "review_requested", "approval_required" -> GitLabNotifications.REVIEWS
      "build_failed" -> GitLabNotifications.PIPELINES
      else -> GitLabNotifications.ACTIVITY
    }
    val number = todo.target?.iid?.takeIf { it > 0 }?.let { "${if (todo.targetType == "MergeRequest") "!" else "#"}$it " }.orEmpty()
    return GitLabNotificationEvent("todo:${todo.id}", account.id, title,
      listOf("$number$targetTitle", todo.project?.pathWithNamespace.orEmpty(), "From ${todo.author.name.ifBlank { todo.author.username }} · ${account.name}").filter { it.isNotBlank() }.joinToString("\n"),
      todo.targetType.orEmpty(), todo.target?.projectId ?: todo.project?.id ?: 0, todo.target?.iid ?: 0, todo.targetUrl.orEmpty(), channel,
      parseGitLabMillis(todo.createdAt) ?: fallbackTimeMs, accountName = account.name, todoId = todo.id)
  }

  internal fun resolveCursor(
    serverDateMs: Long?,
    newestItemMs: Long?,
    previousCursor: Long,
    fallbackNowMs: Long,
  ): Long {
    val serverBest = listOfNotNull(serverDateMs, newestItemMs).maxOrNull()
    if (serverBest != null) return maxOf(previousCursor, serverBest)
    return if (previousCursor > 0L) previousCursor else fallbackNowMs
  }

  private fun iso(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))

  private const val AUTH_EXPIRED_ERROR = "Sign in again to keep getting notifications"
}
