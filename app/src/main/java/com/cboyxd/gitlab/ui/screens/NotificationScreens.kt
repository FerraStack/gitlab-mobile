package com.cboyxd.gitlab.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cboyxd.gitlab.notifications.BatteryCardState
import com.cboyxd.gitlab.notifications.BatteryHelp
import com.cboyxd.gitlab.notifications.DataSaver
import com.cboyxd.gitlab.notifications.OemBatteryHelp
import com.cboyxd.gitlab.notifications.Finding
import com.cboyxd.gitlab.notifications.GitLabNotificationMonitor
import com.cboyxd.gitlab.notifications.InstantNotificationService
import com.cboyxd.gitlab.notifications.InstantSettings
import com.cboyxd.gitlab.notifications.NotificationDiagnostics
import com.cboyxd.gitlab.notifications.NotificationDiagnosticsReader
import com.cboyxd.gitlab.notifications.PollOutcome
import com.cboyxd.gitlab.notifications.PollRun
import com.cboyxd.gitlab.notifications.PollTrigger
import com.cboyxd.gitlab.notifications.Severity
import com.cboyxd.gitlab.notifications.formatDuration
import com.cboyxd.gitlab.ui.components.GlButton
import com.cboyxd.gitlab.ui.components.GlButtonKind
import com.cboyxd.gitlab.ui.components.GlCard
import com.cboyxd.gitlab.ui.components.GlDivider
import com.cboyxd.gitlab.ui.components.GlFilterRow
import com.cboyxd.gitlab.ui.components.GlRow
import com.cboyxd.gitlab.ui.components.GlSectionHeader
import com.cboyxd.gitlab.ui.components.GlTopBar
import com.cboyxd.gitlab.ui.components.LoadingState
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GlSpace
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Loads diagnostics on entry, whenever the app returns to the foreground and after "Check now". */
@Composable
private fun rememberDiagnostics(context: Context): Pair<NotificationDiagnostics?, () -> Unit> {
  var diagnostics by remember { mutableStateOf<NotificationDiagnostics?>(null) }
  var generation by remember { mutableIntStateOf(0) }
  LaunchedEffect(generation) { diagnostics = NotificationDiagnosticsReader.read(context) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { generation++ }
  return diagnostics to { generation++ }
}

internal fun openSystemNotificationSettings(context: Context) {
  val intent = if (Build.VERSION.SDK_INT >= 26) {
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
  } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
  runCatching { context.startActivity(intent) }
}

@Composable
fun NotificationSettingsRoute(onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
  val context = LocalContext.current
  val (diagnostics, reload) = rememberDiagnostics(context)
  val settings = remember { InstantSettings(context.applicationContext) }
  var instant by remember { mutableStateOf(settings.enabled) }
  var interval by remember { mutableIntStateOf(settings.intervalSeconds) }
  val card = remember { BatteryCardState(context.applicationContext) }
  var cardDismissed by remember { mutableStateOf(card.dismissed) }
  NotificationSettingsScreen(
    diagnostics = diagnostics,
    onBack = onBack,
    onOpenSystemSettings = { openSystemNotificationSettings(context) },
    onOpenDiagnostics = onOpenDiagnostics,
    instantEnabled = instant,
    intervalSeconds = interval,
    onSetInstant = {
      settings.enabled = it
      instant = it
      InstantNotificationService.sync(context)
      reload()
    },
    onSetInterval = {
      settings.intervalSeconds = it
      interval = it
      // The running service reads the interval on its next round; wake it so that's now.
      InstantNotificationService.sync(context)
    },
    battery = BatteryHelp.forManufacturer(Build.MANUFACTURER),
    showBatteryCard = diagnostics != null && BatteryHelp.showCard(instant, diagnostics.ignoringBatteryOptimizations, cardDismissed),
    onAllowBackground = { BatteryHelp.requestExemption(context) },
    onOpenGuide = { BatteryHelp.openGuide(context, it) },
    onDismissBatteryCard = {
      card.dismissed = true
      cardDismissed = true
    },
  )
}

@Composable
fun NotificationDiagnosticsRoute(onBack: () -> Unit) {
  val context = LocalContext.current
  val (diagnostics, reload) = rememberDiagnostics(context)
  val scope = rememberCoroutineScope()
  var checking by remember { mutableStateOf(false) }
  NotificationDiagnosticsScreen(
    diagnostics = diagnostics,
    checking = checking,
    onBack = onBack,
    onCheckNow = {
      if (!checking) scope.launch {
        checking = true
        try {
          runCatching { GitLabNotificationMonitor.poll(context.applicationContext, PollTrigger.MANUAL) }
        } finally {
          checking = false
          reload()
        }
      }
    },
    onOpenSystemSettings = { openSystemNotificationSettings(context) },
  )
}

@Composable
fun NotificationSettingsScreen(
  diagnostics: NotificationDiagnostics?,
  onBack: () -> Unit,
  onOpenSystemSettings: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  modifier: Modifier = Modifier,
  instantEnabled: Boolean = false,
  intervalSeconds: Int = InstantSettings.DEFAULT_INTERVAL,
  onSetInstant: (Boolean) -> Unit = {},
  onSetInterval: (Int) -> Unit = {},
  battery: OemBatteryHelp? = null,
  showBatteryCard: Boolean = false,
  onAllowBackground: () -> Unit = {},
  onOpenGuide: (String) -> Unit = {},
  onDismissBatteryCard: () -> Unit = {},
) {
  Column(modifier = modifier.fillMaxSize().background(Gl.colors.bg)) {
    GlTopBar(title = "Notifications", onBack = onBack)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = GlSpace.group)) {
      item(key = "instant") {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = instantEnabled, role = Role.Switch, onValueChange = onSetInstant)
            .padding(horizontal = GlSpace.margin, vertical = 12.dp)
            .testTag("instant_toggle"),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Instant notifications", style = Gl.type.rowTitle, color = Gl.colors.textPrimary)
            Text(
              "Checks every $intervalSeconds s while the screen is on, and every 5–15 minutes while it's off. " +
                "Shows a silent notification while it runs and uses more battery.",
              style = Gl.type.meta,
              color = Gl.colors.textSecondary,
            )
          }
          Spacer(Modifier.width(GlSpace.margin))
          Switch(checked = instantEnabled, onCheckedChange = null)
        }
        if (instantEnabled) {
          Text(
            "Check every",
            style = Gl.type.label,
            color = Gl.colors.textSecondary,
            modifier = Modifier.padding(start = GlSpace.margin, top = 4.dp),
          )
          GlFilterRow(
            options = InstantSettings.INTERVAL_CHOICES,
            selected = intervalSeconds,
            label = { "$it s" },
            onSelect = onSetInterval,
            modifier = Modifier.padding(bottom = GlSpace.tight),
          )
        }
        if (showBatteryCard) BatteryCard(onAllow = onAllowBackground, onDismiss = onDismissBatteryCard)
        GlDivider()
      }
      item(key = "summary") {
        val problems = diagnostics?.findings().orEmpty().filter { it.severity != Severity.OK }
        GlRow(
          title = "Diagnostics",
          subtitle = when {
            diagnostics == null -> "Checking…"
            problems.isEmpty() -> "Nothing is holding notifications back"
            problems.size == 1 -> problems.first().title
            else -> "${problems.size} things can delay notifications"
          },
          leading = { SeverityIcon(problems.minOfOrNull { it.severity } ?: Severity.OK) },
          onClick = onOpenDiagnostics,
          onClickLabel = "Open diagnostics",
          modifier = Modifier.testTag("notification_diagnostics_row"),
        )
        GlDivider(inset = GlSpace.margin)
        GlRow(
          title = "Android notification settings",
          subtitle = "Permission, categories and sounds",
          leading = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = Gl.colors.textSecondary) },
          onClick = onOpenSystemSettings,
        )
      }
      if (battery != null) item(key = "battery") {
        BatterySection(
          optimized = diagnostics?.ignoringBatteryOptimizations?.not(),
          help = battery,
          onAllow = onAllowBackground,
          onOpenGuide = onOpenGuide,
        )
      }
      item(key = "how") {
        GlSectionHeader(title = "How notifications work")
        Text(
          text = "GitLab has no push service for other apps, so GitLab Mobile checks your to-dos and merge requests " +
            "itself: every minute while the app is open, and about every 15 minutes in the background when Android " +
            "allows it. Battery optimization, the standby bucket and the phone maker's battery manager can delay " +
            "background checks by hours. Instant notifications keep checking with a foreground service instead. " +
            "Diagnostics shows what applies to this phone.",
          style = Gl.type.bodySmall,
          color = Gl.colors.textSecondary,
          modifier = Modifier.padding(horizontal = GlSpace.margin),
        )
      }
    }
  }
}

