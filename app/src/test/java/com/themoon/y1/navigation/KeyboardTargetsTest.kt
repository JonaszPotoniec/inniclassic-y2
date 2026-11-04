package com.themoon.y1.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardTargetsTest {

    private val localize: (String) -> String = { it }

    private val all = listOf(
        KeyboardTarget.WifiPassword("Home"),
        KeyboardTarget.PodcastSearch,
        KeyboardTarget.LibrarySearch,
        KeyboardTarget.LastFmUsername,
        KeyboardTarget.LastFmPassword("ann"),
        KeyboardTarget.JellyfinServerUrl,
        KeyboardTarget.JellyfinUsername,
        KeyboardTarget.JellyfinPassword("bob"),
        KeyboardTarget.JellyfinToken
    )

    @Test
    fun librarySearch_showsSearch_andRunsTheSearch() {
        val t = KeyboardTarget.LibrarySearch
        assertEquals("🔍 Search", KeyboardTargets.title(t, localize))
        assertEquals("Enter search term...", KeyboardTargets.hint(t, localize))
        assertEquals("Search", KeyboardTargets.actionLabel(t, localize))
        assertEquals(KeyboardSubmit.Accepted("abba"), KeyboardTargets.submit(t, "  abba "))
        assertEquals(KeyboardSubmit.Rejected("Please enter a keyword."), KeyboardTargets.submit(t, "   "))
    }

    @Test
    fun onlyLastFmTargets_mentionLastFm() {
        for (t in all) {
            val lastFm = t is KeyboardTarget.LastFmUsername || t is KeyboardTarget.LastFmPassword
            val texts = KeyboardTargets.title(t, localize) + KeyboardTargets.hint(t, localize)
            assertEquals(t.toString(), lastFm, texts.contains("Last.fm"))
        }
    }

    @Test
    fun titles() {
        assertEquals("Target: Home", KeyboardTargets.title(KeyboardTarget.WifiPassword("Home"), localize))
        assertEquals("🔍 Search Podcast", KeyboardTargets.title(KeyboardTarget.PodcastSearch, localize))
        assertEquals("Last.fm Username", KeyboardTargets.title(KeyboardTarget.LastFmUsername, localize))
        assertEquals("Last.fm Password: ann", KeyboardTargets.title(KeyboardTarget.LastFmPassword("ann"), localize))
        assertEquals("Server URL", KeyboardTargets.title(KeyboardTarget.JellyfinServerUrl, localize))
        assertEquals("Username", KeyboardTargets.title(KeyboardTarget.JellyfinUsername, localize))
        assertEquals("Password: bob", KeyboardTargets.title(KeyboardTarget.JellyfinPassword("bob"), localize))
        assertEquals("API Token", KeyboardTargets.title(KeyboardTarget.JellyfinToken, localize))
    }

    @Test
    fun hints() {
        assertEquals("Enter Password...", KeyboardTargets.hint(KeyboardTarget.WifiPassword("Home"), localize))
        assertEquals("Open Network (Direct Connect)", KeyboardTargets.hint(KeyboardTarget.WifiPassword("Cafe", isOpen = true), localize))
        assertEquals("Enter keyword...", KeyboardTargets.hint(KeyboardTarget.PodcastSearch, localize))
        assertEquals("Enter Last.fm username...", KeyboardTargets.hint(KeyboardTarget.LastFmUsername, localize))
        assertEquals("Enter Last.fm password...", KeyboardTargets.hint(KeyboardTarget.LastFmPassword("ann"), localize))
        assertEquals("http://...", KeyboardTargets.hint(KeyboardTarget.JellyfinServerUrl, localize))
    }

    @Test
    fun actionLabels() {
        val expected = listOf("Connect", "Search", "Search", "Next", "Login", "Save", "Next", "Login", "Login")
        assertEquals(expected, all.map { KeyboardTargets.actionLabel(it, localize) })
    }

    @Test
    fun usernames_leadToThePasswordStep() {
        assertEquals(
            KeyboardSubmit.Accepted("ann", KeyboardTarget.LastFmPassword("ann")),
            KeyboardTargets.submit(KeyboardTarget.LastFmUsername, " ann ")
        )
        assertEquals(
            KeyboardSubmit.Accepted("bob", KeyboardTarget.JellyfinPassword("bob")),
            KeyboardTargets.submit(KeyboardTarget.JellyfinUsername, "bob")
        )
        assertEquals(KeyboardSubmit.Rejected("Please enter a username."), KeyboardTargets.submit(KeyboardTarget.LastFmUsername, " "))
    }

    @Test
    fun passwords_areNotTrimmed_andMustNotBeEmpty() {
        assertEquals(KeyboardSubmit.Accepted(" pw "), KeyboardTargets.submit(KeyboardTarget.LastFmPassword("ann"), " pw "))
        assertEquals(KeyboardSubmit.Accepted(" pw "), KeyboardTargets.submit(KeyboardTarget.JellyfinPassword("bob"), " pw "))
        assertEquals(KeyboardSubmit.Rejected("Please enter a password."), KeyboardTargets.submit(KeyboardTarget.LastFmPassword("ann"), ""))
        assertEquals(KeyboardSubmit.Accepted(" pw "), KeyboardTargets.submit(KeyboardTarget.WifiPassword("Home"), " pw "))
        assertEquals(KeyboardSubmit.Accepted(""), KeyboardTargets.submit(KeyboardTarget.WifiPassword("Cafe", isOpen = true), ""))
    }

    @Test
    fun onlyUsernames_haveANextStep() {
        for (t in all) {
            val r = KeyboardTargets.submit(t, "x")
            val hasNext = r is KeyboardSubmit.Accepted && r.next != null
            val isUsername = t is KeyboardTarget.LastFmUsername || t is KeyboardTarget.JellyfinUsername
            assertEquals(t.toString(), isUsername, hasNext)
        }
    }
}
