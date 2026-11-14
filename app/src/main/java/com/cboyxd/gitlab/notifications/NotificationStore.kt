package com.cboyxd.gitlab.notifications

import android.content.Context
import android.util.Base64
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
)

/** Small per-account cursors and a durable deduplication window survive app restarts. */
internal class NotificationStore(context: Context) {
  private val prefs = context.getSharedPreferences("gitlab_notification_state", Context.MODE_PRIVATE)

  fun cursor(account: String, feed: String): Long = prefs.getLong("cursor:$account:$feed", 0)
  fun advance(account: String, feed: String, time: Long) { prefs.edit().putLong("cursor:$account:$feed", time).commit() }
  fun snapshot(account: String, feed: String): JSONObject = runCatching { JSONObject(prefs.getString("snapshot:$account:$feed", "{}")!!) }.getOrDefault(JSONObject())
  fun saveSnapshot(account: String, feed: String, value: JSONObject) { prefs.edit().putString("snapshot:$account:$feed", value.toString()).commit() }

  fun record(event: GitLabNotificationEvent): Boolean {
    val key = "delivered:${event.accountId}"
    val previous = prefs.getStringSet(key, emptySet()).orEmpty()
    if (event.id in previous) return false
    val ids = LinkedHashSet(previous)
    ids += event.id
    // Cursors also prevent older events from being replayed when this bounded window rolls over.
    while (ids.size > 2000) ids.remove(ids.first())
    return prefs.edit().putStringSet(key, ids).commit()
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

  private companion object {
    const val KEY_OPEN_INTENT_TOKEN = "open_intent_token"
  }
}
