package com.cboyxd.gitlab.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Saved servers, encrypted with an Android Keystore key. Reads are served from memory after the
 * first decrypt; every method blocks on disk or Keystore, so call it off the main thread.
 */
class InstanceStore(context: Context) {

  private val prefs: SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val secretStore = AndroidKeyStoreSecretStore(context, SECRET_PREFS_NAME)

  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val listType = Types.newParameterizedType(List::class.java, GitLabInstance::class.java)
  private val adapter = moshi.adapter<List<GitLabInstance>>(listType)

  @Volatile
  private var cache: List<GitLabInstance>? = null
  @Volatile
  private var unreadable: Boolean = false

  /** Returns the in-memory cached instance without touching disk or Keystore, or null if not yet loaded. */
  fun cachedInstance(instanceId: String): GitLabInstance? =
    cache?.firstOrNull { it.id == instanceId }

  @Synchronized
  fun getInstances(): List<GitLabInstance> {
    cache?.let { return it }
    val fromSecrets = try {
      secretStore.get(KEY_INSTANCES)
    } catch (error: SecretStoreException) {
      backupUnreadable(secretStore.rawGet(KEY_INSTANCES))
      unreadable = true
      throw error
    }
    val legacy = if (fromSecrets == null) prefs.getString(KEY_INSTANCES, null) else null
    val json = fromSecrets ?: legacy
    if (json == null) {
      unreadable = false
      cache = emptyList()
      return emptyList()
    }
    val parsed = try {
      adapter.fromJson(json) ?: throw JsonDataException("Saved accounts JSON decoded to null.")
    } catch (error: Exception) {
      backupUnreadable(json)
      unreadable = true
      throw SecretStoreException("Unable to read saved accounts.", error)
    }
    if (legacy != null) {
      secretStore.put(KEY_INSTANCES, legacy)
      prefs.edit().remove(KEY_INSTANCES).commit()
    }
    unreadable = false
    // Guest browsing is the app's default now, not a saved server; drop entries older versions saved.
    val cleaned = parsed.filterNot { it.isSeededDemo() || it.authKind == GitLabAuth.KIND_PUBLIC }
    cache = cleaned
    if (cleaned.size != parsed.size) writeInstances(cleaned)
    return cleaned
  }

  @Synchronized
  fun saveInstances(instances: List<GitLabInstance>) {
    // Refuse to overwrite unreadable stored data unless resetAll() was explicitly called.
    if (unreadable || cache == null) getInstances()
    writeInstances(instances)
  }

  private fun writeInstances(instances: List<GitLabInstance>) {
    secretStore.put(KEY_INSTANCES, adapter.toJson(instances))
    prefs.edit().remove(KEY_INSTANCES).commit()
    unreadable = false
    cache = instances
  }

  private fun backupUnreadable(raw: String?) {
    if (raw != null) {
      prefs.edit().putString(KEY_INSTANCES_BACKUP, raw).commit()
    }
  }

  @Synchronized
  fun getActiveInstance(): GitLabInstance? {
    val instances = getInstances()
    if (instances.isEmpty()) return null
    val activeId = prefs.getString(KEY_ACTIVE_ID, null)
    return instances.firstOrNull { it.id == activeId }
      ?: instances.firstOrNull { it.isActive }
      ?: instances.first()
  }

  @Synchronized
  fun setActiveInstance(instanceId: String) {
    val instances = getInstances().map { it.copy(isActive = it.id == instanceId) }
    writeInstances(instances)
    prefs.edit().putString(KEY_ACTIVE_ID, instanceId).commit()
  }

  /**
   * Adds a server, or replaces the saved one with the same URL and username so signing in again
   * refreshes the credentials instead of creating a duplicate entry.
   */
  @Synchronized
  fun addInstance(instance: GitLabInstance): GitLabInstance {
    val current = getInstances()
    val existing = current.firstOrNull { it.sameAccountAs(instance) }
    val active = instance.isActive || current.isEmpty()
    val toSave = instance.copy(id = existing?.id ?: instance.id, isActive = active)
    val others = current.filterNot { it.id == toSave.id }
    val updated = if (active) others.map { it.copy(isActive = false) } + toSave else others + toSave
    writeInstances(updated)
    if (active) prefs.edit().putString(KEY_ACTIVE_ID, toSave.id).commit()
    return toSave
  }

