package com.cboyxd.gitlab.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.cboyxd.gitlab.BuildConfig

/** A phone maker's extra battery manager and how to exempt the app from it. */
data class OemBatteryHelp(
  val maker: String,
  val steps: String,
  /** dontkillmyapp.com page for this maker. */
  val guideUrl: String,
)

/**
 * Battery advice for reliable notifications. Most phone makers add a battery manager on top of
 * Android's own optimization; both can stop background checks.
 */
object BatteryHelp {
  private const val GUIDES = "https://dontkillmyapp.com/"

  /** Instructions for [manufacturer] (Build.MANUFACTURER), or general ones for unknown makers. */
  fun forManufacturer(manufacturer: String): OemBatteryHelp {
    val m = manufacturer.trim().lowercase()
    return when {
      m in setOf("xiaomi", "redmi", "poco") -> OemBatteryHelp(
        "Xiaomi",
        "Settings → Apps → Manage apps → GitLab Mobile: turn on Autostart, and set Battery saver to No restrictions.",
        GUIDES + "xiaomi",
      )
      m == "samsung" -> OemBatteryHelp(
        "Samsung",
        "Settings → Battery → Background usage limits: add GitLab Mobile to Never sleeping apps, and set the app's battery use to Unrestricted.",
        GUIDES + "samsung",
      )
      m == "huawei" -> OemBatteryHelp(
        "Huawei",
        "Settings → Battery → App launch: turn off Manage automatically for GitLab Mobile and allow Auto-launch, Secondary launch and Run in background.",
        GUIDES + "huawei",
      )
      m == "honor" -> OemBatteryHelp(
        "Honor",
        "Settings → Battery → App launch: turn off Manage automatically for GitLab Mobile and allow it to run in the background.",
        GUIDES + "honor",
      )
      m == "oneplus" -> OemBatteryHelp(
        "OnePlus",
        "Settings → Battery → Battery optimization → GitLab Mobile: Don't optimize. Also allow background activity in the app's battery settings.",
        GUIDES + "oneplus",
      )
      m == "oppo" -> OemBatteryHelp(
        "OPPO",
        "Settings → Battery → GitLab Mobile: allow background activity and auto launch.",
        GUIDES + "oppo",
      )
      m == "realme" -> OemBatteryHelp(
        "realme",
        "Settings → Battery → GitLab Mobile: allow background activity and auto launch.",
        GUIDES + "realme",
      )
      m == "vivo" -> OemBatteryHelp(
        "vivo",
        "Settings → Battery → Background power consumption: allow GitLab Mobile to run in the background, and turn on Autostart.",
        GUIDES + "vivo",
      )
      m == "asus" -> OemBatteryHelp(
        "ASUS",
        "Mobile Manager → PowerMaster: allow GitLab Mobile to auto-start and run in the background.",
        GUIDES + "asus",
      )
      m == "motorola" -> OemBatteryHelp(
        "Motorola",
        "Settings → Apps → GitLab Mobile → Battery: Unrestricted.",
        GUIDES + "motorola",
      )
      m == "sony" -> OemBatteryHelp(
        "Sony",
        "Settings → Battery → STAMINA mode: exclude GitLab Mobile, or turn STAMINA mode off.",
        GUIDES + "sony",
      )
      m == "google" -> OemBatteryHelp(
        "Google",
        "Settings → Apps → GitLab Mobile → App battery usage: Unrestricted.",
        GUIDES + "google",
      )
      else -> OemBatteryHelp(
        manufacturer.trim().replaceFirstChar { it.uppercase() }.ifBlank { "This phone" },
        "Settings → Apps → GitLab Mobile → Battery: Unrestricted. Some makers add their own battery manager; the guide lists where to find it.",
        GUIDES,
      )
    }
  }

  /** The one-time card shows after instant notifications are turned on while the app is still optimized. */
  fun showCard(instantEnabled: Boolean, ignoringOptimizations: Boolean, dismissed: Boolean): Boolean =
    instantEnabled && !ignoringOptimizations && !dismissed

  /**
   * Asks Android to stop optimizing the app (a system dialog). The direct request is fine for the
   * GitHub build; [BuildConfig.REQUEST_BATTERY_EXEMPTION] is false for builds where store policy
   * forbids it, which open the battery optimization list instead.
   */
  @SuppressLint("BatteryLife")
  fun requestExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    val candidates = if (BuildConfig.REQUEST_BATTERY_EXEMPTION) listOf(direct, list, details) else listOf(list, details)
    for (intent in candidates) {
      if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
  }

  fun openGuide(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }
}

/** Remembers that the one-time battery card was dismissed. */
class BatteryCardState(context: Context) {
  private val prefs = context.getSharedPreferences("gitlab_battery_help", Context.MODE_PRIVATE)
  var dismissed: Boolean
    get() = prefs.getBoolean("card_dismissed", false)
    set(value) { prefs.edit().putBoolean("card_dismissed", value).apply() }
}
