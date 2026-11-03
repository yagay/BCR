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
 * Experimental, fail-closed Java permission bridge for *only* the BCR app.
 *
 * Not a replacement for privileged installation when native AudioPolicy or
 * Telecom enforce privileges independently of the Java permission service.
 */
class BcrModule : XposedModule() {
    private val grantLogCount = AtomicInteger(0)
    private val uidLookupGuard = ThreadLocal<Boolean>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(TAG, "Loaded process=${param.processName} systemServer=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (Build.VERSION.SDK_INT < 36) {
            Log.i(TAG, "Skip: permission bridge requires Android 16+")
            return
        }

        var installed = 0
        for (name in SERVICE_CLASSES) {
            val serviceClass = try {
                Class.forName(name, false, param.classLoader)
            } catch (_: ClassNotFoundException) {
                Log.i(TAG, "Not present: $name")
                continue
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to inspect $name", t)
                continue
            }

            for (method in serviceClass.declaredMethods) {
                val kind = PermissionMethodClassifier.classify(method) ?: continue
                try {
                    method.isAccessible = true
                    hook(method).setId("bcr-permission-${kind.name.lowercase()}").intercept(
                        permissionHooker(kind),
                    )
                    installed++
                    Log.i(TAG, "Hook installed: ${method.toGenericString()}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Hook failed: ${method.toGenericString()}", t)
                }
            }
        }
        Log.i(TAG, "Installed permission hooks=$installed (Java only; verify real recording)")
    }

    private fun permissionHooker(kind: PermissionMethodClassifier.Kind) =
        XposedInterface.Hooker { chain ->
            val original = chain.proceed()
            if (original != PackageManager.PERMISSION_DENIED) return@Hooker original

            val args = chain.args
            val permission = args.getOrNull(1) as? String ?: return@Hooker original
            if (permission !in PRIVILEGED_PERMISSIONS) return@Hooker original

            val isBcr = when (kind) {
                PermissionMethodClassifier.Kind.PACKAGE -> args.getOrNull(0) == PACKAGE_NAME
                PermissionMethodClassifier.Kind.UID -> {
                    val uid = args.getOrNull(0) as? Int ?: return@Hooker original
                    belongsToBcr(uid)
                }
            }
            if (!isBcr) return@Hooker original

            if (grantLogCount.incrementAndGet() <= MAX_GRANT_LOGS) {
                Log.w(TAG, "Java permission override for BCR: $permission, kind=$kind")
            }
            PackageManager.PERMISSION_GRANTED
        }

    private fun belongsToBcr(uid: Int): Boolean {
        if (uid < Process.FIRST_APPLICATION_UID || uidLookupGuard.get() == true) return false
        uidLookupGuard.set(true)
        return try {
            // Look up *the checked UID*; Binder.getCallingUid() belongs to the
            // service caller and is not necessarily the recorded application's UID.
            val appGlobals = Class.forName("android.app.AppGlobals")
            val pm = appGlobals.getMethod("getPackageManager").invoke(null) ?: return false
            val method = pm.javaClass.getMethod(
                "getPackagesForUid", Int::class.javaPrimitiveType!!,
            )
            val packages = method.invoke(pm, uid) as? Array<*> ?: return false
            packages.any { it == PACKAGE_NAME }
        } catch (t: Throwable) {
            // Never grant a permission when package identity can't be proven.
            Log.w(TAG, "UID ownership query unavailable; keep denial", t)
            false
        } finally {
            uidLookupGuard.remove()
        }
    }

    private companion object {
        const val TAG = "BCR/LSPosed"
        const val PACKAGE_NAME = "com.chiller3.bcr"
        const val MAX_GRANT_LOGS = 8
        val PRIVILEGED_PERMISSIONS = setOf(
            "android.permission.CAPTURE_AUDIO_OUTPUT",
            "android.permission.CONTROL_INCALL_EXPERIENCE",
        )
        val SERVICE_CLASSES = listOf(
            // Newer AOSP permission manager.
            "com.android.server.permission.access.permission.PermissionService",
            // Older and vendor permission service implementations.
            "com.android.server.pm.permission.PermissionManagerServiceImpl",
            "com.android.server.pm.permission.PermissionManagerService",
        )
    }
}
