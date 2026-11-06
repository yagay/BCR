package com.chiller3.bcr.xposed

/**
 * Decode the two AOSP permission service signatures without guessing.
 *
 * checkPermission(permission, packageName, userId, ...): permission first.
 * checkUidPermission(uid, permission, ...): UID first.
 *
 * Unexpected arguments return null, preserving the original denial.
 */
internal data class PermissionCheckRequest(
    val permission: String,
    val packageName: String? = null,
    val uid: Int? = null,
) {
    companion object {
        fun parse(
            kind: PermissionMethodClassifier.Kind,
            args: List<Any?>,
        ): PermissionCheckRequest? = when (kind) {
            PermissionMethodClassifier.Kind.PACKAGE -> {
                val permission = args.getOrNull(0) as? String
                val packageName = args.getOrNull(1) as? String
                if (permission.isNullOrBlank() || packageName.isNullOrBlank()) null
                else PermissionCheckRequest(permission, packageName = packageName)
            }
            PermissionMethodClassifier.Kind.UID -> {
                val uid = args.getOrNull(0) as? Int
                val permission = args.getOrNull(1) as? String
                if (uid == null || permission.isNullOrBlank()) null
                else PermissionCheckRequest(permission, uid = uid)
            }
        }
    }
}
