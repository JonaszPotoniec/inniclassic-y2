package com.themoon.y1.navigation

/**
 * What the on-screen keyboard is open for. Each opening gives one, in [ScreenState.WifiKeyboard],
 * so nothing from an earlier opening is used again. The title, the hint, the submit key label and
 * the submit action all come from it.
 *
 * Do not put secrets (passwords, tokens) here: screen states can show in logs.
 */
sealed interface KeyboardTarget {
    data class WifiPassword(val ssid: String, val isOpen: Boolean = false) : KeyboardTarget
    data object PodcastSearch : KeyboardTarget
    data object LibrarySearch : KeyboardTarget
    data object LastFmUsername : KeyboardTarget
    data class LastFmPassword(val username: String) : KeyboardTarget
    data object JellyfinServerUrl : KeyboardTarget
    data object JellyfinUsername : KeyboardTarget
    data class JellyfinPassword(val username: String) : KeyboardTarget
    data object JellyfinToken : KeyboardTarget
}

/** Result of the submit key for a [KeyboardTarget]. */
sealed interface KeyboardSubmit {
    /** Nothing to submit: show [message] (not localized yet) and stay on the keyboard. */
    data class Rejected(val message: String) : KeyboardSubmit

    /** Give [value] to the target. If [next] is not null, the keyboard stays open for that step. */
    data class Accepted(val value: String, val next: KeyboardTarget? = null) : KeyboardSubmit
}

/** Texts and submit rules for each [KeyboardTarget]. No Android code, so unit tests can check it. */
object KeyboardTargets {

    /** Line above the input field. */
    fun title(target: KeyboardTarget, localize: (String) -> String): String = when (target) {
        is KeyboardTarget.WifiPassword -> localize("Target") + ": " + target.ssid
        KeyboardTarget.PodcastSearch -> "🔍 " + localize("Search Podcast")
        KeyboardTarget.LibrarySearch -> "🔍 " + localize("Search")
        KeyboardTarget.LastFmUsername -> localize("Last.fm Username")
        is KeyboardTarget.LastFmPassword -> localize("Last.fm Password") + ": " + target.username
        KeyboardTarget.JellyfinServerUrl -> localize("Server URL")
        KeyboardTarget.JellyfinUsername -> localize("Username")
        is KeyboardTarget.JellyfinPassword -> localize("Password") + ": " + target.username
        KeyboardTarget.JellyfinToken -> localize("API Token")
    }

    /** Grey text in the empty input field. */
    fun hint(target: KeyboardTarget, localize: (String) -> String): String = when (target) {
        is KeyboardTarget.WifiPassword ->
            if (target.isOpen) localize("Open Network (Direct Connect)") else localize("Enter Password...")
        KeyboardTarget.PodcastSearch -> localize("Enter keyword...")
        KeyboardTarget.LibrarySearch -> localize("Enter search term...")
        KeyboardTarget.LastFmUsername -> localize("Enter Last.fm username...")
        is KeyboardTarget.LastFmPassword -> localize("Enter Last.fm password...")
        KeyboardTarget.JellyfinServerUrl -> "http://..."
        KeyboardTarget.JellyfinUsername -> localize("Username")
        is KeyboardTarget.JellyfinPassword -> localize("Password")
        KeyboardTarget.JellyfinToken -> localize("API Token")
    }

    /** Label of the QWERTY submit key. */
    fun actionLabel(target: KeyboardTarget, localize: (String) -> String): String = when (target) {
        is KeyboardTarget.WifiPassword -> localize("Connect")
        KeyboardTarget.PodcastSearch, KeyboardTarget.LibrarySearch -> localize("Search")
        KeyboardTarget.LastFmUsername, KeyboardTarget.JellyfinUsername -> localize("Next")
        is KeyboardTarget.LastFmPassword, is KeyboardTarget.JellyfinPassword, KeyboardTarget.JellyfinToken -> localize("Login")
        KeyboardTarget.JellyfinServerUrl -> localize("Save")
    }

    /** What the submit key does with [text]. Passwords are not trimmed. */
    fun submit(target: KeyboardTarget, text: String): KeyboardSubmit = when (target) {
        is KeyboardTarget.WifiPassword -> KeyboardSubmit.Accepted(text)
        KeyboardTarget.PodcastSearch, KeyboardTarget.LibrarySearch,
        KeyboardTarget.JellyfinServerUrl, KeyboardTarget.JellyfinToken ->
            required(text.trim(), "Please enter a keyword.") { KeyboardSubmit.Accepted(it) }
        KeyboardTarget.LastFmUsername ->
            required(text.trim(), "Please enter a username.") { KeyboardSubmit.Accepted(it, KeyboardTarget.LastFmPassword(it)) }
        KeyboardTarget.JellyfinUsername ->
            required(text.trim(), "Please enter a username.") { KeyboardSubmit.Accepted(it, KeyboardTarget.JellyfinPassword(it)) }
        is KeyboardTarget.LastFmPassword, is KeyboardTarget.JellyfinPassword ->
            required(text, "Please enter a password.") { KeyboardSubmit.Accepted(it) }
    }

    private inline fun required(value: String, message: String, accept: (String) -> KeyboardSubmit): KeyboardSubmit =
        if (value.isEmpty()) KeyboardSubmit.Rejected(message) else accept(value)
}
