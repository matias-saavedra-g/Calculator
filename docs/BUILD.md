# Building

## What was installed on this PC (2026-11-22)

All installed per-user, so no admin rights were needed. Downloads were checked against their
published checksums.

| Tool | Location |
|---|---|
| JDK Temurin 21.0.13 LTS | `C:\Users\mattia\.jdks\jdk-21.0.13.1+2` |
| Android SDK (cmdline-tools 23, platform-tools 37.0.3, build-tools 37.0.0, platforms 37.0 and 37.2) | `C:\Users\mattia\AppData\Local\Android\Sdk` |
| Android Emulator 37.3 + system image `android-37.0/google_apis/x86_64`, AVD `calc37` (Pixel 8) | same SDK |
| Gradle 9.9.2 (standalone, only used to generate the wrapper) | `C:\Users\mattia\.local\opt\gradle-9.9.2` |
| Python 3.14 venv with sympy 1.14.0 for engine tests | session scratch dir (recreate with the command below) |

User environment variables set: `JAVA_HOME` = the JDK above (it pointed to a JRE 8 folder that no
longer existed), `ANDROID_HOME` = the SDK. `local.properties` also points to the SDK.
The build uses the **Gradle wrapper**, which downloads its own Gradle 9.9.2 with a pinned SHA-256.

## Build commands (Nushell)

```nu
cd ~/Documents/Projects/Calculator
./gradlew assembleRelease            # signed release APK  -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug              # debug APK (package com.mattia.calculator.debug, installs alongside)
./gradlew testDebugUnitTest          # Kotlin unit tests
./gradlew assembleDebug -Pcalc.abis=arm64-v8a,x86_64   # also runs on the x86_64 emulator
^adb install -r app/build/outputs/apk/release/app-release.apk
```

The first build downloads Gradle, AGP, CPython for Android and SymPy (a few minutes). Later builds
take ~30–90 s. Chaquopy needs **Python 3.14 on the build machine** (found via `py -3.14`) to run pip and
compile `.pyc`.

Engine tests:

```nu
py -3.14 -m venv .venv; .venv/Scripts/python -m pip install sympy==1.14.0 mpmath==1.3.0
.venv/Scripts/python engine-tests/test_engine.py -v
```

## Signing

* Keystore: `keystore/calculator-release.jks`. PKCS12, alias `calculator`, RSA 4096, SHA384withRSA,
  `CN=Calculator`, valid until 2054-04-09.
  Certificate SHA-256: `73:98:14:3A:9A:00:34:BA:9D:1C:40:9E:21:40:02:3E:C0:2F:2C:8F:66:9D:FB:8F:B8:B3:DA:02:7F:E1:60:7C`.
* Secrets: `.env` in the project root (random 43-character password). PKCS12 has a single password,
  so `CALC_KEY_PASSWORD` = `CALC_KEYSTORE_PASSWORD`:

  ```text
  CALC_KEYSTORE_PATH=keystore/calculator-release.jks
  CALC_KEYSTORE_PASSWORD=…
  CALC_KEY_ALIAS=calculator
  CALC_KEY_PASSWORD=…
  ```

* `app/build.gradle.kts` reads `.env`, and real environment variables override it (for CI). Without a
  keystore the release build is produced unsigned instead of failing.
* `.env`, `*.jks` and `keystore/` are in `.gitignore`. A template is in `.env.example`.
* ⚠️ **Back up `.env` and the `.jks` together** (e.g. in your password manager). If they are lost,
  no future update can be installed over the existing app; you'd have to uninstall it, which loses
  the history unless exported.

## Known build quirks (and the fixes in place)

1. **Compose 1.13 needs compileSdk ≥ 37.1.** AGP 9 DSL `compileSdk { version = release(37) { minorApiLevel = 2 } }`.
   targetSdk stays 37.
2. **Chaquopy's R8 rules vs. `clean` + configuration cache.** Chaquopy 17.2 writes
   `build/python/proguard-rules.pro` while the plugin is *applied*, not in a task. After `clean`, a
   configuration-cache hit skips that and R8 fails with "Supplied proguard configuration does not
   exist". The `restoreChaquopyProguardRules` task (runs before `preBuild`, after `clean`) re-extracts
   it from the Chaquopy jar. Verified: `clean assembleRelease` in one invocation and in separate ones.
3. Some Gboard versions ignore `TYPE_TEXT_FLAG_NO_SUGGESTIONS` (see INPUT.md). This is a keyboard
   behaviour, not a build problem.

## How it was verified

* `engine-tests/test_engine.py`: 122/122 pass, plus all 48 examples shown in the app.
* Kotlin unit tests: 16/16 pass.
* Android 17 emulator (API 37, x86_64 build of the same code), **debug and minified release**: launch,
  engine start, live preview, Enter, history persistence (`files/history.json`), ghost suggestion + Tab,
  fuzzy search, `ans`, Wolfram syntax, and the watchdog killing and restarting the engine.
* Release APK: `apksigner verify` (v2 scheme, CN=Calculator), `aapt2 dump badging`
  (`com.mattia.calculator`, versionName 1.0.0, targetSdk 37, arm64-v8a).

## Changing things

* **Package name / app id**: `namespace` and `applicationId` in `app/build.gradle.kts` (plus the Kotlin
  package folders if you want them to match).
* **Version**: `versionCode` / `versionName` in `app/build.gradle.kts`.
* **Time limits**: `PREVIEW_TIMEOUT_MS` / `SUBMIT_TIMEOUT_MS` in `CalculatorViewModel.kt`.
* **Engine grammar**: `app/src/main/python/calc_engine.py`. Add a test in `engine-tests/test_engine.py`.
