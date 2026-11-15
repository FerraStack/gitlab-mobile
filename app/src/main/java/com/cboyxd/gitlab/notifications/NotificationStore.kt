package com.cboyxd.gitlab.notifications

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

internal data class GitLabNotificationEvent(
  val id: String,
  val accountId: String,
  val title: String,
  val message: String,
  val targetType: String,
  val projectId: Long,
  val iid: Long,
  val url: String,
  val channel: String,
  val createdAt: Long = System.currentTimeMillis(),
  val accountName: String = "",
  val todoId: Long? = null,
)

/** Small per-account cursors and a durable deduplication window survive app restarts. */
internal class NotificationStore(
  context: Context,
  private val maxDelivered: Int = MAX_DELIVERED,
) {
  private val prefs = context.getSharedPreferences("gitlab_notification_state", Context.MODE_PRIVATE)

  internal data class DeliveredEntry(val id: String, val at: Long)

  fun cursor(account: String, feed: String): Long = prefs.getLong("cursor:$account:$feed", 0)
  fun advance(account: String, feed: String, time: Long) { prefs.edit().putLong("cursor:$account:$feed", time).commit() }
  fun snapshot(account: String, feed: String): JSONObject = runCatching { JSONObject(prefs.getString("snapshot:$account:$feed", "{}")!!) }.getOrDefault(JSONObject())
  fun saveSnapshot(account: String, feed: String, value: JSONObject) { prefs.edit().putString("snapshot:$account:$feed", value.toString()).commit() }

  fun isAuthFailureNotified(accountId: String): Boolean =
    prefs.getBoolean("auth_notified:$accountId", false)

  fun setAuthFailureNotified(accountId: String, notified: Boolean): Boolean {
    val key = "auth_notified:$accountId"
    return if (notified) {
      prefs.edit().putBoolean(key, true).commit()
    } else {
      prefs.edit().remove(key).commit()
    }
  }

  fun record(event: GitLabNotificationEvent): Boolean {
    val entries = readEntries(event.accountId).toMutableList()
    if (entries.any { it.id == event.id }) return false
    entries += DeliveredEntry(event.id, event.createdAt)
    val limit = maxDelivered.coerceAtLeast(1)
    // Cursors also prevent older events from being replayed when this bounded window rolls over.
    while (entries.size > limit) entries.removeAt(0)
    val array = JSONArray().apply {
      entries.forEach { entry ->
        put(JSONObject().put("id", entry.id).put("at", entry.at))
      }
    }
    return prefs.edit()
      .putString(deliveredKey(event.accountId), array.toString())
      .remove(legacyKey(event.accountId))
      .commit()
  }

  fun recordedIds(accountId: String): List<String> = readEntries(accountId).map { it.id }

  fun recordedEntries(accountId: String): List<DeliveredEntry> = readEntries(accountId)

  private fun readEntries(accountId: String): List<DeliveredEntry> {
    val key = deliveredKey(accountId)
    val raw = prefs.getString(key, null)
    if (!raw.isNullOrBlank()) {
      val array = runCatching { JSONArray(raw) }.getOrNull()
      if (array != null) {
        return (0 until array.length()).mapNotNull { index ->
          val item = array.optJSONObject(index) ?: return@mapNotNull null
          val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
          DeliveredEntry(id, item.optLong("at"))
        }
      }
    }
    val legacy = prefs.all[legacyKey(accountId)] as? Set<*> ?: return emptyList()
    return legacy.mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } }.map { DeliveredEntry(it, 0L) }
  }

  /**
   * Secret attached to notification tap intents so external apps cannot forge [GitLabNotifications.ACTION_OPEN]
   * extras against the exported [com.cboyxd.gitlab.MainActivity]. Stored in private prefs so taps
   * still work after process death.
   */
  @Synchronized
  fun openIntentToken(): String {
    val existing = prefs.getString(KEY_OPEN_INTENT_TOKEN, null)
    if (!existing.isNullOrBlank()) return existing
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    val generated = Base64.encodeToString(bytes, Base64.NO_WRAP)
    prefs.edit().putString(KEY_OPEN_INTENT_TOKEN, generated).commit()
    return generated
  }

  fun prune(accounts: Set<String>) {
    val editor = prefs.edit()
    prefs.all.keys.forEach { key ->
      val account = key.split(':').getOrNull(1)
      if (account != null && account !in accounts) editor.remove(key)
    }
    editor.apply()
  }

  companion object {
    const val MAX_DELIVERED = 2000
    private const val KEY_OPEN_INTENT_TOKEN = "open_intent_token"
    private fun deliveredKey(accountId: String) = "delivered_events:$accountId"
    private fun legacyKey(accountId: String) = "delivered:$accountId"
  }
}
