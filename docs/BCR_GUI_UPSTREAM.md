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
  The app still has just one launcher, `com.chiller3.bcr`.

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
npx cap copy android
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
