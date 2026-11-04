package com.themoon.y1.controllers.browser

import java.io.File

/**
 * Tracks folder navigation steps so Back returns through visited folders and restores focus
 * on the folder or ".." that was left.
 *
 * @param currentFolder The initial folder being browsed.
 */
class FolderHistory(var currentFolder: File = File("/")) {

    data class Entry(val folder: File, val focusText: String)

    private val stack = ArrayList<Entry>()

    val size: Int get() = stack.size
    val isEmpty: Boolean get() = stack.isEmpty()
    val isNotEmpty: Boolean get() = stack.isNotEmpty()

    /**
     * Enters [folder] from [currentFolder], remembering to focus [folder.name] when returning.
     */
    fun enter(folder: File) {
        stack.add(Entry(currentFolder, folder.name))
        currentFolder = folder
    }

    /**
     * Navigates up to [parent] using "..", remembering to return to [currentFolder] focusing "..".
     * Returns the name of [currentFolder] to focus in [parent], or null if [parent] is null.
     */
    fun up(parent: File? = currentFolder.parentFile): String? {
        if (parent == null) return null
        val exited = currentFolder.name
        stack.add(Entry(currentFolder, ".."))
        currentFolder = parent
        return exited
    }

    /**
     * Pops the previous [Entry] from history, updates [currentFolder], and returns the entry to restore,
     * or null if history was empty (indicating Back should exit folder selection).
     */
    fun back(): Entry? {
        if (stack.isEmpty()) return null
        val prev = stack.removeAt(stack.size - 1)
        currentFolder = prev.folder
        return prev
    }

    /**
     * Clears history and sets [currentFolder] to [folder].
     */
    fun reset(folder: File) {
        stack.clear()
        currentFolder = folder
    }

    /**
     * Clears history without changing [currentFolder].
     */
    fun clear() {
        stack.clear()
    }
}
