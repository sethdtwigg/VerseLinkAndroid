# VerseLink for Android

[![CI](https://github.com/sethdtwigg/VerseLinkAndroid/actions/workflows/ci.yml/badge.svg)](https://github.com/sethdtwigg/VerseLinkAndroid/actions/workflows/ci.yml)

Select a Bible reference like `John 3:16` in any app, tap **VerseLink** in the
text-selection menu, and the reference is replaced in place with the full verse
text — the Android companion to [VerseLinkWindows](https://github.com/sethdtwigg/VerseLinkWindows),
sharing the same parser behaviour and the same Bible XML data files.

## Features

- **Selection-menu action**: "VerseLink" appears in the standard text-selection
  menu whenever the selection parses as a Bible reference.
- **In-place replacement**: the platform swaps your selected text for the verse
  text — no copy/paste, no clipboard touching.
- **Same parsing as Windows**: all nine reference patterns are ported
  one-for-one (single verses, ranges incl. en/em dashes, cross-chapter ranges,
  comma lists, chapter/book ranges, ~200 book aliases) - plus multi-word book
  names such as `Song of Solomon 2:1` and `1st John 2:1`, which the upstream
  single-word book token could not handle.
- **Same data format**: drop `KJV.xml`, `NASB.xml`, `ESV.xml` etc. straight in;
  KJV (public domain) is bundled. Import and remove others from the Settings
  screen; an import is validated before it replaces anything.
- **Formatting options** mirroring Windows config.json: include reference,
  reference on first line, dynamic reference, verse numbers, new lines between
  chapters/books - with a live preview in Settings showing exactly what the
  current combination produces.
- **Clipboard**: where the platform cannot write the selection back
  (non-editable views), the resolved verse goes to the clipboard instead. For
  editable fields there is an optional "also copy verse to clipboard" setting -
  PROCESS_TEXT gives no signal about whether an editor applied the result, so a
  few browsers/webviews drop it silently and this leaves the verse pasteable.
- **Optional VerseLink keyboard (IME)**: minimal keyboard that inserts a
  resolved verse at the cursor and hands control back to your normal keyboard.
  Useful in apps whose editors ignore PROCESS_TEXT results (some webviews).

## Building

1. Open the project root in Android Studio, or from a shell run
   `./gradlew :app:assembleDebug` (`gradlew.bat` on Windows). A JDK 17+ must be
   on `JAVA_HOME`; nothing machine-specific is pinned in `gradle.properties`.
2. Install the debug APK on a device/emulator running Android 8.0+ (API 26).
3. Unit tests: `./gradlew :app:testDebugUnitTest`.

### Release build (ready-to-install APK)

Run `./gradlew :app:assembleRelease` (or Android Studio → Build → Generate Signed
App Bundles/APKs). Output:

```
dist/VerseLink-<version>.apk      (also under app/build/outputs/apk/release/)
```

The release is signed automatically using `keystore.properties` +
`keystore/verselink-release.jks` (both gitignored - keep a backup of these two,
updates must be signed with the same key). If those files are missing on another
machine, Gradle falls back to the debug key so a build always succeeds.

### Installing on a phone (no adb, no developer options)

1. Copy `dist/VerseLink-1.0.4.apk` to the phone (USB, Drive, email...).
2. Tap it; accept the "install unknown apps" prompt when asked.
3. Done. The selection-menu action works immediately with zero setup.
   Optional extras afterwards:
   - Enable the VerseLink keyboard: Settings → System → Keyboards →
     On-screen keyboard → VerseLink (normal toggle, no permissions).
   - Silent auto-switching of keyboards (skips the picker): one-time
     `adb shell pm grant com.verselink.android android.permission.WRITE_SECURE_SETTINGS`
     from a computer - purely optional.

## Using VerseLink

### The selection-menu action (primary path)

1. Select text that looks like a reference, e.g. `John 3:16`, `jn 3:16–17`,
   `Romans 8:28-9:1`, `John 3:16,18,20`.
2. Tap the **overflow arrow** (⫶) in the selection toolbar if "VerseLink" is
   not immediately visible.
3. Tap **VerseLink** → the selection becomes e.g.
   `John 3:16 For God so loved the world, that he gave his only begotten Son…`

Works in messaging apps, Notes/Keep, email, browsers, most edit fields —
anywhere Android offers the standard selection actions.

### The VerseLink keyboard (optional secondary path)

1. Open VerseLink → **Enable VerseLink keyboard** → switch it on in system
   settings.
2. Anywhere you can type, open the keyboard picker and choose **VerseLink**
   once; it shows an idle hint and stays out of the way.
3. To feed it a verse without typing: select/share text to **VerseLink** in the
   share sheet — it resolves the verse and activates the keyboard, which shows
   a preview and an **Insert verse** button that replaces the current selection
   or inserts at the cursor, then switches back to your previous keyboard.

### Automatic keyboard switching (optional, one-time adb step)

Android normally forbids apps from switching keyboards silently. If you accept
the trade-off, grant the secure-settings permission once from a computer:

```
adb shell pm grant com.verselink.android android.permission.WRITE_SECURE_SETTINGS
```

With this granted, sharing a reference to VerseLink will activate the VerseLink
keyboard automatically **and restore your previous keyboard after insertion**,
with no picker interaction. Without it, VerseLink opens the system keyboard
picker instead and you tap manually.

## Manual test checklist

Parser, lookup and formatting coverage is unit-tested (45 cases across
`ReferenceParserTest.kt` and `BibleXmlParserIntegrationTest.kt`, the latter
parsing the real bundled KJV). On-device checks:

| # | Scenario | Steps | Expected |
|---|----------|-------|----------|
| 1 | Single verse | Select `John 3:16` in Messages → ⫶ → VerseLink | Replaced with verse + label |
| 2 | Range w/ dash variants | `John 3:16-18`, `Jn 3:16–18`, `John 3:16 - 17` | Both/three verses joined by spaces |
| 3 | Cross-chapter range | `Romans 8:28-9:1` | Tail of ch.8 + head of ch.9 |
| 4 | Comma list | `John 3:16,18,20` | Three verses joined |
| 5 | Multiple references | `John 3:16; Romans 8:28` | Both replaced sequentially |
| 6 | Non-reference | Select `Call me at 3:16 pm` | No VerseLink entry in menu |
| 7 | Empty/no selection | Long-press without dragging | No VerseLink entry |
| 8 | Unknown book | `Foo 3:16` via share sheet | Toast: "No verse found" |
| 9 | Webview field | Select ref in Chrome address/textarea | Menu may appear; verify replacement applied |
| 10 | Keyboard flow | Share ref → pick VerseLink keyboard → Insert | Verse inserted at cursor, Gboard restored |
| 11 | Formatting toggles | Toggle verse numbers / first-line reference in Settings | Output matches flags |
| 12 | Translation switch | Settings → choose/import another XML → resolve ref | Verses come from chosen translation, no restart needed |
| 13 | Chapter/book labels | Select `Psalm 23`, `John 1-2`, `Genesis - Exodus` | Label has no invented `:1` |
| 14 | Read-only source | Select a ref in a non-editable view → VerseLink | Verse copied to clipboard |
| 15 | Bad import | Import a non-Bible XML | "Import failed"; previous translation still works |
| 16 | Delete translation | Settings -> Delete imported translation | Entry gone; bundled KJV still selectable |
| 17 | Formatting preview | Toggle any formatting flag in Settings | Preview updates to match |
| 18 | Multi-reference | Select `John 3:16; Romans 8:28` | Each verse carries its own label |
| 19 | Also-copy off (default) | Replace a ref, then paste elsewhere | Clipboard unchanged |
| 20 | Also-copy on | Settings -> Clipboard -> enable, replace a ref, paste | Verse both replaced and pasteable |

## Limitations & platform notes

- **TextClassifierService**: the original design called for a
  `android.service.textclassifier.TextClassifierService`. This is not viable in
  a third-party APK: the class has been removed from the public Android SDK
  (it's absent from android.jar for API 30–35), and the OS resolves the system
  text classifier with `MATCH_SYSTEM_ONLY` — only preinstalled/OEM packages can
  ever hold that role. VerseLink therefore uses `ACTION_PROCESS_TEXT`, which is
  the supported way to add a custom action to the selection menu (same
  mechanism as Google Translate's "Translate" entry). Consequence: the action
  lives behind the overflow arrow rather than as a first-class chip, and a few
  non-standard editors don't show it at all.
- **Keyboard switching**: no public API exists to read "the previous keyboard".
  With the optional adb-granted permission VerseLink snapshots and restores
  `Settings.Secure.DEFAULT_INPUT_METHOD`. Without it you get the system picker.
- **PROCESS_TEXT result honoured per-app**: standard `TextView`/`EditText`
  replace the selection automatically; some webviews/custom editors ignore the
  returned text — that's what the IME path covers.
- **First-use latency**: resolving the very first reference parses the full
  Bible XML (~4.7 MB KJV, 1–2 s); results are cached in memory per process.
- **API levels**: minSdk 26. `getDefaultTextClassifierImplementation()` /
  modern TextLinks paths are API-28-guarded. Multi-window and foldables behave
  like any other IME.
- **Translations**: only KJV (public domain) ships with the app. Importing a
  copyrighted translation (NASB/ESV) is supported technically; respect the
  respective licence terms.

## Project layout

```
app/src/main/java/com/verselink/android/
├── engine/            BibleEngine, ReferenceParser, BookData,
│                      AssetBibleRepository, FormatterOptions  (pure Kotlin)
├── handoff/           PendingReplacementStore (classifier→IME hand-off)
├── ime/               VerseLinkImeService (minimal replace-and-return IME)
├── util/              KeyboardSwitcher (activate/restore helpers)
├── ProcessTextActivity    selection-menu action (primary)
├── VerseLinkActionActivity  share-sheet trampoline feeding the IME pipeline
├── MainActivity           onboarding/status
└── SettingsActivity       translations, formatting, permissions status
```

## License / attribution

App code provided as-is for educational and personal use. KJV text is public
domain. Windows counterpart: https://github.com/sethdtwigg/VerseLinkWindows
