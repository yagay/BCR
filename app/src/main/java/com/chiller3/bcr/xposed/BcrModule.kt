package com.chiller3.bcr.xposed

import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Experimental, narrowly scoped permission bridge for a user-installed BCR.
 *
 * This only changes Java permission-service answers for BCR. It does not grant
 * package permissions in persistent state or bypass native audioserver policy.
 * A failed hook falls back to the original result.
 */
class BcrModule : XposedModule() {
    private val grantedCount = AtomicInteger(0)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "Loaded in " + param.processName + " systemServer=" + param.isSystemServer)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // Keep the experimental implementation off unsupported Android releases.
        if (Build.VERSION.SDK_INT < 36) {
            Log.w(TAG, "Skipping permission bridge: unsupported Android release")
            return
        }

        var count = 0
        for (name in SERVICES) {
            val clazz = try {
                Class.forName(name, false, param.classLoader)
            } catch (_: ClassNotFoundException) {
                continue
            } catch (t: Throwable) {
                Log.w(TAG, "Could not load " + name, t)
                continue
            }
            for (method in clazz.declaredMethods.filter(::supportsSignature)) {
                try {
                    method.isAccessible = true
                    hook(method).setId("bcr-permissions").intercept(permissionHooker(method))
                    count++
                    Log.i(TAG, "Hooked " + method.toGenericString())
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to hook " + method.toGenericString(), t)
                }
            }
        }
        Log.i(TAG, "Permission hooks installed=" + count +
            "; native AudioPolicy and Telecom compatibility still require device testing")
    }

    private fun supportsSignature(method: Method): Boolean {
        if (method.returnType != Int::class.javaPrimitiveType) return false
        val types = method.parameterTypes
        return when (method.name) {
            // Package name and permission must both be present as strings.
            "checkPermission" -> types.count { it == String::class.java } >= 2
            // AOSP permission name followed by target uid.
            "checkUidPermission" -> types.size >= 2 &&
                types[0] == String::class.java && types[1] == Int::class.javaPrimitiveType
            else -> false
        }
    }

    private fun permissionHooker(method: Method) = XposedInterface.Hooker { chain ->
        val original = chain.proceed()
        if (original != PackageManager.PERMISSION_DENIED) return@Hooker original

        val args = chain.args
        val permission = args.filterIsInstance<String>().firstOrNull { it in PRIVILEGED }
            ?: return@Hooker original
        val forBcr = if (method.name == "checkPermission") {
            args.any { it == PACKAGE_NAME }
        } else {
            val uid = args.getOrNull(1) as? Int ?: return@Hooker original
            belongsToBcr(uid)
        }

        if (!forBcr) return@Hooker original
        if (grantedCount.incrementAndGet() <= MAX_LOGS) {
            Log.w(TAG, "Experimental grant for BCR only: " + permission +
                " via " + method.declaringClass.simpleName)
        }
        PackageManager.PERMISSION_GRANTED
    }

    private fun belongsToBcr(uid: Int): Boolean {
        if (uid < Process.FIRST_APPLICATION_UID) return false
        return try {
            // Use package-manager ownership, not caller Binder uid. Audioserver
            // asks about another uid, so Binder.getCallingUid() is not BCR.
            val globals = Class.forName("android.app.AppGlobals")
            val pm = globals.getMethod("getPackageManager").invoke(null) ?: return false
            val lookup = pm.javaClass.getMethod(
                "getPackagesForUid", Int::class.javaPrimitiveType!!)
            val names = lookup.invoke(pm, uid) as? Array<*> ?: return false
            names.any { it == PACKAGE_NAME }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot verify uid=" + uid + "; keeping original permission result", t)
            false
        }
    }

    private companion object {
        const val TAG = "BCR/LSPosed"
        const val PACKAGE_NAME = "com.chiller3.bcr"
        const val MAX_LOGS = 8
        val PRIVILEGED = setOf(
            "android.permission.CAPTURE_AUDIO_OUTPUT",
            "android.permission.CONTROL_INCALL_EXPERIENCE",
        )
        val SERVICES = listOf(
            "com.android.server.pm.permission.PermissionManagerServiceImpl",
            "com.android.server.permission.access.permission.PermissionService",
        )
    }
}
