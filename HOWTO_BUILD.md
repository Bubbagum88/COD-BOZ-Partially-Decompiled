# Build notes

## Building

```
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # signed only when keystore.properties exists
```

Requirements:
- JDK 21 (Temurin)
- Android SDK platform and build-tools 36
- The Gradle wrapper downloads Gradle 8.11.1

`gradle.properties` is git-ignored. Create one before building:

```
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

For release signing, add a `keystore.properties` (git-ignored) with `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`. Without that file the release signing
config is skipped. CI signs with repository secrets instead (see `.github/workflows/build.yml`).

## Regenerating Kotlin skeletons

`buildtools/syntex_to_kt.py` converts smali into Kotlin skeletons, with each original method
body kept as a comment. You need your own smali dump of the original APK (for example from
apktool). The committed `buildtools/smali_out/` is a reference snapshot, not a build input.

The upstream README also described `decompile_jar.py`, `smali_to_kt.py`, `extract_7z.py`,
`disable_sig_check.py` and `check_*.py`. Those scripts were never committed to this repo.

## JNI-bound classes

The native libraries in `jniLibs/` look these up by name. **Do not rename their methods
or fields, or change field types:**

- `com.ideaworks3d.marmalade.LoaderActivity`: callback hub for `libs3e_android.so`
- `com.ideaworks3d.marmalade.LoaderThread`: engine start, OS tick, surface lifecycle, `onMotionEvent`
- `com.ideaworks3d.marmalade.LoaderView`: GL surface host
- `com.ideaworks3d.marmalade.LoaderActivitySlave`
- `com.ideaworks3d.marmalade.S3EVideoView`
- `com.ideaworks3d.marmalade.s3eTouchpad`: touchpad queries and `onMotionEvent` (used by the controller support)
- `com.ideaworks3d.studio.IsDevice`: `libIsDevice.so` callbacks, notably `IsDeviceKeyCallback`
- `com.ideaworks3d.studio.IsDeviceActivity.getInstance()`

`GamepadInput` and `ControllerSettingsActivity` are not bound to native code and can be
changed freely.