@Composable
fun NotificationDiagnosticsScreen(
  diagnostics: NotificationDiagnostics?,
  checking: Boolean,
  onBack: () -> Unit,
  onCheckNow: () -> Unit,
  onOpenSystemSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize().background(Gl.colors.bg)) {
    GlTopBar(title = "Diagnostics", overline = "Notifications", onBack = onBack)
    if (diagnostics == null) {
      LoadingState(label = "Reading notification state")
      return@Column
    }
    val d = diagnostics
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("notification_diagnostics"),
      contentPadding = PaddingValues(bottom = GlSpace.group),
    ) {
      item(key = "findings") {
        GlCard(modifier = Modifier.padding(horizontal = GlSpace.margin, vertical = GlSpace.tight)) {
          val findings = d.findings()
          findings.forEachIndexed { index, finding ->
            if (index > 0) GlDivider(inset = 56.dp)
            FindingRow(finding)
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = GlSpace.margin, vertical = GlSpace.tight),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          GlButton(text = "Check now", onClick = onCheckNow, loading = checking, icon = Icons.Outlined.Refresh, modifier = Modifier.testTag("check_now"))
          Spacer(Modifier.width(GlSpace.tight))
          GlButton(text = "Android settings", onClick = onOpenSystemSettings, kind = GlButtonKind.Secondary)
        }
      }
      item(key = "schedule") {
        GlSectionHeader(title = "Background checks")
        val last = d.backgroundRuns.firstOrNull()
        ValueRow("Last background check", last?.let { "${formatDuration(d.now - it.startedAt)} ago" } ?: "None yet")
        ValueRow("Typical interval", d.typicalInterval?.let { "every ${formatDuration(it)}" } ?: "Not enough checks yet")
        ValueRow("Longest gap", d.longestGap?.let { formatDuration(it) } ?: "—")
        val next = d.scheduled?.nextRunAt
        ValueRow(
          "Next planned check",
          when {
            d.scheduled == null -> "Not scheduled"
            next == null -> d.scheduled.state.lowercase().replaceFirstChar { it.uppercase() }
            next >= d.now -> "in ${formatDuration(next - d.now)}"
            else -> "overdue by ${formatDuration(d.now - next)}"
          },
        )
        d.scheduled?.stopReason?.let { ValueRow("Last stopped by Android", it) }
        ValueRow(
          "Instant notifications",
          when {
            !d.instantEnabled -> "Off"
            !d.instantRunning -> "On, not running"
            else -> d.instantRuns.firstOrNull()?.let { "Running, checked ${formatDuration(d.now - it.startedAt)} ago" } ?: "Running"
          },
          bad = d.instantEnabled && !d.instantRunning,
        )
      }
      if (d.accounts.isNotEmpty()) item(key = "accounts") {
        GlSectionHeader(title = "Accounts")
        d.accounts.forEach { account ->
          GlRow(
            title = account.name,
            subtitle = when {
              account.lastCheckAt == null -> "Not checked yet"
              account.lastOk == true -> "Checked ${formatDuration(d.now - account.lastCheckAt)} ago"
              else -> "Failed ${formatDuration(d.now - account.lastCheckAt)} ago: ${account.lastError ?: "unknown error"}" +
                (account.lastSuccessAt?.let { " · last success ${formatDuration(d.now - it)} ago" } ?: "")
            },
            leading = { SeverityIcon(if (account.lastOk == false) Severity.PROBLEM else Severity.OK) },
          )
        }
      }
      item(key = "system") {
        GlSectionHeader(title = "This phone")
        ValueRow("Notification permission", if (d.permissionGranted) "Allowed" else "Not allowed", bad = !d.permissionGranted)
        ValueRow("App notifications", if (d.appNotificationsEnabled) "On" else "Off", bad = !d.appNotificationsEnabled)
        d.channels.forEach { ValueRow(it.name, if (it.enabled) "On" else "Off", bad = !it.enabled) }
        ValueRow("Standby bucket", d.standbyBucket?.label ?: "Not reported (Android 8 or older)", bad = d.standbyBucket?.ordinal?.let { it >= 3 } == true)
        ValueRow("Battery optimization", if (d.ignoringBatteryOptimizations) "Not optimized" else "Optimized", bad = !d.ignoringBatteryOptimizations)
        d.backgroundRestricted?.let { ValueRow("Background use", if (it) "Restricted" else "Allowed", bad = it) }
        ValueRow(
          "Data Saver",
          when (d.dataSaver) {
            DataSaver.OFF -> "Off"
            DataSaver.ON -> "On"
            DataSaver.ON_ALLOWED -> "On, app allowed"
          },
          bad = d.dataSaver == DataSaver.ON,
        )
        ValueRow("Battery Saver", if (d.powerSaveMode) "On" else "Off", bad = d.powerSaveMode)
      }
      item(key = "history-header") {
        GlSectionHeader(title = "Recent checks")
        if (d.history.isEmpty()) {
          Text(
            "No checks recorded yet.",
            style = Gl.type.bodySmall,
            color = Gl.colors.textSecondary,
            modifier = Modifier.padding(horizontal = GlSpace.margin),
          )
        }
      }
      d.history.forEachIndexed { index, run ->
        item(key = "run-$index-${run.startedAt}") { HistoryRow(run, d.now) }
      }
    }
  }
}

