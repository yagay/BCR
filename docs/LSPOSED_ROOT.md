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

## BCR call lifecycle diagnostics

The integration page stores **timestamps only** in BCR's device-protected preferences. No phone numbers, call details or audio are added to diagnostics. After a test call, press **Refresh permission and call status**.

- No **InCallService created** timestamp: Android has probably not bound the BCR service; inspect Telecom eligibility, package visibility and module logs.
- Service created, but **Call received by InCallService** is missing: the service has not observed the tested call.
- Call observed, but **Recorder thread requested** is missing: inspect BCR's recording switch, runtime permissions and rules.
- Recorder thread requested, but no usable output audio: inspect native AudioPolicy and the device's call capture support. This timestamp does **not** prove audio capture worked.

If **Installation mode** says *System application*, the original ZIP or a different privileged placement can explain successful operation. Test the user-installed path separately only when you have a safe recovery option.

### Native audio authorization

On Android 16, `AudioServerPermissionProvider` synchronizes protected permission state to native audioserver. Java-only permission overrides are not a complete replacement for `CAPTURE_AUDIO_OUTPUT`, and Android documents voice-call capture as reserved for privileged preinstalled apps. If non-privileged APK testing fails despite Java permission checks, prefer a **minimal KernelSU privileged placement shim** over adding global SELinux exemptions or broadly patching audioserver. Such a shim would still require an initial boot-time integration and has not been implemented here.

## Scope selection in LSPosed Manager (scope UI fix)

Modern libxposed API 102 reads the module entry and recommended scope from the three
`META-INF/xposed/` resources embedded in the APK, not the old `xposed_init` assets.
Earlier experimental builds used `staticScope=true`, meaning the framework treated the
`system` target as a fixed scope. Now `staticScope=false` allows managing the recommended
scope in the LSPosed UI.

1. Install the latest Debug APK; it should replace this same application package (keep existing signing consistent).
2. In LSPosed Manager, open the BCR module and enable it.
3. Scope should suggest **System Framework** (`system` / `system_server`). Select that scope.
4. **Do not** select `android` or `com.android.phone` for this implementation. They have separate processes, and no BCR hooks are installed there.
5. Reboot before checking the integration diagnostics. The Java-permission bridge does not guarantee native audio capture.

If scopes are missing despite reinstalling, confirm the installed APK has
`META-INF/xposed/{module.prop,java_init.list,scope.list}` and that the manager
actually supports modern libxposed API 102. If no module is detected, inspect
the LSPosed framework logs for rejection errors.

CI now inspects all three files in the **built APK**, including `staticScope=false`
and `scope.list=system`, rather than checking only the source tree.

## Experimental independent VoIP capture

The experimental build now contains an independent root-backed VoIP recording
path, inspired by **JemRec** (Apache-2.0) and **ShizuCallRecorder** (GPL-3.0).
These are references to the original projects, not a wholesale copy of their
capture daemons.

**Current capabilities and limitations:**
- Opt-in package switches: WhatsApp, WhatsApp Business, Telegram, Telegram X,
  Signal, Viber, Google Meet and WeChat; all OFF by default.
- Manual start/stop of a stand-alone Android AudioPolicy capture session through
  KernelSU `su` and `app_process`. No modification of third-party APKs.
- On Android 14+, experimental best-effort automatic start from explicit ongoing
  call-category notifications, enabled only after selecting apps and granting
  notification-listener access. May not work on OEM devices due to background
  execution limits; no content of notifications is retained.
- Root path currently records **only VOIP PLAYBACK** (the possible remote party),
  **not proven two-sided audio**. A microphone loop is deliberately disabled
  because it may interrupt third-party voice calls. Real success is not assumed.
- Results are moved to BCR's configured output folder under `VoIP/` as `.wav`,
  with MediaStore fallback in `Recordings/BCR-VoIP`. No persistent root daemon and no auto-start at boot.
- Normal BCR Telecom/VOICE_CALL recording remains unchanged. Do not run both
  capture paths concurrently for the same call.

**Test steps:**
1. Open BCR Settings → VoIP recording (experimental).
2. Check Audio Mode and Shell permission diagnostics; enable only one installed app.
3. Press the corresponding manual test button while on a permitted call.
4. Stop VoIP capture. Confirm a WAV file appears and contains meaningful audio.
5. Only after manual capture works, enable notification access and automatic
   detection. It requires a matching ongoing call notification and communication mode.
6. If the Root AudioPolicy path fails on OxygenOS, inspect logcat tag `BCR/VoIPRoot`
   and BCR's VoIP status. Do not grant global permissions or relax SELinux.

**Upstream references:**
- https://github.com/jemcik/JemRec
- https://github.com/kitsumed/ShizuCallRecorder

Note: Shell UID and Root UID have different Android permission and SELinux
identities. This proof-of-concept uses Root and does not claim identical
capabilities to ShizuCallRecorder/JemRec's Shell-UID daemons.

### Output location and capture process safety

Successful VoIP `.wav` files are first moved to the same configured BCR output
folder as normal calls, under `VoIP/`. If that SAF operation fails, a copy is
saved via MediaStore under `Recordings/BCR-VoIP`. The in-app status shows the
actual destination. The recording process also stops if the BCR application
process that launched it exits, or after the hard 2-hour limit. Audio capture
continues to require explicit opt-in.

### Notification timing and media mix

Notification-based call detection retries AudioManager communication mode
for up to 800 ms after an ongoing call notification arrives. This is
best-effort; no content of notification messages is read and apps without
Android `CATEGORY_CALL` notifications must be started manually.

AudioPolicy captures a system voice-communication playback mix, not a
cryptographically authenticated stream identified by the selected package.
Only enable recording for a call you are taking part in; do not assume
other active voice sessions cannot be included in a recording.

## Ordinary call recording loudness (PCM gain)

The regular BCR `VOICE_CALL`/`VOICE_UPLINK`/`VOICE_DOWNLINK` recording path
previously passed raw PCM straight to the encoder without a digital loudness
adjustment. The **Output format → Recording volume boost** option now offers
Off (default), +3 dB, +6 dB and +9 dB. The selected value is applied to
16-bit PCM in the existing encoding loop, independent of Opus/AAC/FLAC/WAV
output format. A bounded soft-knee limiter reduces sudden peak clipping,
although boosting a noisy or distorted source cannot restore quality.

This does **not** alter system call volume, microphone hardware gain, the
LSPosed permission hook or files already recorded. The VoIP Root
AudioPolicy prototype remains separately implemented and is not affected
by this setting.

For a fair comparison with the phone's stock recorder, use the same
call and playback device, try +3 dB first, then +6 dB, and check whether
the BCR output is mono `VOICE_CALL` or stereo `VOICE_UPLINK_DOWNLINK`.
A stereo recording downmixed by a player may be perceived as softer than
a mixed mono recording. Digital gain cannot recover audio that Android
does not capture.
