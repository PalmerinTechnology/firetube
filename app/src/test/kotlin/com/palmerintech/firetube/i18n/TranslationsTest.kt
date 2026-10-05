package com.palmerintech.firetube.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translation covers exactly the strings in values/strings.xml, with the same placeholders,
 * and res/xml/locales_config.xml lists every translated language (for Android 13+'s per-app
 * language setting).
 */
class TranslationsTest {
    /** Unit tests run with the module directory as the working directory. */
    private val res = File("src/main/res")

    private val translations: List<File> =
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }
            .orEmpty().map { File(it, "strings.xml") }.sortedBy { it.parentFile!!.name }

    /** name -> placeholders, for each translatable string and plurals item ("name#quantity"). */
    private fun read(file: File): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, List<String>>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            if (e.getAttribute("translatable") == "false") continue
            out[e.getAttribute("name")] = placeholders(e.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as Element
            val items = p.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                out[p.getAttribute("name") + "#" + item.getAttribute("quantity")] = placeholders(item.textContent)
            }
        }
        return out
    }

    private fun placeholders(text: String) = PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun thereAreTranslations() {
        assertTrue(translations.map { it.parentFile!!.name }.containsAll(listOf("values-es", "values-pt-rBR")))
    }

    @Test
    fun everyStringIsTranslatedAndNothingElse() {
        val base = read(File(res, "values/strings.xml")).keys.filterNot { it.contains('#') }.toSet()
        for (file in translations) {
            val keys = read(file).keys.filterNot { it.contains('#') }.toSet()
            val lang = file.parentFile!!.name
            assertEquals("$lang is missing strings", emptySet<String>(), base - keys)
            assertEquals("$lang has strings values/ doesn't", emptySet<String>(), keys - base)
        }
    }

    @Test
    fun pluralsHaveOneAndOtherInEveryLanguage() {
        for (file in translations + File(res, "values/strings.xml")) {
            val entries = read(file).keys.filter { it.contains('#') }
            val byName = entries.groupBy({ it.substringBefore('#') }, { it.substringAfter('#') })
            for ((name, quantities) in byName) {
                assertTrue("${file.parentFile!!.name} $name: $quantities", quantities.containsAll(listOf("one", "other")))
            }
        }
    }

    @Test
    fun placeholdersMatchTheEnglish() {
        val base = read(File(res, "values/strings.xml"))
        val pluralArgs = base.filterKeys { it.endsWith("#other") }.mapKeys { it.key.substringBefore('#') }
        for (file in translations) {
            val lang = file.parentFile!!.name
            for ((key, args) in read(file)) {
                // A plural's "one" may spell the number out; every other form matches the English "other".
                val expected = if (key.contains('#')) pluralArgs.getValue(key.substringBefore('#')) else base.getValue(key)
                if (key.endsWith("#one")) {
                    assertTrue("$lang $key: $args", expected.containsAll(args))
                } else {
                    assertEquals("$lang $key", expected, args)
                }
            }
        }
    }

    @Test
    fun localeConfigListsEveryLanguage() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "xml/locales_config.xml"))
        val locales = doc.getElementsByTagName("locale")
        val listed = (0 until locales.length).map { (locales.item(it) as Element).getAttribute("android:name") }.toSet()
        // values-pt-rBR -> pt-BR
        val translated = translations.map { it.parentFile!!.name.removePrefix("values-").replace("-r", "-") }
        assertEquals((listOf("en") + translated).toSet(), listed)
    }

    private companion object {
        /** %1$s, %2$d, ... (%% is a literal percent sign, not an argument). */
        val PLACEHOLDER = Regex("""%\d+\$[a-z]""")
    }
}
