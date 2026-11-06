# BCR Xposed source isolation (current manager branch)

## Design

The Android `xposed` product flavor compiles the BCR upstream core from `app/src/main` with the fork-specific overlay in `app/src/xposed`. The overlay includes the **native recordings manager** as well as LSPosed API 102, optional Root helpers, VoIP experiments and PCM gain.

- `app/src/xposed/java/`: all newly added Kotlin/Java packages, including `manager`, `xposed`, `root`, `voip`, `audio` and diagnostics.
- `app/src/xposed/AndroidManifest.xml`: extra services, activities, permissions, package visibility, and the launcher alias override selecting the native recordings manager.
- `app/src/xposed/res/`: manager icons and extension-only strings.
- `app/src/xposed/resources/META-INF/xposed/`: module metadata packaged into the APK.
- `app/src/testXposed/java/`: extension-specific tests.

The upstream-facing `app/src/main/AndroidManifest.xml` and `app/src/main/res/values/strings.xml` are restored byte-for-byte from the fork's `master` baseline. The Android manifest merger combines the fork overlay at build time; **do not move** the Xposed alias override back to `main`.

## Coupling that still requires review

Moving source files reduces path collisions but does not eliminate upstream conflicts. These currently modified upstream-owned files remain integration touchpoints:

- `app/build.gradle.kts`: flavor configuration, libxposed dependency and resource packaging.
- `app/src/main/java/com/chiller3/bcr/Preferences.kt`: recording gain.
- `app/src/main/java/com/chiller3/bcr/RecorderInCallService.kt`: lifecycle diagnostic events.
- `app/src/main/java/com/chiller3/bcr/RecorderThread.kt`: optional PCM gain.
- `app/src/main/java/com/chiller3/bcr/settings/OutputFormatScreen.kt`: gain controls.
- `app/src/main/java/com/chiller3/bcr/settings/SettingsScreen.kt`: integration and VoIP entry points.
- `app/proguard-rules.pro` and `gradle/verification-metadata.xml`: build metadata.
- `.github/workflows/ci.yml`: extension Debug CI.

This change intentionally keeps the recording and UI logic intact. Long-term full separation would require **small, stable interface ports** for settings, recording events and PCM processing, backed by no-op upstream defaults.

## Debug build without Magisk ZIP

```bash
./gradlew :app:assembleXposedDebug :app:testXposedDebugUnitTest -x :app:archive
```

Look in `app/build/outputs/apk/xposed/debug/`. The CI workflow builds only that Debug APK, validates Xposed metadata inside it, and does not invoke ZIP tasks. The experimental Xposed Java permission bridge is not proof of native AudioPolicy/Telecom privileges.

## Synchronizing original upstream

```bash
git remote add upstream https://github.com/chenxiaolong/BCR.git  # first setup only
git fetch upstream
git switch feature/native-recordings-manager
git merge upstream/master
./gradlew :app:assembleXposedDebug :app:testXposedDebugUnitTest -x :app:archive
```

For production, first merge the reviewed isolation refactor into `feature/native-recordings-manager`, then synchronize upstream there. Resolve changes in the remaining touchpoints by inspection; never discard all fork edits in one command. Confirm the merged manifest still launches the native manager, the APK includes `META-INF/xposed`, and phone recordings retain their expected behavior.
