# Shortcut System — Analysis & Upgrade

## 1. Analysis of the existing system

Four files made up the original implementation:

| File | Role |
|---|---|
| `ProfileShortcutHelper.java` | Created shortcuts across three API paths |
| `ShortcutActivity.java` | Trampoline activity that launched the game |
| `ShortcutIconPickerFragment.java` | Name + icon UI (profile / skin head / custom) |
| `ShortcutSkinHeadHelper.java` | Mojang skin head download with a 24 h cache |

Supporting resources: `dialog_add_shortcut.xml`, 16 `shortcut_*` strings, the
`menu_profile_shortcuts` id, and an `INSTALL_SHORTCUT` permission capped at
`maxSdkVersion="28"`.

### 1.1 The feature was unreachable

`HomeProfileAdapter` declared `onProfileAddShortcut(String, MinecraftProfile)` in
its `OnProfileActionListener` interface, and `RightPaneHomeFragment` implemented
it — correctly, routing into `ShortcutIconPickerFragment`. But the adapter never
called it. `onBindViewHolder` wired only three listeners: card click → edit,
`btn_profile_play` → play, `btn_profile_browse` → browse. There was no
long-press handler, no overflow button, no menu inflation.

`menu_profile_shortcuts` (in `ids.xml`) and `profile_menu_shortcuts` (in
`strings.xml`) were both defined and referenced nowhere. The evidence points to a
`PopupMenu` that was planned and never built — `grep -rn "PopupMenu"` across the
whole `java/` tree returns nothing.

Net effect: roughly 700 lines of working shortcut code that no user could reach.

### 1.2 Other defects found

| # | Issue | Impact |
|---|---|---|
| 1 | `Tools.read(url)` used for Mojang API calls | `Tools.read(String)` opens a `FileInputStream`. Passing a URL always threw `FileNotFoundException`, so **skin head icons could never work** |
| 2 | `500ms postDelayed` launch signal | Race with `LauncherActivity` init. Cold start: listener not yet registered, tap silently did nothing. Warm start: pure added lag |
| 3 | Unconditional success toast | "Shortcut created!" fired on request, not on pin. Dismissing the system dialog still showed success |
| 4 | No `isRequestPinShortcutSupported()` check | Launchers that reject pinning failed silently |
| 5 | `createShortcutN` / `createShortcutO` byte-identical | ~45 lines duplicated, including the same reflection block |
| 6 | Reflection for the one-arg `requestPinShortcut` | Fragile workaround for an SDK-visibility quirk |
| 7 | No adaptive icon support | Square, unmasked icons look broken on Android 8+ |
| 8 | Bilinear scaling of 8×8 skin heads | Pixel art rendered blurry |
| 9 | Hat/helmet overlay layer discarded | Skins with hats lost them |
| 10 | No timeouts on skin PNG download | A stalled server blocked the worker thread indefinitely |
| 11 | Single action only | Every shortcut did exactly one thing: launch |
| 12 | Shortcuts survived profile deletion | Tapping one landed on a dead reference |
| 13 | No `onNewIntent` | Shortcuts behaved inconsistently when the launcher was already running |
| 14 | Opaque `AppTheme` on the trampoline | Black flash between icon tap and launcher |
| 15 | No way to review created shortcuts | Android cannot enumerate pinned shortcuts; without a registry there was no manage screen |

---

## 2. What was implemented

### 2.1 Entry points (the blocking fix)

- **Long-press a profile card** → opens the shortcut editor, with a haptic tick
- **Dedicated shortcut button** in the card's action row, for discoverability

### 2.2 Multi-action shortcuts — `ShortcutType`

| Action | Behaviour |
|---|---|
| `LAUNCH` | Select profile, start Minecraft |
| `OPEN_PROFILE` | Open launcher with the profile selected |
| `MODS` | Jump into the mod browser scoped to the profile |
| `EDIT` | Open the profile editor |
| `FOLDER` | Open the game directory in a file manager (no launcher UI at all) |

One shortcut per profile per action; the editor marks actions that already exist.

### 2.3 Icon rendering — `ShortcutIconRenderer`

- Four shapes: squircle (cubic superellipse), circle, rounded square, square
- Adaptive-icon safe zone (72% content scale) so launcher masks cannot crop artwork
- Action badge — corner glyph with a dark separator ring
- Aspect-preserving centre crop, so non-square artwork is not squashed
- Nearest-neighbour upscaling for pixel art
- Four icon sources: profile icon, skin head, **mod-loader glyph** (new), custom image

### 2.4 Correctness

- `ShortcutManagerCompat` replaces all hand-rolled API branching and the reflection hack
- Routing moved from a timed guess to intent extras, consumed in `onResume()` once
  profiles are loaded; extras are removed on read so rotation cannot replay a launch
- `ShortcutPinReceiver` reports success only on a genuine pin
- `onNewIntent()` added for the already-running case
- Translucent trampoline theme removes the black flash
- Skin heads fixed (`DownloadUtils.downloadString`), given timeouts, hat-layer
  compositing, and crisp upscaling
- Profile deletion revokes the profile's shortcuts

### 2.5 New surfaces

- **`ShortcutRegistry`** — JSON-backed record of every created shortcut, with usage
  counts, last-used timestamps and orphan pruning
- **`ShortcutManagerFragment`** — lists shortcuts sorted by usage, flags orphans,
  supports single and bulk removal
- **Dynamic shortcuts** — the app-icon long-press menu is populated with the four
  most recently played profiles, refreshed on every launcher resume

---

## 3. Files

**New (17):** `ShortcutType`, `ShortcutRecord`, `ShortcutRegistry`,
`ShortcutIconRenderer`, `ShortcutRouter`, `ShortcutPinReceiver`,
`ShortcutPreferences`, `ShortcutActionAdapter`, `ShortcutManagerFragment`,
plus 3 layouts and 5 drawables.

**Modified (9):** `ProfileShortcutHelper`, `ShortcutActivity`,
`ShortcutIconPickerFragment`, `ShortcutSkinHeadHelper`, `HomeProfileAdapter`,
`LauncherActivity`, `ProfileEditorFragment`, `AndroidManifest.xml`,
`build.gradle`, `strings.xml`, `styles.xml`, `item_home_profile_card.xml`.

**Dependency:** `androidx.core:core:1.13.1` (was commented out) for
`ShortcutManagerCompat` / `IconCompat`.

---

## 4. Verification performed

| Check | Result |
|---|---|
| Java parse (all 13 shortcut files + 3 touched files) | pass |
| XML well-formedness (436 files) | pass |
| `R.*` references in new Java resolve | pass |
| Resource references in new layouts resolve | pass |
| `R.id.*` present in layouts | pass |
| Plural/format argument counts match call sites | pass |

**Not verified:** no JDK or Android SDK is available in this environment, so
`./gradlew assembleDebug` was not run. Compilation and on-device behaviour still
need a real build — the CI workflow on this branch will cover the former.
