package com.cboyxd.gitlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure classification of links other apps hand in. No emulator. */
class IncomingLinksTest {

  @Test
  fun `a viewed gitlab com url is the url itself`() {
    val issue = "https://gitlab.com/gitlab-org/gitlab/-/issues/1"
    assertEquals(issue, IncomingLinks.webUrl(VIEW, issue, "https://evil.example/phish"))
    assertEquals(
      "https://www.gitlab.com/gitlab-org/gitlab/-/merge_requests/2",
      IncomingLinks.webUrl(VIEW, "  https://www.gitlab.com/gitlab-org/gitlab/-/merge_requests/2  ", null),
    )
    assertEquals("https://gitlab.com", IncomingLinks.webUrl(VIEW, "https://gitlab.com", null))
    assertEquals("HTTPS://gitlab.com/group/proj", IncomingLinks.webUrl(VIEW, "HTTPS://gitlab.com/group/proj", null))
  }

  @Test
  fun `the oauth redirect is not a web link`() {
    val redirect = "com.cboyxd.gitlab://oauth?code=abc&state=xyz"
    assertTrue(IncomingLinks.isOAuthRedirect(redirect))
    assertTrue(IncomingLinks.isOAuthRedirect("COM.CBOYXD.GITLAB://OAuth"))
    assertTrue(IncomingLinks.isOAuthRedirect("com.cboyxd.gitlab://oauth/extra"))
    assertFalse(IncomingLinks.isOAuthRedirect("com.cboyxd.gitlab://oauth-not"))
    assertFalse(IncomingLinks.isOAuthRedirect("https://gitlab.com/oauth/authorize"))
    assertNull(IncomingLinks.webUrl(VIEW, redirect, "https://gitlab.com/gitlab-org/gitlab"))
    assertNull(IncomingLinks.webUrl(SEND, redirect, "https://gitlab.example.com/group/proj"))
  }

  @Test
  fun `shared text yields the first http url and nothing else`() {
    val project = "https://gitlab.example.com/group/proj/-/merge_requests/4"
    assertEquals(project, IncomingLinks.webUrl(SEND, "https://evil.example/phish", project))
    assertEquals(project, IncomingLinks.webUrl(SEND, null, "Look at this: $project"))
    assertEquals(
      "https://gitlab.example.com/g/p/-/issues/3?x=1",
      IncomingLinks.webUrl(SEND, null, "https://gitlab.example.com/g/p/-/issues/3?x=1."),
    )
    assertEquals(
      "https://gitlab.com/g/p/-/blob/main/README.md#L10",
      IncomingLinks.webUrl(SEND, null, "<https://gitlab.com/g/p/-/blob/main/README.md#L10>"),
    )
    assertEquals(
      "https://gitlab.com/a/b",
      IncomingLinks.webUrl(SEND, null, "see https://gitlab.com/a/b and https://gitlab.com/c/d"),
    )
    assertEquals(
      "https://gitlab.example.com/group/proj",
      IncomingLinks.webUrl(SEND, null, "Review [the project](https://gitlab.example.com/group/proj)."),
    )
    assertEquals(
      "http://gitlab.internal/group/proj/-/pipelines/9",
      IncomingLinks.webUrl(SEND, null, "http://gitlab.internal/group/proj/-/pipelines/9"),
    )
    assertEquals(project, IncomingLinks.webUrl(SEND, project, null))
    assertEquals(project, IncomingLinks.webUrl(SEND, project, "not a url"))
    assertNull(IncomingLinks.webUrl(SEND, null, "no link in here"))
    assertNull(IncomingLinks.webUrl(SEND, null, "gitlab.com/group/proj"))
    assertNull(IncomingLinks.webUrl(SEND, null, "javascript:alert(1)"))
    assertNull(IncomingLinks.webUrl(SEND, null, "mailto:dev@gitlab.com"))
    assertNull(IncomingLinks.webUrl(SEND, null, "   "))
    assertNull(IncomingLinks.webUrl(SEND, null, null))
  }

  @Test
  fun `other actions and non-urls are left alone`() {
    assertNull(IncomingLinks.webUrl(null, "https://gitlab.com/group/proj", null))
    assertNull(IncomingLinks.webUrl("android.intent.action.MAIN", "https://gitlab.com/group/proj", null))
    assertNull(IncomingLinks.webUrl(VIEW, "mailto:dev@gitlab.com", null))
    assertNull(IncomingLinks.webUrl(VIEW, "https://", null))
    assertNull(IncomingLinks.webUrl(VIEW, null, "https://gitlab.com/group/proj"))
  }

  @Test
  fun `www gitlab com is the same server and lookalikes are not`() {
    assertTrue(IncomingLinks.sameServer("gitlab.com", "gitlab.com"))
    assertTrue(IncomingLinks.sameServer("www.gitlab.com", "gitlab.com"))
    assertTrue(IncomingLinks.sameServer("gitlab.com", "WWW.GITLAB.COM"))
    assertTrue(IncomingLinks.sameServer("GitLab.Example.com", "gitlab.example.com"))
    assertFalse(IncomingLinks.sameServer("gitlab.com", "gitlab.example.com"))
    assertFalse(IncomingLinks.sameServer("gitlab.com.evil.com", "gitlab.com"))
    assertFalse(IncomingLinks.sameServer("www.gitlab.com.evil.com", "gitlab.com"))
    assertFalse(IncomingLinks.sameServer("notgitlab.com", "gitlab.com"))
    assertFalse(IncomingLinks.sameServer("evilgitlab.com", "gitlab.com"))
    assertFalse(IncomingLinks.sameServer(null, "gitlab.com"))
    assertFalse(IncomingLinks.sameServer("gitlab.com", ""))
    assertFalse(IncomingLinks.sameServer("gitlab.com/evil", "gitlab.com"))
    assertTrue(IncomingLinks.claimsHost("gitlab.com"))
    assertTrue(IncomingLinks.claimsHost("www.gitlab.com"))
    assertFalse(IncomingLinks.claimsHost("gitlab.example.com"))
    assertFalse(IncomingLinks.claimsHost("about.gitlab.com"))
    assertFalse(IncomingLinks.claimsHost(null))
  }

  private companion object {
    const val VIEW = "android.intent.action.VIEW"
    const val SEND = "android.intent.action.SEND"
  }
}
