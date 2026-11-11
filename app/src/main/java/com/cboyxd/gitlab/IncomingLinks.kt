package com.cboyxd.gitlab

import com.cboyxd.gitlab.data.auth.GitLabAuth

/**
 * Links another app asked this one to open.
 *
 * gitlab.com and www.gitlab.com arrive as VIEW intents. Any other server arrives as shared
 * text, because the manifest cannot list hosts it does not know. The OAuth redirect is not a web link.
 */
internal object IncomingLinks {
  private const val ACTION_VIEW = "android.intent.action.VIEW"
  private const val ACTION_SEND = "android.intent.action.SEND"
  private const val TRAILING = ".,;:!?)]}>\"'"

  private val HTTP_URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

  /**
   * The http(s) URL to route, or null when [data] is the OAuth redirect or there is no web URL.
   * VIEW trusts the intent data only. SEND reads the shared text, which may be a sentence around the URL.
   */
  fun webUrl(action: String?, data: String?, sharedText: String?): String? {
    if (isOAuthRedirect(data)) return null
    return when (action) {
      ACTION_VIEW -> data?.trim()?.takeIf(::isHttpUrl)
      ACTION_SEND -> sharedText?.let(::httpUrlIn) ?: data?.trim()?.takeIf(::isHttpUrl)
      else -> null
    }
  }

  /** True for [GitLabAuth.REDIRECT_URI], including the query GitLab appends. */
  fun isOAuthRedirect(data: String?): Boolean {
    val text = data?.trim().orEmpty()
    if (!text.startsWith(GitLabAuth.REDIRECT_URI, ignoreCase = true)) return false
    if (text.length == GitLabAuth.REDIRECT_URI.length) return true
    return text[GitLabAuth.REDIRECT_URI.length] in "?/#"
  }

  /**
   * Same GitLab server. www.gitlab.com and gitlab.com match, as in [GitLabAuth.isGitLabCom].
   * Every other host has to be equal on its own.
   */
  fun sameServer(linkHost: String?, instanceHost: String?): Boolean {
    val link = bareHost(linkHost) ?: return false
    val instance = bareHost(instanceHost) ?: return false
    if (link.equals(instance, ignoreCase = true)) return true
    return GitLabAuth.isGitLabCom("https://$link") && GitLabAuth.isGitLabCom("https://$instance")
  }

  /** Hosts the https VIEW filter offers to open. */
  fun claimsHost(host: String?): Boolean = sameServer(host, "gitlab.com")

  private fun bareHost(host: String?): String? {
    val trimmed = host?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it == '/' || it == '?' || it == '#' || it.isWhitespace() }) return null
    return trimmed
  }

  private fun isHttpUrl(value: String): Boolean {
    if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) return false
    val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringAfter('@').substringBefore(':')
    return host.isNotBlank()
  }

  private fun httpUrlIn(text: String): String? {
    var url = HTTP_URL.find(text)?.value ?: return null
    while (url.isNotEmpty() && url.last() in TRAILING) url = url.dropLast(1)
    return url.takeIf(::isHttpUrl)
  }
}