  /** Updates a saved server. Returns false when it was deleted meanwhile. */
  @Synchronized
  fun updateInstance(instance: GitLabInstance): Boolean {
    val current = getInstances()
    if (current.none { it.id == instance.id }) return false
    writeInstances(current.map { if (it.id == instance.id) instance.copy(isActive = it.isActive) else it })
    return true
  }

  /**
   * Atomically transforms a saved server under the store lock so concurrent metadata updates and
   * token refreshes never clobber each other's fields. Returns the latest record, or null if deleted.
   */
  @Synchronized
  fun patch(instanceId: String, transform: (GitLabInstance) -> GitLabInstance): GitLabInstance? {
    val current = getInstances()
    val index = current.indexOfFirst { it.id == instanceId }
    if (index < 0) return null
    val existing = current[index]
    val patched = transform(existing).copy(id = existing.id, isActive = existing.isActive)
    if (patched != existing) {
      val updated = current.toMutableList().apply { set(index, patched) }
      writeInstances(updated)
    }
    return patched
  }

  /** Returns the saved instance that would be replaced by [instance], if any. */
  @Synchronized
  fun findExistingAccount(instance: GitLabInstance): GitLabInstance? =
    runCatching { getInstances().firstOrNull { it.sameAccountAs(instance) } }.getOrNull()

  fun getGitLabComClientId(): String = prefs.getString(KEY_OAUTH_CLIENT_ID, "").orEmpty()

  fun setGitLabComClientId(clientId: String) {
    prefs.edit().putString(KEY_OAUTH_CLIENT_ID, clientId.trim()).apply()
  }

  /** Removes every saved account and the key that encrypts them; see [AndroidKeyStoreSecretStore.reset]. */
  @Synchronized
  fun resetAll() {
    val backup = prefs.getString(KEY_INSTANCES_BACKUP, null)
    prefs.edit().apply {
      clear()
      if (backup != null) putString(KEY_INSTANCES_BACKUP, backup)
    }.commit()
    secretStore.reset()
    unreadable = false
    cache = null
  }

  @Synchronized
  fun deleteInstance(instanceId: String) {
    val remaining = getInstances().filter { it.id != instanceId }
    val finalList = when {
      remaining.isEmpty() -> emptyList()
      remaining.none { it.isActive } -> remaining.mapIndexed { index, instance ->
        if (index == 0) instance.copy(isActive = true) else instance
      }
      else -> remaining
    }
    writeInstances(finalList)
    if (finalList.isEmpty()) {
      prefs.edit().remove(KEY_ACTIVE_ID).commit()
    } else if (prefs.getString(KEY_ACTIVE_ID, null) == instanceId) {
      prefs.edit().putString(KEY_ACTIVE_ID, finalList.first { it.isActive }.id).commit()
    }
  }

  private fun GitLabInstance.sameAccountAs(other: GitLabInstance): Boolean {
    val sameUrl = url.trim().trimEnd('/').equals(other.url.trim().trimEnd('/'), ignoreCase = true)
    if (!sameUrl) return false
    // One public (no sign-in) entry per server.
    if (token.isBlank() && other.token.isBlank()) return true
    if (username.isBlank() || other.username.isBlank()) return false
    return username.equals(other.username, ignoreCase = true)
  }

  private fun GitLabInstance.isSeededDemo(): Boolean {
    if (token.isNotBlank()) return false
    return id in SEEDED_IDS && username in SEEDED_USERS
  }

  companion object {
    @Volatile private var shared: InstanceStore? = null
    @Volatile private var sharedApp: Context? = null

    fun get(context: Context): InstanceStore {
      val app = context.applicationContext
      shared?.takeIf { sharedApp === app }?.let { return it }
      return synchronized(this) {
        shared?.takeIf { sharedApp === app } ?: InstanceStore(app).also {
          shared = it
          sharedApp = app
        }
      }
    }

    const val PREFS_NAME = "gitlab_mobile_prefs"
    const val SECRET_PREFS_NAME = "gitlab_mobile_secrets"
    const val KEY_INSTANCES = "saved_instances"
    const val KEY_INSTANCES_BACKUP = "saved_instances_backup"
    const val KEY_ACTIVE_ID = "active_instance_id"
    const val KEY_OAUTH_CLIENT_ID = "gitlab_com_oauth_client_id"
    val SEEDED_IDS = setOf("gitlab_com", "gnome_gitlab", "freedesktop_gitlab")
    val SEEDED_USERS = setOf("tanuki_developer", "gnome_contributor", "desktop_dev")
  }
}
