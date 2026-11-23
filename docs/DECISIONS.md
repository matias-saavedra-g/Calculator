# Design decisions

Each entry gives the decision, the alternatives that were considered, and why this one was picked.
Research was done on 2026-11-22. Sources are at the bottom.

## 1. CAS engine: SymPy on the phone (Chaquopy), not a Java CAS or a home-made one

| Option | Verdict |
|---|---|
| **SymPy 1.14 in embedded CPython 3.14 via Chaquopy 17.2** ✅ | Same engine as the Flow Launcher plugin, so the same grammar, the same answers and an already-tested parser. Full Risch / Meijer-G / Gosper / Gruntz implementations. Pure-Python wheels install cleanly. Chaquopy 17.2 officially supports AGP 9.5 and Python 3.10–3.15. |
| Symja / MathEclipse (Java, Mathematica-like syntax) | Native JVM, but its Android builds lag behind its desktop builds and it would mean rewriting the whole natural-language layer. Its Wolfram syntax was the attraction, so instead the engine got a Wolfram-Language translator (see SYNTAX.md). |
| Server / Wolfram Alpha API | Needs network and an API key, adds latency, and sends your queries off-device. Rejected: a calculator must work offline. |
| Write a CAS in Kotlin | Years of work to match SymPy's integration. Rejected. |

Cost: the APK is ~39 MB (CPython + SymPy). For a phone calculator in 2026 that's acceptable, and in exchange it is a real CAS.

**Python 3.14** matches the Python installed on this PC, which Chaquopy needs at build time for pip
and `.pyc` compilation, and the plugin's own bytecode (`cpython-314`). Chaquopy recommends ≥ 3.13
for 16 KB-page devices (Android 15+ hardware). **SymPy 1.14.0** is the latest release, and
**mpmath 1.3.0** is pinned because SymPy 1.14 requires `mpmath < 1.4`.

## 2. Engine in a separate process with a watchdog

The engine runs in the `:engine` process, reached through a bound `Service` + `Messenger`.
Python can't be interrupted from another thread, so killing the process is the only reliable
timeout (the Flow plugin uses a killable `worker.py` subprocess for the same reason). Limits:
**8 s** for the live preview and **45 s** after Enter. An engine crash or OOM can't take the UI down.
Requests are serialised: a newer preview cancels an older one that hasn't been sent yet. One that is
already computing finishes or hits its watchdog. Verified on the emulator with `100000000!`.

## 3. Preview vs. full result

The preview (debounced 250 ms) runs with `quick=true`. That skips the expensive extras of a bare
expression (roots, derivative, antiderivative, alternative simplifications), so typing stays fluid.
Enter runs the full analysis and stores it in history.

## 4. UI toolkit and design: Jetpack Compose + Material 3

* **Compose** is Google's recommended UI toolkit; there are no XML layouts. **Material 3 1.5.0**, whose
  Expressive components became stable in 1.5.
* **Dynamic colour** (Material You) on Android 12+, M3 baseline scheme below that, automatic dark theme.
* **"As simple as possible":** one screen, a small top app bar (title + history + overflow), the
  calculation "tape" (newest at the bottom, right next to where you type, like a terminal), one input
  panel, one accessory bar. Default M3 typography; results in `headlineSmall`, inputs in monospace so
  `x^2` and brackets read clearly.
* Components follow M3 guidance: *suggestion chips* for dynamically generated completions, a *filled
  icon button* for the primary action (↵), a *filled tonal icon button* for Tab, *modal bottom sheets*
  for search and help, *dropdown menus* for overflow and per-entry actions, *snackbars* for confirmations.
* **Edge-to-edge** (mandatory when targeting 35+) with proper inset handling. **Predictive back** is
  enabled. Android 13+ themed (monochrome) launcher icon.
* **State-based `BasicTextField`** (`TextFieldState`), the current API: the old value/`VisualTransformation`
  overload is deprecated in Compose 1.13. The ghost text is an `OutputTransformation` with a styled span.

## 5. Keyboard: the system keyboard, nothing custom

You asked for the keyboard you use everywhere, so the field is `KeyboardType.Text`, not a
calculator keypad or number pad. Autocorrect and capitalisation are off, and the "Go" action
evaluates. The calculator-specific helpers (Tab, completions, history, search) live in an accessory
bar that sits on top of the keyboard, the way terminal apps do it. Details: INPUT.md.

