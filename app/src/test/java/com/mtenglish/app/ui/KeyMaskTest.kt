package com.mtenglish.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyMaskTest {
    @Test fun masks() {
        assertEquals("sk-ant-…abcd", KeyMask.mask("sk-ant-api03-XXXXXXXXXXXXXXXXabcd"))
        assertEquals("key…6789", KeyMask.mask(" key-123456789 "))
        assertEquals("••••", KeyMask.mask("short"))
        assertNull(KeyMask.mask(""))
        assertNull(KeyMask.mask(null))
    }
}
