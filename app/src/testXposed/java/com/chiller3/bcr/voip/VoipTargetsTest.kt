package com.chiller3.bcr.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoipTargetsTest {
    @Test fun packagesAreUniqueAndExplicit() {
        val packages = VoipTargets.known.map { it.packageName }
        assertEquals(packages.size, packages.toSet().size)
        assertTrue(packages.contains("com.whatsapp"))
        assertTrue(packages.contains("org.telegram.messenger"))
        assertTrue(packages.contains("com.tencent.mm"))
        assertFalse(packages.contains("android"))
    }

    @Test fun fileTagsAreSafeForPaths() {
        assertTrue(VoipTargets.known.all { it.tag.matches(Regex("[a-z-]+")) })
        assertTrue(VoipTargets.known.all { it.packageName.matches(Regex("[a-zA-Z0-9._]+")) })
    }
}
