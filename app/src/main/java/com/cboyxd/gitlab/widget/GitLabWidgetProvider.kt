package com.cboyxd.gitlab.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.cboyxd.gitlab.MainActivity
import com.cboyxd.gitlab.R

/**
 * Home-screen widget showing the cached pending to-do count and latest pipelines.
 * Never triggers network requests on its own; fed by [WidgetStore] updates from dashboard loads
 * and background notification checks.
 */
class GitLabWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    val snapshot = WidgetStore.read(context)
    for (widgetId in appWidgetIds) {
      appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, snapshot))
    }
  }

  companion object {
    const val ACTION_OPEN_INBOX = "com.cboyxd.gitlab.WIDGET_OPEN_INBOX"
    const val ACTION_OPEN_PIPELINE = "com.cboyxd.gitlab.WIDGET_OPEN_PIPELINE"
    const val EXTRA_PROJECT_ID = "widget_project_id"
    const val EXTRA_PIPELINE_ID = "widget_pipeline_id"

    private val ROW_IDS = intArrayOf(
      R.id.widget_pipeline_1,
      R.id.widget_pipeline_2,
      R.id.widget_pipeline_3,
    )
    private val STATUS_IDS = intArrayOf(
      R.id.widget_pipeline_1_status,
      R.id.widget_pipeline_2_status,
      R.id.widget_pipeline_3_status,
    )
    private val LABEL_IDS = intArrayOf(
      R.id.widget_pipeline_1_label,
      R.id.widget_pipeline_2_label,
      R.id.widget_pipeline_3_label,
    )

    fun refreshAll(context: Context) {
      runCatching {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, GitLabWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val snapshot = WidgetStore.read(context)
        for (widgetId in ids) {
          manager.updateAppWidget(widgetId, buildRemoteViews(context, snapshot))
        }
      }
    }

    internal fun buildRemoteViews(context: Context, snapshot: WidgetSnapshot): RemoteViews {
      val views = RemoteViews(context.packageName, R.layout.gitlab_widget)
      val openInboxIntent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_INBOX
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
      val openInboxPending = PendingIntent.getActivity(
        context,
        1000,
        openInboxIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      views.setOnClickPendingIntent(R.id.widget_header, openInboxPending)
      views.setOnClickPendingIntent(R.id.widget_todo_count, openInboxPending)

      if (!snapshot.hasAccounts) {
        views.setTextViewText(R.id.widget_todo_count, "Guest")
        views.setTextViewText(R.id.widget_empty, "Sign in to see to-dos and pipelines")
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }
        return views
      }

      val todoLabel = when {
        snapshot.todoCount > 99 -> "99+ to-dos"
        snapshot.todoCount == 1 -> "1 to-do"
        else -> "${snapshot.todoCount} to-dos"
      }
      views.setTextViewText(R.id.widget_todo_count, todoLabel)

      val pipelines = snapshot.pipelines.take(ROW_IDS.size)
      if (pipelines.isEmpty()) {
        views.setTextViewText(R.id.widget_empty, "No recent pipelines")
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }
      } else {
        views.setViewVisibility(R.id.widget_empty, View.GONE)
        for (i in ROW_IDS.indices) {
          val row = pipelines.getOrNull(i)
          if (row == null) {
            views.setViewVisibility(ROW_IDS[i], View.GONE)
            continue
          }
          views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
          views.setTextViewText(STATUS_IDS[i], statusLabel(row.status))
          views.setTextColor(STATUS_IDS[i], statusColor(context, row.status))
          val name = row.projectPath.substringAfterLast('/').ifBlank { "#${row.id}" }
          val label = listOfNotNull(
            name,
            row.ref.takeIf { it.isNotBlank() },
          ).joinToString(" · ")
          views.setTextViewText(LABEL_IDS[i], label)
          val openPipelineIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_PIPELINE
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PROJECT_ID, row.projectId)
            putExtra(EXTRA_PIPELINE_ID, row.id)
          }
          val openPipelinePending = PendingIntent.getActivity(
            context,
            1100 + i,
            openPipelineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
          views.setOnClickPendingIntent(ROW_IDS[i], openPipelinePending)
        }
      }
      return views
    }

    internal fun statusLabel(status: String): String = when (status.lowercase()) {
      "success" -> "Passed"
      "failed" -> "Failed"
      "running" -> "Running"
      "pending" -> "Pending"
      "canceled" -> "Canceled"
      "skipped" -> "Skipped"
      "manual" -> "Manual"
      else -> status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }.ifBlank { "Unknown" }
    }

    private fun statusColor(context: Context, status: String): Int {
      val colorRes = when (status.lowercase()) {
        "success" -> R.color.widget_success
        "failed" -> R.color.widget_danger
        "running", "pending", "preparing", "waiting_for_resource" -> R.color.widget_accent
        "manual", "scheduled" -> R.color.widget_warning
        else -> R.color.widget_text_secondary
      }
      return ContextCompat.getColor(context, colorRes)
    }
  }
}