@Composable
private fun SeverityIcon(severity: Severity) {
  val c = Gl.colors
  val (icon, tint, label) = when (severity) {
    Severity.PROBLEM -> Triple(Icons.Outlined.ErrorOutline, c.danger, "Problem")
    Severity.WARNING -> Triple(Icons.Outlined.WarningAmber, c.warning, "Warning")
    Severity.OK -> Triple(Icons.Outlined.CheckCircleOutline, c.success, "OK")
  }
  Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
}

@Composable
private fun FindingRow(finding: Finding) {
  Row(modifier = Modifier.fillMaxWidth().padding(GlSpace.margin), verticalAlignment = Alignment.Top) {
    SeverityIcon(finding.severity)
    Spacer(Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(finding.title, style = Gl.type.label, color = Gl.colors.textPrimary)
      Text(finding.detail, style = Gl.type.meta, color = Gl.colors.textSecondary)
    }
  }
}

@Composable
private fun ValueRow(label: String, value: String, bad: Boolean = false) {
  GlRow(
    title = label,
    trailing = {
      Text(
        value,
        style = Gl.type.bodySmall,
        color = if (bad) Gl.colors.warning else Gl.colors.textSecondary,
        maxLines = 2,
        modifier = Modifier.padding(start = GlSpace.tight),
      )
    },
  )
}

