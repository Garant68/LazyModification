package com.example.lazymodification

import com.example.lazymodification.utils.MtDictionary
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MtDictionaryTest {
    @Test
    fun writesAeDictXmlFormat() {
        val xml = MtDictionary.build(linkedMapOf("Hello & <world>" to "Привет \"мир\""))
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n" +
                "<translations name=\"AeDict\" version=\"1.0\">\n" +
                "  <translate from=\"Hello &amp; &lt;world&gt;\" to=\"Привет &quot;мир&quot;\" />\n" +
                "</translations>\n",
            xml
        )
    }

    @Test
    fun preservesLineBreaksAsXmlEntities() {
        val xml = MtDictionary.build(mapOf("line1\nline2" to "tab\tend"))
        assertTrue(xml.contains("from=\"line1&#10;line2\""))
        assertTrue(xml.contains("to=\"tab&#9;end\""))
    }

    @Test
    fun parsesMtManagerDictionary() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <translations name="AeDict" version="1.0">
              <translate from="Hello" to="Привет" />
              <translate from="World &amp; Co" to="Мир и Ко" />
            </translations>
        """.trimIndent()
        val parsed = MtDictionary.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertEquals("Привет", parsed["Hello"])
        assertEquals("Мир и Ко", parsed["World & Co"])
    }
}
