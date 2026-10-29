package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun paths(list: List<PlayHistoryStore.Entry>) = list.map { it.path }

    @Test
    fun recordCountsPlaysAndKeepsTheLastTime() {
        val store = PlayHistoryStore()
        store.record("/m/a.mp3", 100L)
        store.record("/m/a.mp3", 300L)
        store.record("/m/b.mp3", 200L)
        assertEquals(2, store.get("/m/a.mp3")?.count)
        assertEquals(300L, store.get("/m/a.mp3")?.lastPlayedMs)
        assertEquals(1, store.get("/m/b.mp3")?.count)
    }

    @Test
    fun recentlyPlayedIsNewestFirstWithEachSongOnce() {
        val store = PlayHistoryStore()
        store.record("/m/a.mp3", 100L)
        store.record("/m/b.mp3", 200L)
        store.record("/m/c.mp3", 300L)
        store.record("/m/a.mp3", 400L)
        assertEquals(listOf("/m/a.mp3", "/m/c.mp3", "/m/b.mp3"), paths(store.recentlyPlayed()))
        assertEquals(listOf("/m/a.mp3", "/m/c.mp3"), paths(store.recentlyPlayed(limit = 2)))
    }

    @Test
    fun mostPlayedIsMostPlaysFirstThenLastPlayed() {
        val store = PlayHistoryStore()
        repeat(3) { store.record("/m/a.mp3", 100L + it) }
        store.record("/m/b.mp3", 500L)
        store.record("/m/c.mp3", 600L)
        repeat(5) { store.record("/m/d.mp3", 50L + it) }
        assertEquals(listOf("/m/d.mp3", "/m/a.mp3", "/m/c.mp3", "/m/b.mp3"), paths(store.mostPlayed()))
    }

    @Test
    fun mostPlayedHasTwentyFive() {
        val store = PlayHistoryStore()
        for (i in 0 until 40) store.record("/m/$i.mp3", i.toLong())
        assertEquals(PlayHistoryStore.TOP_LIMIT, store.mostPlayed().size)
        assertEquals(40, store.recentlyPlayed().size)
    }

    @Test
    fun goneFilesAreRemovedAndTheListStaysFull() {
        val store = PlayHistoryStore()
        for (i in 0 until 5) store.record("/m/$i.mp3", i.toLong())
        val gone = setOf("/m/4.mp3", "/m/3.mp3")
        val (list, removed) = store.listWithoutGone({ store.recentlyPlayed(limit = 3) }, { it in gone })
        assertTrue(removed)
        assertEquals(listOf("/m/2.mp3", "/m/1.mp3", "/m/0.mp3"), paths(list))
        assertNull(store.get("/m/4.mp3"))
        assertEquals(3, store.size)
    }

    @Test
    fun noGoneFilesChangesNothing() {
        val store = PlayHistoryStore()
        store.record("/m/a.mp3", 1L)
        val (list, removed) = store.listWithoutGone({ store.mostPlayed() }, { false })
        assertFalse(removed)
        assertEquals(listOf("/m/a.mp3"), paths(list))
    }

    @Test
    fun movedFileStartsAgain() {
        val store = PlayHistoryStore()
        repeat(4) { store.record("/m/old/a.mp3", it.toLong()) }
        store.listWithoutGone({ store.mostPlayed() }, { it == "/m/old/a.mp3" })
        store.record("/m/new/a.mp3", 10L)
        assertNull(store.get("/m/old/a.mp3"))
        assertEquals(1, store.get("/m/new/a.mp3")?.count)
    }

    @Test
    fun oldestSongGoesWhenTheStoreIsFull() {
        val store = PlayHistoryStore(maxSongs = 3)
        store.record("/m/a.mp3", 1L)
        store.record("/m/b.mp3", 2L)
        store.record("/m/c.mp3", 3L)
        store.record("/m/d.mp3", 4L)
        assertEquals(3, store.size)
        assertNull(store.get("/m/a.mp3"))
    }

    @Test
    fun fileRoundTrip() {
        val file = tmp.root.resolve("sub/play_history.json")
        val store = PlayHistoryStore()
        store.record("/m/a b/ä.mp3", 1000L)
        store.record("/m/a b/ä.mp3", 2000L)
        store.record("/m/c.flac", 1500L)
        store.writeTo(file)
        store.record("/m/c.flac", 3000L)
        store.writeTo(file) // replaces the old file
        assertEquals(listOf("play_history.json"), tmp.root.resolve("sub").list()?.toList()) // no temporary file

        val read = PlayHistoryStore.readFrom(file)
        assertEquals(2, read.get("/m/a b/ä.mp3")?.count)
        assertEquals(2000L, read.get("/m/a b/ä.mp3")?.lastPlayedMs)
        assertEquals(listOf("/m/c.flac", "/m/a b/ä.mp3"), paths(read.recentlyPlayed()))
    }

    @Test
    fun missingOrBadFileGivesAnEmptyStore() {
        assertEquals(0, PlayHistoryStore.readFrom(tmp.root.resolve("none.json")).size)
        assertEquals(0, PlayHistoryStore.fromJson("not json").size)
        val partly = """{"version":1,"songs":[{"path":"/m/a.mp3","count":2,"last":5},{"path":"","count":1},{"count":3},{"path":"/m/b.mp3","count":0}]}"""
        val store = PlayHistoryStore.fromJson(partly)
        assertEquals(1, store.size)
        assertEquals(2, store.get("/m/a.mp3")?.count)
    }
}