## 6. Shell-like memory: fish autosuggestions + nushell/fzf search

* fish: gray inline suggestions from history (Tab/→ accepts), ↑/↓ filtered by what you typed, de-duplication.
* fzf/Ctrl+R: a fuzzy search sheet with highlighted matches and a recency tie-break.
* Implemented in pure Kotlin (`input/Completions.kt`, `input/Fuzzy.kt`) with JVM unit tests.

## 7. History: JSON in app-private storage, exportable

* JSON (kotlinx.serialization) rather than Room/SQLite: you asked for exportable JSON, the data is
  small (capped at 10 000 entries), and one human-readable file is easy to back up, diff and import.
  The internal file and the export use the same format.
* `AtomicFile` for crash safety. Export/import via the Storage Access Framework, so **no storage permissions**.
* Included in Android Auto Backup / device transfer. The Python cache is excluded.
* Format spec: HISTORY_FORMAT.md.

## 8. Toolchain versions ("latest, Android 17-ready")

| Component | Version | Why |
|---|---|---|
| Gradle | **9.9.2** (wrapper, SHA-256 pinned) | Latest release (2026-11-19). AGP 9.5 needs ≥ 9.6.0 |
| Android Gradle Plugin | **9.5.2** | Latest stable; supports API 37 |
| Kotlin | **2.4.21** | Latest stable. AGP 9 **built-in Kotlin**, so no `org.jetbrains.kotlin.android` plugin. The Compose compiler plugin has the same version as Kotlin |
| JDK | **Temurin 21.0.13 LTS** | AGP 9.5 needs JDK 17+; 21 is the LTS Android Studio ships |
| compileSdk | **37.2** (Android 17 QPR) | Compose 1.13 requires compiling against ≥ 37.1 |
| targetSdk | **37** (Android 17) | Opts into Android 17 behaviour. Minor SDK levels can't be targeted |
| minSdk | **26** (Android 8.0) | Chaquopy needs 24+. 26 gives `java.time` without desugaring |
| Compose BOM / Material 3 | **2026.11.01 / 1.5.0** | Latest stable |
| Chaquopy | **17.2.0** | Latest. Adds AGP 9.5 support |
| ABI | **arm64-v8a** | Every current phone. Python ≥ 3.12 is 64-bit only. x86_64 can be added for emulators with `-Pcalc.abis=arm64-v8a,x86_64` |

## 9. Release build

R8 minification + resource shrinking. Signed with a dedicated RSA-4096 key (PKCS12, valid until
2054) whose secrets live in a git-ignored `.env`; environment variables override it for CI.
APK Signature Scheme v2 (minSdk 26 doesn't need v1). See BUILD.md.

## 10. Small extras that came almost free

* `ans`, live preview tap-to-copy, long-press menu (copy result / any row / input, delete).
* Hardware-keyboard shortcuts (Tab, Ctrl+R, ↑/↓, Enter), handy with scrcpy.
* "Calculator" in the text-selection menu of other apps (`ACTION_PROCESS_TEXT`).
* `sinx` → `sin(x)` phone shortcut (found while testing with real Gboard key taps).

## 11. Things deliberately left out

Custom keypad (against the brief), cloud sync, accounts, analytics, ads, network permission (none
requested), plots, units, step-by-step solutions. These are good candidates for later.

## Sources

* Gradle releases: https://docs.gradle.org/9.9.2/release-notes.html, https://services.gradle.org/versions/current
* AGP release notes / compatibility: https://developer.android.com/build/releases/gradle-plugin
* Built-in Kotlin (AGP 9): https://developer.android.com/build/migrate-to-built-in-kotlin
* Android 17 SDK setup: https://developer.android.com/about/versions/17/setup-sdk
* Compose BOM: https://developer.android.com/develop/ui/compose/bom/bom-mapping
* Material 3 for Compose releases: https://developer.android.com/jetpack/androidx/releases/compose-material3
* State-based text fields: https://developer.android.com/develop/ui/compose/text/migrate-state-based
* `InterceptPlatformTextInput`: https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/InterceptPlatformTextInput.composable
* Material 3 guidelines (dynamic colour, chips, sheets): https://m3.material.io/
* Chaquopy docs and changelog: https://chaquo.com/chaquopy/doc/current/android.html, https://chaquo.com/chaquopy/doc/current/changelog.html
* SymPy integration internals: https://docs.sympy.org/latest/modules/integrals/integrals.html
