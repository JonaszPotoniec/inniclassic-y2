package com.themoon.y1.sync

import org.junit.Test
import org.junit.Assert.*

class FileUtilsTest {

    @Test
    fun testSanitizeFilename() {
        assertEquals("Unknown", FileUtils.sanitizeFilename(null))
        assertEquals("Unknown", FileUtils.sanitizeFilename(""))
        assertEquals("Unknown", FileUtils.sanitizeFilename("   "))

        // Illegal chars stripped/replaced: [/ \ : * ? " < > |]
        assertEquals("A_B_C_D_E_F_G_H_I", FileUtils.sanitizeFilename("A/B\\C:D*E?F\"G<H>I|"))

        // Leading/trailing dots and spaces trimmed
        assertEquals("Hello World", FileUtils.sanitizeFilename("  ...Hello World...  "))
        assertEquals("Comfortably Numb", FileUtils.sanitizeFilename(".Comfortably Numb."))

        // Normal text unchanged
        assertEquals("Pink Floyd - The Wall", FileUtils.sanitizeFilename("Pink Floyd - The Wall"))
    }
}
