# Experimental BCR LSPosed + Root build

This branch keeps BCR's original recorder, InCallService, Magisk ZIP and signing behavior intact. It adds an *experimental* APK-first option for Android 16+ with LSPosed API 102 and an optional KernelSU/su helper.

## Installation and test

1. Build the debug APK with `./gradlew :app:assembleDebug`. Install the APK normally. Do not replace your known-working recorder without a backup.
2. In LSPosed, enable BCR and select **System Framework (`system`)**, not Android System (`android`). Restart the device to load the `system_server` hook.
3. Open BCR → LSPosed + Root integration. Check `CAPTURE_AUDIO_OUTPUT` and `CONTROL_INCALL_EXPERIENCE` Java permission status.
4. Tap **Check KernelSU / su** to request Root. After the first device unlock, optionally run **Fix READ_CALL_LOG restriction with Root**; this uses BCR's existing CLI and does **not** grant signature permissions.
5. Make test calls for inbound/outbound, hold, Bluetooth and VoLTE. Examine BCR's recording notifications and `logcat -s BCR/LSPosed` alongside its regular logcat.

## Implementation boundaries

- The module only hooks matching Java permission checks on the **BCR package/UID** in `system_server`. The original results are preserved for every other package/UID, and on failed lookups.
- Root is used only after a user action to invoke the pre-existing BCR hard-restriction helper. It does not run a daemon or disable SELinux.
- Modern audioserver may use a native PermissionProvider/AudioPolicy path that does **not** consult hooked Java checks. A granted Java check is **not proof** that `VOICE_CALL` can record two-way audio.
- Telecom service discovery may apply additional restrictions. This branch has **not** been confirmed to work without `/system/priv-app` on OxygenOS 16.
- If the original ZIP is installed, privileged permission grants may come from it, not LSPosed. Remove it only if you have verified the replacement and have a recovery plan.
- Disable the module's **system** scope and reboot if `system_server` becomes unstable. All reflection hooks fail closed; they do not rewrite SELinux policy.
- Recording without consent may be restricted by local law. Check applicable laws before using call recording.

## Unchanged fallback

Original systemless install and `zipDebug` packaging still work. Do not publish this as a complete replacement until native capture and InCallService binding are verified on target devices.

## Android 16 signature audit

AOSP permission service checks accept `checkPermission(package, permission, userId)`, `checkPermission(package, permission, device, userId)`, and `checkUidPermission(uid, permission[, device])`. In this branch the hook classifier recognizes these **ordered** signatures, rather than searching all arguments for matching values. It will not hook an unknown method shape. The UID path checks package ownership and fails closed on lookup errors. Unit tests cover the signature classifier.

CI has `workflow_dispatch` for manual re-runs where GitHub Actions is enabled. Actions can be disabled for a fork; this is a repository setting outside the code changes.
