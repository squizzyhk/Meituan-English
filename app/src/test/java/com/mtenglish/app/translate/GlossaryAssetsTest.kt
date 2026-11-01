package com.mtenglish.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates every glossary TSV under src/main/assets/glossary: exactly one TAB per entry line, both
 * sides non-empty, no Chinese on the English side, and no duplicate keys within or across files.
 * Passes trivially when no glossary files exist yet.
 */
class GlossaryAssetsTest {

    private fun glossaryDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(userDir, "src/main/assets/glossary"),
            File(userDir, "app/src/main/assets/glossary"),
        )
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }

    private fun parseAll(): List<GlossaryParser.Result> {
        val dir = glossaryDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".tsv", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        return files.map { GlossaryParser.parse(it.readLines(Charsets.UTF_8), it.name) }
    }

    @Test fun formatIsValid() {
        val problems = parseAll().flatMap { it.problems }
        assertTrue("Glossary format problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun noDuplicateKeys() {
        val dups = GlossaryParser.duplicates(parseAll())
        val report = dups.entries.sortedBy { it.key }.joinToString("\n") { (key, list) ->
            "$key -> " + list.joinToString("; ") { "${it.file}:${it.line}=\"${it.english}\"" }
        }
        assertTrue("Duplicate glossary keys (${dups.size}):\n$report", dups.isEmpty())
    }

    @Test fun parserRules() {
        val r = GlossaryParser.parse(
            listOf(
                "﻿# comment",
                "",
                "去结算\tCheckout",
                "  微辣 \t Mild spicy ",
                "坏行",
                "两\t个\tTabs",
                "空\t",
                "中文\t还有中文",
                "去结算\tCheck out",
            ),
            "t.tsv",
        )
        assertEquals(4, r.entries.size)
        assertEquals("微辣", r.entries[1].key)
        assertEquals("Mild spicy", r.entries[1].english)
        assertEquals(listOf(5, 6, 7, 8), r.problems.map { it.line })
        val merged = GlossaryParser.merge(listOf(r))
        assertEquals("Check out", merged["去结算"])
        assertEquals(setOf("去结算"), GlossaryParser.duplicates(listOf(r)).keys)
    }
}
