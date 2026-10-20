package com.opendictate.app.model

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PostProcessingRulesSyncTest {
    @Test fun `shared rules exclude switches and imports preserve every local switch`() {
        val formats = ValueFormatting.systemDefaults(Locale.US)
        val remote = PostProcessingSettings(true, true, true, formats)
        val localFormats = formats.copy(entries = formats.entries +
            (ValueKind.INTEGER to formats.entries.getValue(ValueKind.INTEGER).copy(enabled = false)))
        val local = PostProcessingSettings(false, false, false, localFormats)
        val encoded = remote.rulesJson()
        assertFalse(encoded.contains("enabled")); assertFalse(encoded.contains("punctuation"))
        assertEquals(encoded, remote.copy(enabled = false, punctuation = false, formatValues = false).rulesJson())
        val imported = local.applyingRules(encoded)
        assertFalse(imported.enabled); assertFalse(imported.punctuation); assertFalse(imported.formatValues)
        assertFalse(imported.formats!!.entries.getValue(ValueKind.INTEGER).enabled)
        assertEquals(encoded, imported.rulesJson())
        val cleared = remote.applyingRules(PostProcessingSettings().rulesJson())
        assertTrue(cleared.enabled); assertTrue(cleared.formatValues); assertNull(cleared.formats)
        assertEquals(cleared, PostProcessingSettings.fromJson(cleared.toJson()))
    }
}
