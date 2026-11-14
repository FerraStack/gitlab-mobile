package com.cboyxd.gitlab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cboyxd.gitlab.data.model.GitLabInstance
import com.cboyxd.gitlab.data.storage.InstanceStore
import com.cboyxd.gitlab.data.storage.SecretStoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstanceStoreTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("GitLab", appName)
  }

  @Test
  fun `store starts empty and keeps a real instance`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = InstanceStore(context)
    assertNull(store.getActiveInstance())
    assertTrue(store.getInstances().isEmpty())

    val instance = GitLabInstance(
      name = "Company GitLab",
      url = "https://gitlab.company.internal",
      token = "glpat-test",
      username = "dev",
      isCustom = true,
      isActive = true
    )
    store.addInstance(instance)

    assertEquals("Company GitLab", store.getActiveInstance()?.name)
    assertEquals("https://gitlab.company.internal", store.getActiveInstance()?.url)

    store.deleteInstance(instance.id)
    assertNull(store.getActiveInstance())
  }

  @Test
  fun `previously seeded demo instances are dropped`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences("gitlab_mobile_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString(
      "saved_instances",
      """[{"id":"gitlab_com","name":"GitLab.com","url":"https://gitlab.com","token":"","username":"tanuki_developer","isActive":true,"isCustom":false,"version":"17.4.0-ee","statusOk":true},{"id":"real","name":"Kept","url":"https://gitlab.company.internal","token":"glpat","username":"dev","isActive":false,"isCustom":true,"version":"","statusOk":true}]"""
    ).commit()

    val store = InstanceStore(context)
    assertEquals(listOf("Kept"), store.getInstances().map { it.name })
    assertEquals("Kept", store.getActiveInstance()?.name)
  }

  @Test
  fun `guest servers saved by older versions are dropped and real accounts kept`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences("gitlab_mobile_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString(
      "saved_instances",
      """[{"id":"guest","name":"GitLab.com","url":"https://gitlab.com","token":"","isActive":true,"isCustom":false,"authKind":"public"},{"id":"real","name":"Company","url":"https://gitlab.company.internal","token":"glpat","username":"dev","isActive":false,"isCustom":true,"authKind":"pat"}]"""
    ).commit()

    val store = InstanceStore(context)
    assertEquals(listOf("Company"), store.getInstances().map { it.name })
    assertEquals("Company", store.getActiveInstance()?.name)
    // The cleanup is persisted, not just filtered in memory.
    assertEquals(listOf("Company"), InstanceStore(context).getInstances().map { it.name })
  }

  @Test
  fun `only guest entries leave no active account`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences("gitlab_mobile_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString(
      "saved_instances",
      """[{"id":"guest","name":"GitLab.com","url":"https://gitlab.com","token":"","isActive":true,"isCustom":false,"authKind":"public"}]"""
    ).commit()

    val store = InstanceStore(context)
    assertTrue(store.getInstances().isEmpty())
    assertNull(store.getActiveInstance())
  }

  @Test
  fun `legacy credentials migrate out of plaintext preferences`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences("gitlab_mobile_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString(
      "saved_instances",
      """[{"id":"real","name":"Kept","url":"https://gitlab.company.internal","token":"glpat-secret","username":"dev","isActive":true,"isCustom":true}]"""
    ).commit()

    val store = InstanceStore(context)
    assertEquals("glpat-secret", store.getActiveInstance()?.token)
    assertNull(prefs.getString("saved_instances", null))
    val encrypted = context.getSharedPreferences("gitlab_mobile_secrets", Context.MODE_PRIVATE)
      .getString("saved_instances", null)
    assertTrue(encrypted != null && !encrypted.contains("glpat-secret"))
  }

  @Test
  fun `accounts encrypted with a key this phone doesn't have can be reset`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // What a device transfer leaves behind: our ciphertext format, sealed with another key.
    val foreign = android.util.Base64.encodeToString(
      byteArrayOf(12) + ByteArray(12) { 7 } + ByteArray(48) { 3 },
      android.util.Base64.NO_WRAP,
    )
    context.getSharedPreferences("gitlab_mobile_secrets", Context.MODE_PRIVATE).edit().putString("saved_instances", foreign).commit()

    val store = InstanceStore(context)
    assertTrue(runCatching { store.getInstances() }.exceptionOrNull() is SecretStoreException)

    store.resetAll()
    assertTrue(store.getInstances().isEmpty())
    store.addInstance(GitLabInstance(name = "Again", url = "https://gitlab.com", token = "glpat-new", username = "dev", isActive = true))
    assertEquals(listOf("Again"), InstanceStore(context).getInstances().map { it.name })
  }

  @Test
  fun `patch updates only metadata without clobbering concurrent token refresh`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = InstanceStore(context)
    val initial = store.addInstance(
      GitLabInstance(
        name = "Work",
        url = "https://gitlab.company.internal",
        token = "access-1",
        refreshToken = "refresh-1",
        tokenExpiresAt = 1_000L,
        username = "dev",
        authKind = "oauth",
        oauthClientId = "client-1",
        isActive = true,
      )
    )

    // Simulate a concurrent OAuth token refresh finishing before metadata is persisted.
    store.patch(initial.id) { current ->
      current.copy(token = "access-2", refreshToken = "refresh-2", tokenExpiresAt = 9_999L)
    }

    // Metadata patch sees the latest record under the store lock and preserves the refreshed tokens.
    val patched = store.patch(initial.id) { latest ->
      latest.copy(version = "17.9.0-ee", statusOk = true)
    }

    assertNotNull(patched)
    assertEquals("access-2", patched!!.token)
    assertEquals("refresh-2", patched.refreshToken)
    assertEquals(9_999L, patched.tokenExpiresAt)
    assertEquals("17.9.0-ee", patched.version)
    assertTrue(patched.statusOk)

    val reloaded = InstanceStore(context).getInstances().single()
    assertEquals("access-2", reloaded.token)
    assertEquals("refresh-2", reloaded.refreshToken)
    assertEquals("17.9.0-ee", reloaded.version)
  }

  @Test
  fun `patch returns null when account was deleted`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = InstanceStore(context)
    assertNull(store.patch("missing") { it.copy(version = "1.0") })
  }

  @Test
  fun `damaged json in secret store fails with backup and refuses to overwrite`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = InstanceStore(context)
    val existing = store.addInstance(
      GitLabInstance(name = "Company", url = "https://gitlab.company.internal", token = "glpat-1", username = "dev", isActive = true)
    )
    // Corrupt the stored payload with damaged JSON (via plaintext fallback in secret prefs).
    val secretPrefs = context.getSharedPreferences(InstanceStore.SECRET_PREFS_NAME, Context.MODE_PRIVATE)
    val damaged = """[{"id":"${existing.id}","name":"Company","""
    secretPrefs.edit().putString(InstanceStore.KEY_INSTANCES, damaged).commit()

    val freshStore = InstanceStore(context)
    assertTrue(runCatching { freshStore.getInstances() }.exceptionOrNull() is SecretStoreException)

    val prefs = context.getSharedPreferences(InstanceStore.PREFS_NAME, Context.MODE_PRIVATE)
    assertEquals(damaged, prefs.getString(InstanceStore.KEY_INSTANCES_BACKUP, null))

    // Subsequent addInstance or saveInstances must NOT overwrite the damaged stored data.
    val newAccount = GitLabInstance(name = "New", url = "https://gitlab.com", token = "glpat-new", username = "other", isActive = true)
    assertTrue(runCatching { freshStore.addInstance(newAccount) }.exceptionOrNull() is SecretStoreException)
    assertTrue(runCatching { freshStore.saveInstances(listOf(newAccount)) }.exceptionOrNull() is SecretStoreException)
    assertFalse(secretPrefs.getString(InstanceStore.KEY_INSTANCES, "").orEmpty().contains("glpat-new"))

    // Explicit reset clears the corrupted active entry so new accounts can be saved while keeping backup.
    freshStore.resetAll()
    assertEquals(damaged, prefs.getString(InstanceStore.KEY_INSTANCES_BACKUP, null))
    assertTrue(freshStore.getInstances().isEmpty())
    freshStore.addInstance(newAccount)
    assertEquals(listOf("New"), InstanceStore(context).getInstances().map { it.name })
  }

  @Test
  fun `damaged json in legacy prefs fails with backup and does not wipe`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences(InstanceStore.PREFS_NAME, Context.MODE_PRIVATE)
    val damaged = "not-json-at-all"
    prefs.edit().putString(InstanceStore.KEY_INSTANCES, damaged).commit()

    val store = InstanceStore(context)
    assertTrue(runCatching { store.getInstances() }.exceptionOrNull() is SecretStoreException)
    assertEquals(damaged, prefs.getString(InstanceStore.KEY_INSTANCES_BACKUP, null))
    assertEquals(damaged, prefs.getString(InstanceStore.KEY_INSTANCES, null))
  }

  @Test
  fun `undecryptable ciphertext is backed up before resetAll`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val foreign = android.util.Base64.encodeToString(
      byteArrayOf(12) + ByteArray(12) { 7 } + ByteArray(48) { 3 },
      android.util.Base64.NO_WRAP,
    )
    context.getSharedPreferences(InstanceStore.SECRET_PREFS_NAME, Context.MODE_PRIVATE)
      .edit().putString(InstanceStore.KEY_INSTANCES, foreign).commit()

    val store = InstanceStore(context)
    assertTrue(runCatching { store.getInstances() }.exceptionOrNull() is SecretStoreException)

    val prefs = context.getSharedPreferences(InstanceStore.PREFS_NAME, Context.MODE_PRIVATE)
    assertEquals(foreign, prefs.getString(InstanceStore.KEY_INSTANCES_BACKUP, null))
    assertTrue(runCatching { store.saveInstances(emptyList()) }.exceptionOrNull() is SecretStoreException)
    assertEquals(foreign, context.getSharedPreferences(InstanceStore.SECRET_PREFS_NAME, Context.MODE_PRIVATE).getString(InstanceStore.KEY_INSTANCES, null))
  }
}
