package com.cboyxd.gitlab.notifications

/** How an event notification tells the truth about time, and how an account's events are summarized. */
object NotificationPresentation {
  /** Deliveries later than this after the event say so. */
  const val DELAYED_AFTER = 10 * 60_000L
  /** Lines an InboxStyle summary shows before "+N more". */
  const val SUMMARY_LINES = 5

  fun isDelayed(eventAt: Long, deliveredAt: Long): Boolean = deliveredAt - eventAt > DELAYED_AFTER

  /**
   * Subtext for a late notification, such as "3 h ago · delayed". The header then shows this
   * instead of the bare event time, so an old event doesn't pass for a new one. Null when on time.
   */
  fun delayLabel(eventAt: Long, deliveredAt: Long): String? =
    if (isDelayed(eventAt, deliveredAt)) "${coarseAge(deliveredAt - eventAt)} ago · delayed" else null

  /** "12 min", "3 h", "2 d". */
  fun coarseAge(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(1)
    return when {
      minutes < 60 -> "$minutes min"
      minutes < 24 * 60 -> "${minutes / 60} h"
      else -> "${minutes / (24 * 60)} d"
    }
  }

  data class Summary(val title: String, val lines: List<String>, val summaryText: String)

  /**
   * The group summary for one account, from its visible events (newest first) as
   * "title: first line of the message". Null for fewer than two: a single event needs no summary.
   */
  fun summary(accountName: String, events: List<Pair<String, String>>): Summary? {
    if (events.size < 2) return null
    val lines = events.take(SUMMARY_LINES).map { (title, message) ->
      val detail = message.lineSequence().firstOrNull().orEmpty()
      if (detail.isBlank()) title else "$title: $detail"
    }
    val more = events.size - lines.size
    val name = accountName.ifBlank { "GitLab" }
    return Summary(
      title = "${events.size} GitLab updates",
      lines = lines,
      summaryText = if (more > 0) "$name · +$more more" else name,
    )
  }
}
