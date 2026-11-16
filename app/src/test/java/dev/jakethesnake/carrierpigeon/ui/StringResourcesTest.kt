// SPDX-License-Identifier: Apache-2.0
package dev.jakethesnake.carrierpigeon.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the string resources in every language folder: tappable terms name real glossary
 * entries, and each translation keeps the English string's terms and placeholders.
 */
class StringResourcesTest {
    private val res = File("src/main/res")

    private data class Str(val name: String, val translatable: Boolean, val body: String) {
        val terms: Set<String> get() = TERM.findAll(body).map { it.groupValues[1] }.toSet()
        val placeholders: Set<String> get() = PLACEHOLDER.findAll(body).map { it.value }.toSet()
    }

    private fun strings(folder: File): Map<String, Str> = folder.listFiles { f -> f.extension == "xml" }.orEmpty()
        .flatMap { file -> STRING.findAll(file.readText()).map { m -> Str(m.groupValues[1], !m.groupValues[2].contains("translatable=\"false\""), m.groupValues[3]) } }
        .associateBy { it.name }

    private val english by lazy { strings(File(res, "values")) }

    private val translations by lazy {
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .associate { it.name to strings(it) }
            .filterValues { it.isNotEmpty() }
    }

    @Test
    fun resourcesFolderFound() {
        assertTrue("run from the app module: ${res.absolutePath}", english.isNotEmpty())
    }

    @Test
    fun everyTermHasTitleAndBody() {
        TermId.entries.forEach { term ->
            assertTrue("missing glossary_${term.key}_title", "glossary_${term.key}_title" in english)
            assertTrue("missing glossary_${term.key}_body", "glossary_${term.key}_body" in english)
        }
    }

    @Test
    fun annotationsNameRealTerms() {
        (listOf("values" to english) + translations.toList()).forEach { (folder, strings) ->
            strings.values.forEach { s ->
                s.terms.forEach { key -> assertTrue("$folder/${s.name}: unknown term \"$key\"", TermId.byKey(key) != null) }
            }
        }
    }

    @Test
    fun translationsKeepTermsAndPlaceholders() {
        translations.forEach { (folder, strings) ->
            strings.values.forEach { s ->
                val source = english[s.name]
                assertTrue("$folder/${s.name} has no English source", source != null)
                assertTrue("$folder/${s.name} translates a string marked translatable=\"false\"", source!!.translatable)
                assertEquals("$folder/${s.name}: tappable terms", source.terms, s.terms)
                assertEquals("$folder/${s.name}: placeholders", source.placeholders, s.placeholders)
            }
        }
    }

    private companion object {
        val STRING = Regex("""<string name="(\w+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val TERM = Regex("""<annotation term="([^"]+)">""")
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
