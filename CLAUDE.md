# Watch APK Installer

Android phone app that installs a single APK file onto a Wear OS watch (built for the Galaxy Watch 6) over Wi-Fi. The phone acts as an ADB client: it pairs with the watch's Wireless debugging, connects, and runs an ADB install.

## Build

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. `WatchApkInstaller.apk` in the project root is a copy of a debug build, not a build output; do not commit it.

- Needs JDK 17+ and an Android SDK with platform `android-37.0` and build-tools 37. Point `local.properties` (`sdk.dir=...`) or `ANDROID_HOME` at the SDK.
- Use the wrapper (Gradle 9.9.1). A system Gradle 8.x cannot configure this project.
- Maven Central sometimes answers 429 during a cold dependency download. Wait and re-run with `--max-workers=1`; it is not a build error.
- There are no tests and no lint configuration yet.

## Toolchain constraints

These versions depend on each other; change them together.

- `com.flyfishxu:kadb:2.1.5` declares `minCompileSdk=37`, so `compileSdk` must stay at 37 or higher.
- compileSdk 37 needs Android Gradle Plugin 9.x, which needs Gradle 9.x.
- AGP 9 has built-in Kotlin: there is no `kotlin-android` plugin. The Kotlin version is set by the `org.jetbrains.kotlin.plugin.compose` plugin version in the root `build.gradle.kts` (2.4.20, matching Kadb's Kotlin stdlib).
- Kadb depends on `com.github.Flyfish233:spake2-java`, which is published only on JitPack. The JitPack repository in `settings.gradle.kts` is limited to `com.github.*` groups; keep that filter.
- `minSdk` is 28 because pairing needs the platform's TLS 1.3 support (Android 9+).
- The `packaging.resources.excludes` entries in `app/build.gradle.kts` resolve duplicate license files across the BouncyCastle jars. Removing them breaks `mergeDebugJavaResource`.

## Code layout

All code is in `app/src/main/java/com/watchinstaller/app/`.

- `MainActivity.kt`: the single activity and the whole Compose UI (setup steps, watch address, pair, install, activity log). Also receives APKs opened with or shared to the app (`ACTION_VIEW` / `ACTION_SEND`).
- `InstallerViewModel.kt`: all state and ADB work. `pair()`, `testConnection()`, `install()`, `onApkPicked()`. Translates exceptions into user-facing messages in `describe()`.
- `AdbDiscovery.kt`: finds the watch on the local network with `NsdManager` (`_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp`) to pre-fill the IP address and ports.

## How it works

- Pairing: `Kadb.pair(host, pairingPort, code)` (suspend). The pairing port and 6-digit code come from the watch's "Pair new device" screen and change every time it is opened.
- Connecting: `Kadb.create(host, connectPort)` is lazy; the first `shell(...)` call opens the connection. The connect port is a different port from the pairing port and changes each time Wireless debugging is switched on.
- Installing: the picked APK is copied to `cacheDir/selected.apk`, validated with `PackageManager.getPackageArchiveInfo`, then sent with `kadb.install(file, "-r")`.
- Identity: the phone's ADB private key is stored at `filesDir/adb_key.pem` through `KadbCert.configure(...)` in the view model's `init`. The watch remembers this key after pairing, so clearing app data or changing the key path forces a re-pair.

## Conventions

- Kadb's connect, shell and install calls block. Run them through the view model's `run { }` helper, which uses `Dispatchers.IO`, sets `busy`, and logs failures.
- Change Compose state (`apk`, `log`, text fields) on the main thread; inside `run { }` use `withContext(Dispatchers.Main)` or the `say()` helper.
- `NsdManager` callbacks arrive on a background thread. `AdbDiscovery` posts everything to the main handler and resolves services one at a time, because older Android versions reject concurrent resolves.
- UI uses Material 3 only. There is no icons dependency; do not import `androidx.compose.material.icons`.
- User-facing errors should say what to check on the watch, not show raw exception text.

## Limitations

- Only single `.apk` files. Bundles (`.apks`, `.xapk`, `.apkm`) are rejected; supporting them would use `kadb.installMultiple(...)`.
- No release signing config: only the debug build exists, signed with the local debug key.
- The pairing and install flow has not been verified on a physical watch. Changes to it need a manual test on a device with Wireless debugging on.
