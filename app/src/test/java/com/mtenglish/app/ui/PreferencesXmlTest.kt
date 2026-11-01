package com.mtenglish.app.ui

import com.mtenglish.app.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards against preferences.xml keys drifting away from the Prefs.KEY_* constants other modules read. */
class PreferencesXmlTest {
    private val xml: String by lazy {
        val candidates = listOf(File("src/main/res/xml/preferences.xml"), File("app/src/main/res/xml/preferences.xml"))
        candidates.first { it.exists() }.readText()
    }

    private fun keys(): List<String> = Regex("app:key=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()

    @Test fun everyPrefsKeyIsEditable() {
        val keys = keys()
        listOf(
            Prefs.KEY_API_KEY, Prefs.KEY_MODEL, Prefs.KEY_BASE_URL, Prefs.KEY_IMAGES,
            Prefs.KEY_SHOW_ORIGINAL, Prefs.KEY_OFFLINE, Prefs.KEY_OVERLAY,
        ).forEach { assertTrue("missing $it", it in keys) }
    }

    @Test fun keysAreUnique() {
        val keys = keys()
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun defaultsMatchPrefs() {
        assertTrue(xml.contains("app:defaultValue=\"${Prefs.DEFAULT_MODEL}\""))
        assertTrue(xml.contains("app:defaultValue=\"${Prefs.DEFAULT_BASE_URL}\""))
    }
}
