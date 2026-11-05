/*
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.chiller3.bcr.manager

import com.chiller3.bcr.Preferences
import com.chiller3.bcr.template.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTemplateFallbackTest {
    @Test
    fun bcrDefaultFilename() {
        val parser = RecordingTemplateFallback(listOf(Preferences.DEFAULT_FILENAME_TEMPLATE))
        val values = parser.extract("20261105_221500.123+0000_out_sim1_07700900123_Hospital")
        assertEquals("out", values["direction"])
        assertEquals("1", values["sim_slot"])
        assertEquals("07700900123", values["phone_number"])
        assertEquals("Hospital", values["contact_name"])
    }

    @Test
    fun customNestedDirectories() {
        val parser = RecordingTemplateFallback(
            listOf(Template("{date:yyyy/MM/dd}/{contact_name}_{direction}"))
        )
        val values = parser.extract("2026/11/05/Hospital_out")
        assertEquals("Hospital", values["contact_name"])
        assertEquals("out", values["direction"])
    }

    @Test
    fun customUnixMillisecondsTemplate() {
        val parser = RecordingTemplateFallback(
            listOf(Template("{contact_name}/{date:unix_ms}_{direction}"))
        )
        assertEquals(
            1762381200123L,
            parser.unixTimestampMillis("Hospital/1762381200123_out"),
        )
    }

    @Test
    fun ambiguousAdjacentVariablesNotGuessed() {
        val parser = RecordingTemplateFallback(
            listOf(Template("{contact_name}{phone_number}"))
        )
        assertTrue(parser.extract("Alice1234567").isEmpty())
    }
}
