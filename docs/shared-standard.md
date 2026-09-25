# The shared app standard

The Android apps in this account — among them a fuel price comparator, a music player,
a weather app, a name-day calendar, a security camera, a stock ticker, a
Wake-on-LAN remote and a training log — are built to one written standard.
They do entirely different things. **They differ only in content and colour;
the shell is the same**, so that anyone who has used one can find their way
around the next.

This file is the English condensation of that standard, included in each repo
because the code comments refer to it by chapter number. It is descriptive of
what the apps actually do, not a wish list.

---

## 2.1 Versions and tools

Kotlin, `minSdk 26`, `compileSdk`/`targetSdk` 35, JDK 17, Gradle wrapper
committed. New screens are Jetpack Compose; some older apps are still Views,
and both shapes are expected to look identical to the user.

## 2.2 Credentials in code

Every app reads NAS credentials at **build time** from a single file shared by
all of them, and bakes them into `BuildConfig` so nothing has to be typed on
the phone after installing:

```kotlin
fun nasSetting(key: String): String {
    val f = file(System.getenv("NAS_CREDENTIALS") ?: "nas-credentials.local")
    if (!f.exists()) return ""
    ...
}
```

Three rules make this survive contact with reality:

* A value set in the app's own settings **wins** over the compiled-in one —
  that is the path for changing a password without a rebuild.
* A missing file is not an error. The fields stay empty and the app asks for
  them in settings, exactly as it did before. **The build never fails over it.**
* The password therefore ends up inside the APK. That is a deliberate trade:
  the APKs sit on the same NAS behind the same account, so anyone who can read
  an APK already has the account. It is a dedicated account scoped to one
  folder, never an admin one.

## 2.4 Self-update — the required sequence

There is no Play Store in this setup. Every app updates itself from the NAS,
in this order:

1. **On startup**, a silent check — and only over an unmetered connection.
2. The check reads **only `output-metadata.json`** (a few hundred bytes) and
   takes `elements[0].versionCode` from it. **The APK is never downloaded to
   answer the question "is there a new version".**
3. If the remote version is higher, **ask**: *"Version X is on the NAS (you
   have Y). Install now?"*
4. Only after confirmation: download the APK, verify its `versionCode` by
   reading the file itself (`getPackageArchiveInfo`), hand it to the system
   installer through a `FileProvider`.
5. **An unreachable NAS is silence.** Away from home, an app has no business
   complaining about a server the user did not ask it to contact.

Automatic installation is deliberately not done: an app is opened at the moment
someone needs something from it, and a restart in the middle of that is worse
than waiting.

## 2.6 The shell that must look the same

The first row of every main screen, left to right:

```
App name                          [custom action]  ↻   ⚙
```

| Element | Rule |
|---|---|
| Title | 20sp bold, takes the remaining width |
| Subtitle | optional, 12sp muted — state, filter, what is being shown |
| Custom action | only if the app has one, as text, left of the icons |
| Refresh | 44×44dp icon |
| Settings | 44×44dp gear, **always the rightmost element** |

Settings is a **gear icon**, never a text link and never a button in the
content. Until this was written down, the apps had solved it in five different
ways: a gear in one, a `SETTINGS` text button in another, a link below the
list in two, a big button in the content in two more, and an item hidden
under `⋮` in two others. Five solutions
to one problem, and no way to guess where to look.

The icon files themselves (`ic_obnovit`, `ic_nastavenia`, `ic_lupa`,
`ic_zrusit`, `ic_pridat`) are **byte-for-byte identical across all apps**,
24dp, tinted through `@color/ikona` — same name everywhere, value per app.

**Pull down to refresh** wraps the main content in every app, doing exactly
what the ↻ icon does. Apps with nothing to download have the gesture too and
make it mean whatever "current" means for them: the calendar redraws today,
the Wake-on-LAN app re-pings the machines, the camera app re-checks its
permissions. A gesture that works everywhere except in one app is worse than
no gesture.

## 2.7 Fixed APK name

The build renames the output from the generic `app-debug.apk` to
`<app>.apk`, so publishing is a plain overwrite of one file on the NAS and the
version is never read from a filename.

The music player is the documented exception: its filename carries the version
and it is driven by a `latest.json` including a SHA-256 the app verifies before
installing.

## 2.8 The settings screen

One shape for all apps: fields grouped in sections, each with a sentence saying
what it does, passwords with a reveal toggle, and the app version in a single
footer at the bottom — one place, never repeated elsewhere on the screen.

## 2.8b Transferred-data counter

Apps that download anything count how much, split into **Wi-Fi and mobile**,
and show it in settings. It exists because "does this app eat my data plan?"
should be answerable by the app itself rather than by the system settings three
menus deep.

## 2.9 Night mode and font size

Dark mode and a font-size scale (−6 to +6 steps) are shared code, not
per-app improvisation. The palette names are identical everywhere; only the
values differ.

## 2.10 Home-screen widgets — two traps that kill the whole widget

Both of these were latent in two apps at once, because the widgets are copies
of one scheme, and both only surfaced when a widget was actually placed on a
home screen.

**1. Through `RemoteViews` you may only call a "remotable" method.**
`v.setInt(R.id.grid, "setNumColumns", 2)` compiles and runs, but the launcher
rejects it and the result is not a cropped widget — it is **no widget at all**,
replaced by "Problem loading widget". `GridView.setNumColumns` became remotable
only in Android 12. Column counts therefore belong in the layout; when two
variants are needed, there are two layouts and the provider picks one.

**2. A collection's `PendingIntent` template must be `FLAG_MUTABLE`.**
With `FLAG_IMMUTABLE`, `setPendingIntentTemplate` discards the per-row fill-in
intent, so the launched screen gets empty extras and closes immediately. From
the outside it looks like **tapping a widget row does nothing**:

```kotlin
private val TEMPLATE_FLAGS: Int
    get() = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
```

The widget's other buttons stay immutable — nothing is filled into them, and
an immutable `PendingIntent` is the safer default.

**And one note beyond the traps:** a custom `View` cannot be sent to a widget,
so a chart has to travel as a `Bitmap` (`setImageViewBitmap`). The drawing
routine must be **shared between app and widget**, or the two drift apart at
the first change. Those bitmaps cross into the launcher process, where a memory
cap applies — hence `RGB_565` and a sane width ceiling, so that four rows do
not become three megabytes.