@Composable
private fun HistoryRow(run: PollRun, now: Long) {
  val time = remember(run.startedAt) { SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(run.startedAt)) }
  val severity = when (run.outcome) {
    PollOutcome.OK, PollOutcome.UNCHANGED, PollOutcome.NO_ACCOUNTS -> Severity.OK
    PollOutcome.BUSY, PollOutcome.PARTIAL -> Severity.WARNING
    PollOutcome.FAILED -> Severity.PROBLEM
  }
  val details = buildList {
    add(run.trigger.label)
    add(run.outcome.label)
    if (run.durationMs > 0) add(String.format(Locale.US, "%.1f s", run.durationMs / 1000.0))
    if (run.delivered > 0) add("${run.delivered} shown")
  }.joinToString(" · ")
  val errors = run.accounts.filter { !it.ok }.joinToString("; ") { "${it.accountName}: ${it.error ?: "error"}" }
  GlRow(
    title = "$time · ${formatDuration(now - run.startedAt)} ago",
    subtitle = listOf(details, errors).filter { it.isNotBlank() }.joinToString("\n"),
    leading = { SeverityIcon(severity) },
  )
}

@Composable
private fun BatteryCard(onAllow: () -> Unit, onDismiss: () -> Unit) {
  GlCard(
    modifier = Modifier.padding(horizontal = GlSpace.margin, vertical = GlSpace.tight).testTag("battery_card"),
    contentPadding = PaddingValues(GlSpace.margin),
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Icon(Icons.Outlined.BatteryAlert, contentDescription = null, tint = Gl.colors.warning, modifier = Modifier.size(24.dp))
      Spacer(Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text("Keep instant notifications running", style = Gl.type.label, color = Gl.colors.textPrimary)
        Text(
          "Android optimizes GitLab Mobile's battery use, so it can pause checks while the phone is idle. " +
            "Allow background use to keep notifications on time.",
          style = Gl.type.meta,
          color = Gl.colors.textSecondary,
        )
      }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = GlSpace.tight), horizontalArrangement = Arrangement.End) {
      GlButton(text = "Not now", onClick = onDismiss, kind = GlButtonKind.Ghost)
      Spacer(Modifier.width(GlSpace.tight))
      GlButton(text = "Allow", onClick = onAllow, modifier = Modifier.testTag("battery_allow"))
    }
  }
}

@Composable
private fun BatterySection(optimized: Boolean?, help: OemBatteryHelp, onAllow: () -> Unit, onOpenGuide: (String) -> Unit) {
  GlSectionHeader(title = "Battery")
  val allow: (@Composable RowScope.() -> Unit)? = if (optimized == true) {
    { GlButton(text = "Allow", onClick = onAllow, kind = GlButtonKind.Secondary) }
  } else null
  GlRow(
    title = "Battery optimization",
    subtitle = when (optimized) {
      null -> "Checking…"
      true -> "On: Android can postpone checks while the phone is idle"
      false -> "Off: checks aren't postponed by Doze"
    },
    leading = {
      Icon(
        Icons.Outlined.BatteryAlert,
        contentDescription = null,
        tint = if (optimized == true) Gl.colors.warning else Gl.colors.textSecondary,
      )
    },
    trailing = allow,
  )
  Column(modifier = Modifier.padding(horizontal = GlSpace.margin, vertical = GlSpace.tight)) {
    Text("${help.maker} battery manager", style = Gl.type.label, color = Gl.colors.textPrimary)
    Text(help.steps, style = Gl.type.bodySmall, color = Gl.colors.textSecondary)
    GlButton(
      text = "Open the guide on dontkillmyapp.com",
      onClick = { onOpenGuide(help.guideUrl) },
      kind = GlButtonKind.Ghost,
      icon = Icons.AutoMirrored.Outlined.OpenInNew,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}
