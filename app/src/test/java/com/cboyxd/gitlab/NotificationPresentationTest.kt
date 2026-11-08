package com.cboyxd.gitlab.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Honest times on late notifications and one summary per account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPresentationTest {
  private val minute = 60_000L
  private val hour = 60 * minute
  private val now = 1_800_000_000_000L
  private lateinit var app: Application
  private lateinit var manager: NotificationManager

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    GitLabNotifications.createChannels(app)
    manager = app.getSystemService(NotificationManager::class.java)
    manager.cancelAll()
  }

  private fun event(id: String, at: Long, account: String = "a", title: String = "You were mentioned") = GitLabNotificationEvent(
    id = id,
    accountId = account,
    title = title,
    message = "#7 Launch button does nothing\nexample-org/rocket\nFrom Pat · GitLab.com",
    targetType = "Issue",
    projectId = 1,
    iid = 7,
    url = "https://gitlab.com/example-org/rocket/-/issues/7",
    channel = GitLabNotifications.ACTIVITY,
    createdAt = at,
    accountName = "GitLab.com",
  )

  private fun posted(tag: String): Notification? = manager.activeNotifications.firstOrNull { it.tag == tag }?.notification

  @Test
  fun `delays over ten minutes are labelled`() {
    assertNull(NotificationPresentation.delayLabel(now - 9 * minute, now))
    assertEquals("12 min ago · delayed", NotificationPresentation.delayLabel(now - 12 * minute, now))
    assertEquals("3 h ago · delayed", NotificationPresentation.delayLabel(now - 3 * hour - 20 * minute, now))
    assertEquals("2 d ago · delayed", NotificationPresentation.delayLabel(now - 50 * hour, now))
  }

  @Test
  fun `a late notification says how old it is instead of a bare time`() {
    assertTrue(GitLabNotifications.show(app, event("late", now - 3 * hour), now))
    val late = posted("a:late")!!
    assertEquals("3 h ago · delayed", late.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
    assertFalse(late.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
    assertEquals(now - 3 * hour, late.`when`)

    GitLabNotifications.show(app, event("fresh", now - 2 * minute), now)
    val fresh = posted("a:fresh")!!
    assertNull(fresh.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
    assertTrue(fresh.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
  }

  @Test
  fun `two or more events of an account get an inbox summary`() {
    GitLabNotifications.show(app, event("one", now - minute), now)
    assertNull("a single event needs no summary", posted("a:summary"))

    GitLabNotifications.show(app, event("two", now, title = "Review requested from you"), now)
    GitLabNotifications.show(app, event("other", now, account = "b"), now)
    val summary = posted("a:summary")!!
    assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
    assertEquals("gitlab:a", summary.group)
    assertEquals("2 GitLab updates", summary.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    val lines = summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
    assertEquals(
      listOf("Review requested from you: #7 Launch button does nothing", "You were mentioned: #7 Launch button does nothing"),
      lines,
    )
    assertNull("account b has one event", posted("b:summary"))
  }

  @Test
  fun `summaries keep five lines and count the rest`() {
    val events = (1..7).map { "Event $it" to "line $it\nmore" }
    val summary = NotificationPresentation.summary("GitLab.com", events)!!
    assertEquals("7 GitLab updates", summary.title)
    assertEquals(5, summary.lines.size)
    assertEquals("Event 1: line 1", summary.lines.first())
    assertEquals("GitLab.com · +2 more", summary.summaryText)
    assertNull(NotificationPresentation.summary("GitLab.com", events.take(1)))
  }
}
