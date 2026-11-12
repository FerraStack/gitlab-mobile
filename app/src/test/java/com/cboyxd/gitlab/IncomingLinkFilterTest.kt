package com.cboyxd.gitlab

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The manifest claims gitlab.com https links and shared text, and does not auto-verify. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IncomingLinkFilterTest {
  private lateinit var app: Application

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
  }

  @Test
  fun `gitlab com https links resolve and other hosts do not`() {
    assertTrue(resolves(view("https://gitlab.com/gitlab-org/gitlab/-/merge_requests/1")))
    assertTrue(resolves(view("https://gitlab.com")))
    assertTrue(resolves(view("https://www.gitlab.com/explore")))
    assertTrue(resolves(view("com.cboyxd.gitlab://oauth?code=abc&state=xyz")))
    assertFalse(resolves(view("https://gitlab.example.com/group/proj/-/issues/1")))
    assertFalse(resolves(view("http://gitlab.com/group/proj")))
    assertFalse(resolves(view("https://gitlab.com.evil.com/group/proj")))
    assertFalse(resolves(view("https://about.gitlab.com/blog")))
  }

  @Test
  fun `shared plain text resolves and other share types do not`() {
    val text = Intent(Intent.ACTION_SEND).setType("text/plain").addCategory(Intent.CATEGORY_DEFAULT)
    val image = Intent(Intent.ACTION_SEND).setType("image/png").addCategory(Intent.CATEGORY_DEFAULT)
    assertTrue(resolves(text))
    assertFalse(resolves(image))
  }

  @Test
  fun `https filters are not auto verified`() {
    val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
      .first { it.isFile }
      .readText()
    assertFalse(manifest.contains("autoVerify"))
    assertTrue(manifest.contains("android:host=\"gitlab.com\""))
    assertTrue(manifest.contains("android:host=\"www.gitlab.com\""))
    assertTrue(manifest.contains("android:mimeType=\"text/plain\""))
  }

  private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    .addCategory(Intent.CATEGORY_DEFAULT)
    .addCategory(Intent.CATEGORY_BROWSABLE)

  private fun resolves(intent: Intent) = app.packageManager.queryIntentActivities(intent, 0).any {
    it.activityInfo?.name == MainActivity::class.java.name
  }
}
