package com.cboyxd.gitlab.ui.viewmodel

import com.cboyxd.gitlab.data.api.GitLabApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** Running pipelines and jobs keep refreshing through network blips. */
class LivePollingTest {
  private val offline = Result.failure<String?>(IOException("Couldn't reach GitLab."))

  @Test
  fun `running keeps polling and finished stops`() {
    assertEquals(LivePolling.INTERVAL, LivePolling.next(Result.success("running"), "running", failures = 0))
    assertEquals(LivePolling.INTERVAL, LivePolling.next(Result.success("pending"), "running", failures = 0))
    assertNull(LivePolling.next(Result.success("success"), "running", failures = 0))
    assertNull(LivePolling.next(Result.success("failed"), "running", failures = 0))
  }

  @Test
  fun `a failed refresh of a running job retries with backoff`() {
    assertEquals(16_000L, LivePolling.next(offline, "running", failures = 1))
    assertEquals(32_000L, LivePolling.next(offline, "running", failures = 2))
    assertEquals(60_000L, LivePolling.next(offline, "running", failures = 3))
    assertEquals(60_000L, LivePolling.next(offline, "running", failures = 40))
    val busy = Result.failure<String?>(GitLabApiException(503, "Service unavailable"))
    assertEquals(16_000L, LivePolling.next(busy, "pending", failures = 1))
    val limited = Result.failure<String?>(GitLabApiException(429, "Too many requests"))
    assertEquals(16_000L, LivePolling.next(limited, "running", failures = 1))
  }

  @Test
  fun `errors that won't fix themselves stop, and so does a finished job`() {
    assertNull(LivePolling.next(Result.failure(GitLabApiException(404, "Not found")), "running", failures = 1))
    assertNull(LivePolling.next(Result.failure(GitLabApiException(401, "Unauthorized")), "running", failures = 1))
    assertNull(LivePolling.next(offline, "success", failures = 1))
    assertNull(LivePolling.next(offline, null, failures = 1))
  }
}
