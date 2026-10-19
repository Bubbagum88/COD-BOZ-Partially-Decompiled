# Black Ops Zombies on modern Android, with controller support

A fork of [eugene373/COD-BOZ-Partially-Decompiled](https://github.com/eugene373/COD-BOZ-Partially-Decompiled),
the Kotlin port of *Call of Duty: Black Ops Zombies* for Android (Activision, 2012).

## About

Black Ops Zombies was built on the Marmalade engine, which was discontinued long ago.
Almost all of the game runs as native ARM code. A thin Java layer, now ported to Kotlin
here, starts that code and passes it touch, keys, sensors and storage. The upstream port
got that layer compiling and running on current Android.

This fork has two goals:

1. **Play it with a controller.** The game was never written for a gamepad. It does
   support one other dual-analog device, though: the Sony Xperia Play's touchpad. This fork
   feeds controller sticks into that touchpad path, so movement and aiming work without
   any change to the native code.
2. **Document how the app works under the hood.** The sections below record what I found
   while doing that: how the shell starts the engine, how input reaches the engine, and
   which values the engine expects.

**Status:** builds in CI, runs on modern phones, and is playable with a controller.
Game data still has to come from the original release (see [Game data](#game-data)).

---

## How the app works under the hood

### Two halves: a Kotlin shell and a native engine

| Layer | Where | What it does |
|---|---|---|
| Kotlin shell | `app/src/main/java/` (105 files) | Android lifecycle, permissions, the GL surface, and input. It forwards events to native code over JNI. |
| Marmalade runtime | `jniLibs/armeabi-v7a/libs3e_android.so` | The engine. It loads the game, renders through GLES, and calls back into the shell. |
| Game | `assets/boz.s3e` | An LZMA-compressed s3e container holding the game's 32-bit ARM executable and its config. |
| Data | `assets/blackops_loader.dz`, `blackops_*.dz` | Game data and tuning values. Texture blobs are downloaded to `Android/obb/com.activision.boz/` on first launch. |

`boz.s3e` contains 32-bit ARM code, so the build packages **only `armeabi-v7a`**.
If the arm64 libraries are included, a 64-bit phone starts the app as a 64-bit process
and the engine has nothing it can run, which shows up as a black screen.
The `arm64-v8a/` and `armeabi/` folders in `jniLibs/` are left over from upstream and
are not packaged.

### Boot sequence

```
Main  (launcher activity, empty subclass)
 └─ IsDeviceActivity      storage-permission prompt, controller HUD prompt, OBB folder setup
     └─ LoaderActivity    System.loadLibrary(...), input routing, lifecycle → engine
         ├─ LoaderView    GL surface; key events go to LoaderKeyboard → native
         └─ LoaderThread  runNative(...) starts boz.s3e; onMotionEvent, sensors, suspend/resume
```

`IsDeviceActivity` can hold the engine back with `m_DeferLoader`. If storage access is
missing, or the HUD question hasn't been answered yet, it shows a dialog and then calls
`recreate()`. The second run of `onStart()` then starts the loader with the answer in place.

### JNI-bound classes: don't rename them

The native libraries look up these classes, methods and fields by name:
`LoaderActivity`, `LoaderThread`, `LoaderView`, `LoaderActivitySlave`, `S3EVideoView`,
`s3eTouchpad`, `IsDevice`, and `IsDeviceActivity.getInstance()`. Renaming any of them,
or changing a field's JVM type (for example boxed `Integer` to `Int`), breaks the native
side at runtime even though it still compiles. New code such as `GamepadInput` and
`ControllerSettingsActivity` has no native binding and can be changed freely.

---

## Controller support

### The key finding: use the Xperia Play touchpad path

The engine has no gamepad API. It does have **`s3eTouchpad`**, Marmalade's support for the
Xperia Play's rear touchpad. The game treats that pad as two analog sticks: the left
half moves and the right half aims. Input reaches the engine through one native call:

```kotlin
s3eTouchpad.onMotionEvent(pointerId, action, x, y)   // external, implemented in libs3e_android.so
```

So this fork never touches the engine. It creates a **virtual touchpad** with the Xperia
Play's dimensions, then moves two virtual fingers on it from the controller's sticks.

### Input flow

```
Controller
 ├─ sticks / hat / analog triggers → LoaderActivity.onGenericMotionEvent
 │                                     └─ GamepadInput.onMotion
 │                                          ├─ sticks  → s3eTouchpad.updateVirtualStick → onMotionEvent (native)
 │                                          └─ hat, triggers → synthesized D-pad / L2 / R2 key presses
 └─ buttons → LoaderActivity.onKeyDown/Up → xperiaKeyCode() → LoaderView.onKeyEvent → native
Touch screen → dispatchTouchEvent → unchanged path; also pauses the sticks while a finger is down
```

### What the engine expects (found by trial, logs and the game data)

- **Pad size.** The virtual pad reports 966 × 360, the Xperia Play's touchpad
  resolution, through `S3E_TOUCHPAD_WIDTH` / `S3E_TOUCHPAD_HEIGHT`.
- **Action codes are Android's plus 4.** The engine expects down = 4, up = 5, move = 6.
  The original touchpad code adds 4 to `MotionEvent` actions, and the virtual sticks do the same.
- **Stick centres come from the game data.** `blackops_loader.dz` defines
  `leftStickCenter = 0.2, 0.5` and `rightStickCenter = 0.8, 0.5` as fractions of the pad.
  Each virtual finger touches down at its centre and moves away from it. Centring them
  anywhere else gives drift or a dead area.
- **Y is not flipped for the virtual sticks.** Real touchpad events are flipped
  (`m_Height - y`), but stick-up has to produce a *lower* pad Y. Getting this wrong
  reverses forward/back movement and inverts look.
- **`S3E_TOUCHPAD_AVAILABLE` changes the HUD, not the input.** The engine reads the pad
  either way. Returning **1** switches to the compact Xperia HUD and turns off the
  on-screen camera stick. Returning **0** keeps the original HUD and all touch schemes.
  This fork returns whichever HUD the player picked.
- **The pad is set up once, when the engine starts.** `s3eTouchpadInit()` only creates the virtual pad
  if a controller with analog sticks is already connected. A controller connected later
  works for buttons, but its sticks only work after a restart.

### Making the sticks feel right

All of this is in `s3eTouchpad.updateVirtualStick` and `GamepadInput`:

- **Circle to square.** A stick reports positions inside a circle, so a full diagonal is
  only about 0.71 on each axis. The game reads the pad one axis at a time, which made diagonal
  movement slow. The stick vector is stretched onto the square, so full diagonals move as
  fast as straight ahead while straight and partial pushes are unchanged.
- **Gain.** The look stick gets a flat 1.5× on both axes and is limited to the right half
  of the pad, so it reaches equally far left and right. The game's own sensitivity setting
  still applies on top.
- **Dead zones** come from each controller's reported `flat` range, with a minimum of 0.15.
  A virtual finger touches down when its stick leaves the dead zone and lifts when it returns.
- **Touch screen has priority.** While a finger is on the screen, both virtual fingers
  lift so the on-screen controls never fight the controller. When the finger lifts, the
  last stick position is applied again, because a stick held still sends no new events.
- **Several controllers.** The controller that was used most recently drives the sticks.
  Switching controllers lifts the old virtual fingers first. Buttons work from any controller.
- **Releasing held input.** Sticks and synthesized keys are released on disconnect, pause
  and focus loss, so nothing stays stuck "held".

### Button mapping

Controller buttons are translated to the key codes the game already handles for the Xperia Play:

| Controller | Sent to the game | In game |
|---|---|---|
| A | `DPAD_CENTER` (Xperia ✕) | Confirm |
| B | `BACK` (Xperia ○) | Reload in play, back in menus |
| Select | `BACK` | Back. If the game ignores it, Android's Back is used |
| X / Y | unchanged (Xperia □ / △) | |
| D-pad (hat axes) | `DPAD_*` key presses | |
| L2 / R2 (analog) | `BUTTON_L2` / `BUTTON_R2` presses, with hysteresis (press > 0.6, release < 0.35) | |
| Volume / mute | not sent to the game | Android handles them |

If the game doesn't claim a B press, it is consumed anyway. Otherwise Android would turn it into
system Back and pause the game in the middle of a reload.

### HUD choice

When a controller is connected at launch, the game asks **Controller HUD** (compact) or
**Touch HUD** (original) before it starts. The prompt has a "Don't ask again" option.
To change the choice later, long-press the app icon and pick **Controller settings**.
That screen is a separate `ControllerSettingsActivity`, because the game's own Flash
menus can't take new widgets without new art.

### Approaches that didn't work

These are recorded so nobody has to try them again:

- **Adding the device to `XperiaDeviceID` in `game_config.icf`.** This puts the game fully
  into Xperia mode, which turns off on-screen touch controls completely.
- **Always reporting the virtual pad as available.** This locks the game into the pad scheme:
  the touch-scheme options stop working and the on-screen camera stick stops responding.
  It's why availability is now tied to the HUD choice.
- **Moving reload to its own key, separate from Back.** The game binds reload to the
  Xperia ○ key, which is `BACK`. There's no separate reload key to send, so both attempts
  were reverted.

### Source files

| File | Role |
|---|---|
| `marmalade/GamepadInput.kt` | Stick reading, dead zones, which controller is active, hat and trigger keys, releasing held input |
| `marmalade/s3eTouchpad.kt` | Virtual pad, stick-to-pad mapping, availability and HUD setting (JNI-bound) |
| `marmalade/LoaderActivity.kt` | Input routing and the button mapping (`xperiaKeyCode`, `onGameKey`) |
| `studio/IsDeviceActivity.kt` | HUD prompt at boot |
| `activision/boz/ControllerSettingsActivity.kt` | Settings screen opened from the launcher shortcut |

---

## Building

```
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # signed only if keystore.properties is present
```

Requirements: JDK 21 (Temurin), Android SDK platform and build-tools 36. The Gradle 8.11.1
wrapper downloads itself. `gradle.properties` is git-ignored, so create one with
`android.useAndroidX=true` (the CI workflow shows the full set).

### CI and releases

`.github/workflows/build.yml` builds the debug APK on every push to `master` and uploads it
as an artifact. Pushing a `v*` tag also publishes a GitHub release. To sign builds with a
stable key, set the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`
secrets. With a stable key, updates install over the previous build and keep your saves.
The workflow refuses to publish a release signed with the throwaway debug key.

## Game data

The large texture blobs (`blackops_atitc.dz`, `blackops_etc.dz`, `blackops_gles1.dz`)
are over GitHub's 100 MB limit and are not in the repo. The game downloads its texture
data into `Android/obb/com.activision.boz/` on first launch. It writes a `.tmp` file first,
then renames it.

### Storage on Android 11+

The engine writes to the raw `/sdcard/Android/obb/...` path, which scoped storage blocks.
If that write fails with `EACCES`, the engine shows a misleading "not enough storage" error.
The fix:

- `targetSdk = 35` in `app/build.gradle.kts`. At 36, these writes are blocked even with
  All-files access.
- `WRITE_EXTERNAL_STORAGE` and `MANAGE_EXTERNAL_STORAGE` are declared in the manifest.
- `IsDeviceActivity` asks for the right permission for the Android version (runtime grant
  on 23–29, All-files access on 30+) and creates the OBB folder before starting the engine.

---

## Inherited from the upstream port

### How the Kotlin sources were produced

The upstream port decompiled the original APK and its Marmalade JARs, then converted them
to Kotlin skeletons. `buildtools/syntex_to_kt.py` parses real smali (class, field and
method declarations) and writes Kotlin stubs, with the original smali body kept as a
comment under each method. Those comments were the reference used to fill in bodies and
fix ABI mismatches. Its output is in `buildtools/smali_out/` (4,967 files), kept as a
reference snapshot. The 105 files in `app/src/main/java/` are the hand-finished classes
the app actually builds from.

Upstream also described a `javap`-based pass and several audit scripts
(`decompile_jar.py`, `smali_to_kt.py`, `check_*.py`, `extract_7z.py`). **Those scripts
were never committed**, so that pipeline can't be re-run from this repo. The only tools
in `buildtools/` are:

| Tool | Purpose |
|---|---|
| `syntex_to_kt.py` | smali → Kotlin skeleton converter |
| `boz_s3e_tool.py` | Inspects and repacks the `boz.s3e` container (see `BOZ_S3E_ANALYSIS.md`, `boz_s3e_icf_analysis.md`) |
| `UnmakeLzma.py` | Decompresses s3e LZMA payloads |

### Kotlin ↔ JNI lessons

These are worth knowing before you edit any JNI-bound class:

- Java `int[]`/`short[]`/`byte[]` must be `IntArray`/`ShortArray`/`ByteArray`, never `Array<Int>`.
- Where smali shows a boxed `Ljava/lang/Integer;` field, keep it boxed. Changing it to `Int`
  changes the field's shape that the native code expects.
- `wait()`/`notifyAll()` aren't on Kotlin `Any`. Use `(obj as java.lang.Object).wait()`.
- Java allowed same-package calls to `protected` members, and Kotlin doesn't, so
  `LoaderActivity.onResume`/`onStop` were widened to `public`.
- Kotlin-defined `getX()` methods are not usable as properties, so call `x.getX()`.

Upstream took `compileDebugKotlin` from 283 errors to 0 using these fixes.

### Signature check

`libs3e_android.so` checks the signature of `.s3e` files, so an edited `boz.s3e` is
rejected at load time. Upstream patched out the check in its native libraries (for
`armeabi-v7a`, a `BLE` at offset `0x383b0` replaced with `MOVNE R0, #0`). The script that
applied the patch (`disable_sig_check.py`) was not committed.

### Google Play Services

Upstream replaced Google Play Services with no-op Kotlin stubs, because the native GPS
library crashed on stale JNI references at the end of a match. Sign-in, achievements and
leaderboards don't work. Co-op uses a private backend configured in `game_config.icf`.

---

## Repo layout

```
app/
  build.gradle.kts           armeabi-v7a only, targetSdk 35, compileSdk 36
  version.properties         VERSION_CODE (committed)
  src/main/
    java/                    the Kotlin shell (105 files)
    assets/                  boz.s3e, blackops_loader.dz, game_config.icf, music
    jniLibs/                 Marmalade native libs (only armeabi-v7a is packaged)
    res/                     resources, incl. controller_strings.xml and xml/shortcuts.xml
buildtools/                  syntex_to_kt.py, s3e tools, analysis notes, smali_out/ snapshot
.github/workflows/build.yml  CI build and tagged releases
HOWTO_BUILD.md
```

Some paths in `.gitignore` (both music folders, `buildtools/smali_out/`,
`buildtools/__pycache__/`) were committed before they were ignored, so they are
still in the repo.

## Credits

- **eugene373** for the initial Kotlin port this fork is based on.
- **Sam Richardson** for the rebuild pass, storage fixes, s3e tools, signature-check patch
  and the Google Play Services removal.
- **[12brendon34](https://github.com/12brendon34)** for the original decompiled Java source.
- Upstream donation address (Dogecoin): `DLXroiTaabPubUayDqhsHKsU7yTuegMDen`

*Call of Duty* and *Black Ops* are trademarks of Activision. This is an unofficial
preservation and research project.
