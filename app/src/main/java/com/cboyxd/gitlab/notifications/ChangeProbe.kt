package com.cboyxd.gitlab.notifications

import com.cboyxd.gitlab.data.api.GitLabApiClient
import com.cboyxd.gitlab.data.model.GitLabInstance
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap

sealed interface ProbeResult {
  /** Something changed since the last probe (or this is the first): read every feed. */
  data object Changed : ProbeResult
  data object Unchanged : ProbeResult
  data class RateLimited(val retryAfterMs: Long) : ProbeResult
  data class Failed(val message: String, val authError: Boolean = false) : ProbeResult
}

/**
 * One request per account that tells whether the full notification check is worth running:
 * `GET /user_counts` with the last ETag (a 304 or identical counts mean nothing new), or, on
 * servers without that endpoint, the newest pending to-do and the to-do total.
 */
class ChangeProbe {
  private data class Seen(val etag: String?, val fingerprint: String)

  private val seen = ConcurrentHashMap<String, Seen>()
  private val withoutCounts: MutableSet<String> = ConcurrentHashMap.newKeySet()

  suspend fun check(account: GitLabInstance): ProbeResult = try {
    val service = GitLabApiClient.createService(account)
    val previous = seen[account.id]
    var result: ProbeResult? = null
    if (account.id !in withoutCounts) {
      val response = service.getUserCounts(previous?.etag?.takeIf { previous.fingerprint.startsWith(COUNTS) })
      when {
        response.code() == 304 -> result = ProbeResult.Unchanged
        response.isSuccessful -> {
          val body = response.body()?.use { it.string() }.orEmpty().trim()
          result = remember(account.id, response.headers()["ETag"], COUNTS + body)
        }
        response.code() == 404 -> {
          response.errorBody()?.close()
          withoutCounts += account.id
        }
        else -> result = failure(response)
      }
    }
    result ?: run {
      val response = service.getTodos(state = "pending", perPage = 1)
      if (!response.isSuccessful) failure(response) else {
        val newest = response.body()?.firstOrNull()?.id
        remember(account.id, null, "todos:${response.headers()["X-Total"]}:$newest")
      }
    }
  } catch (cancel: CancellationException) {
    throw cancel
  } catch (error: Exception) {
    ProbeResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
  }

  fun forget(accountId: String) {
    seen.remove(accountId)
    withoutCounts.remove(accountId)
  }

  private fun remember(accountId: String, etag: String?, fingerprint: String): ProbeResult {
    val before = seen.put(accountId, Seen(etag, fingerprint))
    return if (before?.fingerprint == fingerprint) ProbeResult.Unchanged else ProbeResult.Changed
  }

  private fun failure(response: Response<*>): ProbeResult {
    response.errorBody()?.close()
    val code = response.code()
    if (code == 429) return ProbeResult.RateLimited(retryAfterMs(response.headers()["Retry-After"]))
    return ProbeResult.Failed("HTTP $code", authError = code == 401 || code == 403)
  }

  companion object {
    private const val COUNTS = "counts:"
    private const val DEFAULT_RETRY_AFTER = 60_000L

    /** Retry-After in seconds; HTTP dates and nonsense fall back to a minute. */
    internal fun retryAfterMs(header: String?): Long =
      header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 } ?: DEFAULT_RETRY_AFTER
  }
}
