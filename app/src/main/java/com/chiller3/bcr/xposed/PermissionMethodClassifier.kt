package com.chiller3.bcr.xposed

import java.lang.reflect.Method

/**
 * Matches only the AOSP permission checks with explicit package/UID identity.
 *
 * Do not guess argument positions from all String / Int values: Android 14+
 * adds a persistent device id or device index to these methods.
 */
internal object PermissionMethodClassifier {
    enum class Kind { PACKAGE, UID }

    fun classify(method: Method): Kind? =
        classify(method.name, method.returnType, method.parameterTypes.toList())

    fun classify(name: String, result: Class<*>, args: List<Class<*>>): Kind? {
        if (result != Int::class.javaPrimitiveType || args.size !in 2..4) return null
        val stringClass = String::class.java
        val intClass = Int::class.javaPrimitiveType
        return when {
            name == "checkPermission" &&
                args.size >= 3 &&
                args[0] == stringClass &&
                args[1] == stringClass &&
                args.drop(2).all { it == intClass || it == stringClass } &&
                args.last() == intClass -> Kind.PACKAGE

            name == "checkUidPermission" &&
                args[0] == intClass &&
                args[1] == stringClass &&
                args.drop(2).all { it == intClass || it == stringClass } -> Kind.UID

            else -> null
        }
    }
}
