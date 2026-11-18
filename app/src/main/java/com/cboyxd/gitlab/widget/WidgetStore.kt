package com.cboyxd.gitlab.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WidgetPipelineSummary(
  val id: Long,
  val projectId: Long,
  val projectPath: String,
  val ref: String,
  val status: String,
)

data class WidgetSnapshot(
  val hasAccounts: Boolean = false,
  val todoCount: Int = 0,
  val pipelines: List<WidgetPipelineSummary> = emptyList(),
  val updatedAt: Long = 0L,
)

/**
 * Caches the latest to-do count and pipeline summaries from normal app / notification checks so the
 * home-screen widget updates without any extra network polling.
 */
object WidgetStore {
  private const val PREFS = "gitlab_widget_cache"
  private const val KEY_HAS_ACCOUNTS = "has_accounts"
  private const val KEY_TODO_COUNT = "todo_count"
  private const val KEY_ACCOUNT_TODOS = "account_todos"
  private const val KEY_PIPELINES = "pipelines"
  private const val KEY_UPDATED_AT = "updated_at"
  private const val MAX_PIPELINES = 3

  fun read(context: Context): WidgetSnapshot {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val hasAccounts = prefs.getBoolean(KEY_HAS_ACCOUNTS, false)
    val todoCount = prefs.getInt(KEY_TODO_COUNT, 0).coerceAtLeast(0)
    val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
    val pipelines = parsePipelines(prefs.getString(KEY_PIPELINES, null))
    return WidgetSnapshot(hasAccounts, todoCount, pipelines, updatedAt)
  }

  fun saveAccountTodos(
    context: Context,
    activeAccountIds: Set<String>,
    observedCounts: Map<String, Int>,
    now: Long = System.currentTimeMillis(),
  ) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (activeAccountIds.isEmpty()) {
      clear(context)
      return
    }
    val previous = runCatching { JSONObject(prefs.getString(KEY_ACCOUNT_TODOS, "{}") ?: "{}") }.getOrDefault(JSONObject())
    val merged = JSONObject()
    var total = 0
    for (id in activeAccountIds) {
      val count = observedCounts[id] ?: previous.optInt(id, 0)
      merged.put(id, count.coerceAtLeast(0))
      total += count.coerceAtLeast(0)
    }
    prefs.edit()
      .putBoolean(KEY_HAS_ACCOUNTS, true)
      .putInt(KEY_TODO_COUNT, total)
      .putString(KEY_ACCOUNT_TODOS, merged.toString())
      .putLong(KEY_UPDATED_AT, now)
      .apply()
  }

  fun saveTodoCount(context: Context, hasAccounts: Boolean, todoCount: Int, now: Long = System.currentTimeMillis()) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!hasAccounts) {
      clear(context)
      return
    }
    prefs.edit()
      .putBoolean(KEY_HAS_ACCOUNTS, true)
      .putInt(KEY_TODO_COUNT, todoCount.coerceAtLeast(0))
      .putLong(KEY_UPDATED_AT, now)
      .apply()
  }

  fun savePipelines(context: Context, pipelines: List<WidgetPipelineSummary>, now: Long = System.currentTimeMillis()) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val array = JSONArray()
    pipelines.take(MAX_PIPELINES).forEach { item ->
      array.put(
        JSONObject()
          .put("id", item.id)
          .put("projectId", item.projectId)
          .put("projectPath", item.projectPath)
          .put("ref", item.ref)
          .put("status", item.status)
      )
    }
    prefs.edit()
      .putString(KEY_PIPELINES, array.toString())
      .putLong(KEY_UPDATED_AT, now)
      .apply()
  }

  fun clear(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
  }

  private fun parsePipelines(raw: String?): List<WidgetPipelineSummary> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      buildList {
        for (i in 0 until minOf(array.length(), MAX_PIPELINES)) {
          val obj = array.optJSONObject(i) ?: continue
          val id = obj.optLong("id", 0L)
          val projectId = obj.optLong("projectId", 0L)
          if (id <= 0L || projectId <= 0L) continue
          add(
            WidgetPipelineSummary(
              id = id,
              projectId = projectId,
              projectPath = obj.optString("projectPath", ""),
              ref = obj.optString("ref", ""),
              status = obj.optString("status", ""),
            )
          )
        }
      }
    }.getOrDefault(emptyList())
  }
}
