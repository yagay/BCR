# BCR Suite: upstream-safe single-APK build

## Independent source history

- `yagay/BCR` is a fork of `chenxiaolong/BCR`. The existing
  `master` and `feature/lsposed-root` branches are unchanged.
- `external/bcr-gui` is a **git submodule** pointing to
  `yagay/bcr-gui` (`dev`); that project tracks `nicorac/bcr-gui`.
- `integrations/bcrgui-bridge` is the downstream adapter only.
  It compiles GUI's original Java, resources and Capacitor built web
  assets as an Android library, without copying its source history.
- The app module includes this library only with `-PsuiteEnabled=true`.
  The Suite's only enabled launcher is the BCR-GUI bridge activity. The BCR
  `SettingsActivity` is opened from inside GUI and its existing launcher alias
  remains enabled only for standalone BCR builds.

## Updating either upstream separately

Update BCR's `master` from `chenxiaolong/BCR` as usual; then merge
your updated BCR branch into `feature/bcr-gui-suite`.

Update GUI's `dev` from `nicorac/bcr-gui` in its **own fork**.
Then update the suite's pointer without mixing Git commits:

```bash
git submodule update --init
cd external/bcr-gui
git fetch origin dev
git checkout --detach origin/dev
cd ../..
git add external/bcr-gui
git commit -m "chore(suite): sync BCR-GUI submodule"
```

## Build

```bash
git submodule update --init
cd external/bcr-gui
npm ci
npm run build
npx cap sync android
cd ../..
./gradlew --dependency-verification lenient -PsuiteEnabled=true :app:assembleDebug -x :app:archive
```

Standalone BCR still builds without any Node.js, submodule, or Capacitor
dependencies: `./gradlew :app:assembleDebug`.

## Important compatibility considerations

BCR-GUI uses Ionic/Angular with Capacitor 6 and native SAF, contacts,
and Media3 AudioPlayer plugins. It cannot be integrated by copying
static HTML into a vanilla Android WebView. The original GUI sources
are compiled in the bridge library and Capacitor native Gradle projects
are included only for the opt-in suite build.

BCR is a modern AGP 9.x / SDK 37 project, whereas GUI's standalone
build uses older AGP/Capacitor versions. CI must prove compilation
before this is treated as a functional combined APK.

GUI's standalone app preferences and SAF storage grants are tied to
`com.github.nicorac.bcrgui`, whereas the combined APK remains
`com.chiller3.bcr`. A combined installation cannot automatically
read or migrate the standalone GUI app's private storage. The initial
combined app must select the recordings folder again.

Do not merge this integration into `master` until the combined app
is built and real-device-tested.

## Downstream compatibility shims

Suite CI currently patches only **installed** `node_modules` Android Gradle
scripts to replace AGP's removed `proguard-android.txt` option with
`proguard-android-optimize.txt`. Neither upstream repository source nor
submodule commit changes. This integration shim can be removed once the
Capacitor dependency is upgraded or can build natively with AGP 9.

## Capacitor plugin registration

The Suite CI must use `npx cap sync android`, not merely `cap copy`,
so that `assets/capacitor.plugins.json` is generated. The resulting APK
is inspected for this file, the GUI web entry, and the original BCR
LSPosed module resources. Without plugin registration the GUI's
Capacitor-native features can fail even when the APK compiles.

## GUI-first navigation (Suite overlay)

The merged Suite APK launches `SuiteGuiActivity` directly, which extends
the unchanged GUI `MainActivity`. The original BCR Settings launcher alias
is disabled via `bcrSettingsLauncherEnabled` placeholder **only** when
`-PsuiteEnabled=true`, so standalone BCR behaves as before.

The Suite native bridge registers a `BcrSuite` Capacitor plugin with a single
`openBcrSettings` action. It launches the original BCR
`com.chiller3.bcr.settings.SettingsActivity` as an internal screen;
Android Back returns to BCR-GUI. No privileged permissions are exposed
to JavaScript.

The `integrations/bcrgui-bridge/apply_navigation_overlay.py` script
modifies **only the temporary Git-submodule worktree at build time**,
inserting a BCR entry in GUI's side navigation and Settings page.
There are no commits to `yagay/bcr-gui` or its original upstream.
The overlay deliberately fails when the relevant GUI templates change
so that upstream merges cannot silently remove the BCR entry.
