package com.cboyxd.gitlab

import com.cboyxd.gitlab.notifications.DeviceState
import com.cboyxd.gitlab.notifications.InstantPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When instant notifications check, back off and read everything. */
class InstantPolicyTest {
  private val second = 1000L
  private val minute = 60 * second

  @Test
  fun `the screen state picks the interval`() {
    assertEquals(90 * second, InstantPolicy.nextDelay(DeviceState.SCREEN_ON, 90 * second, failures = 0))
    assertEquals(5 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_OFF, 90 * second, failures = 0))
    assertEquals(15 * minute, InstantPolicy.nextDelay(DeviceState.IDLE, 90 * second, failures = 0))
  }

  @Test
  fun `errors back off exponentially up to 15 minutes`() {
    val interval = 60 * second
    assertEquals(2 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_ON, interval, failures = 1))
    assertEquals(4 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_ON, interval, failures = 2))
    assertEquals(8 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_ON, interval, failures = 3))
    assertEquals(15 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_ON, interval, failures = 4))
    assertEquals(15 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_ON, interval, failures = 60))
  }

  @Test
  fun `backoff never shortens the screen-off interval`() {
    assertEquals(5 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_OFF, 60 * second, failures = 1))
    assertEquals(15 * minute, InstantPolicy.nextDelay(DeviceState.IDLE, 60 * second, failures = 2))
  }

  @Test
  fun `retry-after is respected`() {
    assertEquals(10 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_ON, 60 * second, failures = 1, retryAfterMs = 10 * minute))
    assertEquals(2 * minute, InstantPolicy.nextDelay(DeviceState.SCREEN_ON, 60 * second, failures = 1, retryAfterMs = 5 * second))
  }

  @Test
  fun `full checks run on change, when unsure, and every ten minutes`() {
    val now = 100 * minute
    assertTrue(InstantPolicy.shouldFullPoll(changed = true, lastFullPollAt = now - minute, now = now))
    assertTrue(InstantPolicy.shouldFullPoll(changed = null, lastFullPollAt = now - minute, now = now))
    assertFalse(InstantPolicy.shouldFullPoll(changed = false, lastFullPollAt = now - minute, now = now))
    assertTrue(InstantPolicy.shouldFullPoll(changed = false, lastFullPollAt = now - 10 * minute, now = now))
    assertTrue(InstantPolicy.shouldFullPoll(changed = false, lastFullPollAt = 0, now = now))
  }
}
