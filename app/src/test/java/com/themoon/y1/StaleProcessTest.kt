package com.themoon.y1

import com.themoon.y1.ClassyApplication.RunningProcess
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleProcessTest {

    @Test
    fun onlyOtherProcessesWithOurNameAndUserAreStale() {
        val running = listOf(
            RunningProcess(100, 10012, "com.themoon.y1"),      // this process
            RunningProcess(90, 10012, "com.themoon.y1"),       // old copy
            RunningProcess(91, 10012, "com.themoon.y1:other"), // another process name
            RunningProcess(92, 1000, "com.themoon.y1"),        // another user
            RunningProcess(93, 10012, null)
        )
        assertEquals(listOf(90), ClassyApplication.staleProcessIds(100, 10012, "com.themoon.y1", running))
    }

    @Test
    fun noOtherProcessGivesAnEmptyList() {
        val running = listOf(RunningProcess(100, 10012, "com.themoon.y1"))
        assertEquals(emptyList<Int>(), ClassyApplication.staleProcessIds(100, 10012, "com.themoon.y1", running))
    }
}
