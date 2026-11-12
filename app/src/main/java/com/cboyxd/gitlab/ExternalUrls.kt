package com.cboyxd.gitlab

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient

/**
 * Opens a URL outside the app. gitlab.com can be set to open here, so a link this app cannot
 * show has to name some other app; otherwise Android hands the same link straight back.
 */
internal fun openExternalUrl(context: Context, url: String) {
  if (url.isBlank()) return
  try {
    context.startActivity(externalView(context, url))
  } catch (_: ActivityNotFoundException) {
    // No browser installed; nothing sensible to do.
  }
}

private fun externalView(context: Context, url: String): Intent {
  val uri = Uri.parse(url)
  val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  if (!IncomingLinks.claimsHost(uri.host) || !viewResolvesToThisApp(context, uri)) return view
  val browser = CustomTabsClient.getPackageName(context, null)?.takeIf { it != context.packageName }
  if (browser != null) return view.setPackage(browser)
  return Intent.createChooser(view, null)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
}

/** True when Android would deliver this https link to this app (the user chose Open by default). */
internal fun viewResolvesToThisApp(context: Context, uri: Uri): Boolean {
  val probe = Intent(Intent.ACTION_VIEW, uri)
    .addCategory(Intent.CATEGORY_BROWSABLE)
    .addCategory(Intent.CATEGORY_DEFAULT)
  val resolved = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
  return resolved.activityInfo?.packageName == context.packageName
}
