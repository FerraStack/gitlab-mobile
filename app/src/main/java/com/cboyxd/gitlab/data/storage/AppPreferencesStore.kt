package com.cboyxd.gitlab.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLockTimeout(val seconds: Int, val label: String) {
  IMMEDIATE(0, "Immediately"),
  ONE_MINUTE(60, "After 1 minute"),
  FIVE_MINUTES(300, "After 5 minutes"),
  FIFTEEN_MINUTES(900, "After 15 minutes");

  companion object {
    fun fromName(raw: String?): AppLockTimeout =
      entries.firstOrNull { it.name == raw } ?: IMMEDIATE
  }
}

data class SecurityPreferences(
  val appLockEnabled: Boolean = false,
  val lockTimeout: AppLockTimeout = AppLockTimeout.IMMEDIATE,
  val screenSecurityEnabled: Boolean = false,
)

/** Pure policy helper determining whether the app lock screen must engage. */
object AppLockPolicy {
  fun shouldLock(
    enabled: Boolean,
    backgroundedAtMs: Long,
    nowMs: Long,
    timeout: AppLockTimeout,
    isInitialLaunch: Boolean,
  ): Boolean {
    if (!enabled) return false
    if (isInitialLaunch) return true
    if (backgroundedAtMs <= 0L) return false
    val elapsedMs = (nowMs - backgroundedAtMs).coerceAtLeast(0L)
    return elapsedMs >= timeout.seconds * 1000L
  }
}

class AppPreferencesStore(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _preferences = MutableStateFlow(readFromPrefs())
  val preferences: StateFlow<SecurityPreferences> = _preferences.asStateFlow()

  private fun readFromPrefs(): SecurityPreferences = SecurityPreferences(
    appLockEnabled = prefs.getBoolean(KEY_APP_LOCK, false),
    lockTimeout = AppLockTimeout.fromName(prefs.getString(KEY_LOCK_TIMEOUT, null)),
    screenSecurityEnabled = prefs.getBoolean(KEY_SCREEN_SECURITY, false),
  )

  fun setAppLockEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
    _preferences.value = _preferences.value.copy(appLockEnabled = enabled)
  }

  fun setLockTimeout(timeout: AppLockTimeout) {
    prefs.edit().putString(KEY_LOCK_TIMEOUT, timeout.name).apply()
    _preferences.value = _preferences.value.copy(lockTimeout = timeout)
  }

  fun setScreenSecurityEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_SCREEN_SECURITY, enabled).apply()
    _preferences.value = _preferences.value.copy(screenSecurityEnabled = enabled)
  }

  companion object {
    private const val PREFS_NAME = "gitlab_security_prefs"
    private const val KEY_APP_LOCK = "app_lock_enabled"
    private const val KEY_LOCK_TIMEOUT = "app_lock_timeout"
    private const val KEY_SCREEN_SECURITY = "screen_security_enabled"

    @Volatile
    private var instance: AppPreferencesStore? = null

    fun get(context: Context): AppPreferencesStore =
      instance ?: synchronized(this) {
        instance ?: AppPreferencesStore(context).also { instance = it }
      }
  }
}
