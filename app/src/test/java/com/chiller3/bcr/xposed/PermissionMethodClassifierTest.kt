package com.chiller3.bcr.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionMethodClassifierTest {
    private val cls = PermissionMethodClassifier
    private val intClass = Int::class.javaPrimitiveType!!
    private val strClass = String::class.java

    @Test fun packageChecksSupportOldAndNewAospSignatures() {
        val klass = PermissionMethodClassifier.Kind.PACKAGE
        assertEquals(klass, cls.classify("checkPermission", intClass,
            listOf(strClass, strClass, intClass)))
        assertEquals(klass, cls.classify("checkPermission", intClass,
            listOf(strClass, strClass, strClass, intClass)))
        assertEquals(klass, cls.classify("checkPermission", intClass,
            listOf(strClass, strClass, intClass, intClass)))
    }

    @Test fun uidChecksAlwaysStartWithUidThenPermission() {
        val kind = PermissionMethodClassifier.Kind.UID
        assertEquals(kind, cls.classify("checkUidPermission", intClass,
            listOf(intClass, strClass)))
        assertEquals(kind, cls.classify("checkUidPermission", intClass,
            listOf(intClass, strClass, intClass)))
        assertEquals(kind, cls.classify("checkUidPermission", intClass,
            listOf(intClass, strClass, strClass)))
    }

    @Test fun rejectsAmbiguousAndIncompatibleMethods() {
        assertNull(cls.classify("checkUidPermission", intClass,
            listOf(strClass, intClass)))
        assertNull(cls.classify("checkPermission", intClass,
            listOf(strClass, intClass, strClass)))
        assertNull(cls.classify("checkPermission", Boolean::class.javaPrimitiveType!!,
            listOf(strClass, strClass, intClass)))
        assertNull(cls.classify("checkPermission", intClass,
            listOf(strClass, strClass, Boolean::class.javaPrimitiveType!!)))
    }
}
