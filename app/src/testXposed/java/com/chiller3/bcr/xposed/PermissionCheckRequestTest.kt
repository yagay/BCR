package com.chiller3.bcr.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionCheckRequestTest {
    @Test fun packageCheckUsesPermissionThenPackage() {
        val request = PermissionCheckRequest.parse(
            PermissionMethodClassifier.Kind.PACKAGE,
            arrayOf("android.permission.CAPTURE_AUDIO_OUTPUT", "com.chiller3.bcr", 0),
        )
        assertEquals("android.permission.CAPTURE_AUDIO_OUTPUT", request?.permission)
        assertEquals("com.chiller3.bcr", request?.packageName)
        assertNull(request?.uid)
    }

    @Test fun uidCheckUsesUidThenPermission() {
        val request = PermissionCheckRequest.parse(
            PermissionMethodClassifier.Kind.UID,
            arrayOf(12345, "android.permission.CONTROL_INCALL_EXPERIENCE", 0),
        )
        assertEquals(12345, request?.uid)
        assertEquals("android.permission.CONTROL_INCALL_EXPERIENCE", request?.permission)
        assertNull(request?.packageName)
    }

    @Test fun malformedOrSwappedArgumentsNeverMatch() {
        assertNull(PermissionCheckRequest.parse(
            PermissionMethodClassifier.Kind.PACKAGE, arrayOf("com.chiller3.bcr", 10001),
        ))
        assertNull(PermissionCheckRequest.parse(
            PermissionMethodClassifier.Kind.UID, arrayOf("android.permission.CAPTURE_AUDIO_OUTPUT", 10001),
        ))
        assertNull(PermissionCheckRequest.parse(
            PermissionMethodClassifier.Kind.PACKAGE, emptyArray(),
        ))
        assertNull(PermissionCheckRequest.parse(
            PermissionMethodClassifier.Kind.UID, arrayOf(10001, ""),
        ))
    }

    @Test fun anotherPackageIsNotMistakenForBcr() {
        val request = PermissionCheckRequest.parse(
            PermissionMethodClassifier.Kind.PACKAGE,
            arrayOf("android.permission.CAPTURE_AUDIO_OUTPUT", "com.other.app", 0),
        )
        assertEquals("com.other.app", request?.packageName)
    }
}
