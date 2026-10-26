package com.themoon.y1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureVisibilityTest {
    @Test
    fun hiddenOnlyWhenAllButtonsAreHidden() {
        val hidden = setOf("btn_videos", "btn_videos_2")
        assertTrue(FeatureVisibility.isHidden(listOf("btn_videos")) { it in hidden })
        assertTrue(FeatureVisibility.isHidden(listOf("btn_videos", "btn_videos_2")) { it in hidden })
        assertFalse(FeatureVisibility.isHidden(listOf("btn_videos", "btn_other")) { it in hidden })
        assertFalse(FeatureVisibility.isHidden(listOf("btn_podcasts")) { it in hidden })
    }

    @Test
    fun featureWithoutButtonIsNotHidden() {
        assertFalse(FeatureVisibility.isHidden(emptyList()) { true })
    }

    @Test
    fun jellyfinLibrariesMapToTheirFeature() {
        assertEquals(FeatureVisibility.VIDEOS, FeatureVisibility.featureForLibrary("movies"))
        assertEquals(FeatureVisibility.VIDEOS, FeatureVisibility.featureForLibrary("TvShows"))
        assertEquals(FeatureVisibility.AUDIOBOOKS, FeatureVisibility.featureForLibrary("books"))
        assertEquals(FeatureVisibility.AUDIOBOOKS, FeatureVisibility.featureForLibrary("audiobooks"))
        assertNull(FeatureVisibility.featureForLibrary("music"))
        assertNull(FeatureVisibility.featureForLibrary(null))
    }

    @Test
    fun missingContextMeansVisible() {
        assertFalse(FeatureVisibility.isHidden(null, FeatureVisibility.VIDEOS))
        assertFalse(FeatureVisibility.skipsJellyfinLibrary(null, "movies"))
    }
}
