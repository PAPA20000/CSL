# CS LAUNCHER V3 — PHASE 4
## Full Architecture Analysis + Implementation Plan
### (Play Launch Animation · Advanced Cursor System · Cursor Studio v2 · Rule Editor · World Manager Fix · Settings Redesign)

**Date:** 2026-08-04 · **Repo:** `PAPA20000/CSL` (workspace `/home/user/CSL`)
**Reference studied:** `ZalithLauncher/ZalithLauncher2` (workspace `/home/user/ZalithLauncher2`)
**Status:** Analysis complete — awaiting approval before coding.

---

# PART A — CS LAUNCHER V3 ARCHITECTURE (AS-IS)

## A1. Module layout

```
settings.gradle
├── :app_pojavlauncher          → THE launcher app module (all code lives here)
├── :jre_lwjgl3glfw             → patched LWJGL 3.3.3 / 3.4.1 / 3.4.2 (java modules)
│     └── lwjgl-3.x/src/main/java/org/lwjgl/glfw/  GLFW.java + CallbackBridge.java (stubs)
├── :arc_dns_injector, :methods_injector_agent, :forge_installer
```

- `app/` is **not** a real module (only one stray drawable). Everything is in `app_pojavlauncher`.
- Game side: `org.libsdl.app.*` (SDL3 passthrough), `org.lwjgl.glfw.CallbackBridge` (JVM→Android bridge).
- Native: `app_pojavlauncher/src/main/jni/input_bridge_v3.c` (glfwSetCursorPos etc.).

## A2. Navigation & Home flow

```
LauncherActivity (activity_pojav_launcher / activity_basemain)
 └─ fragment container hosts MainMenuFragment (fragment_launcher.xml)
     ├─ landscape/two-pane: left rail + right_pane_container
     │    └─ RightPaneHomeFragment (fragment_right_pane_home.xml)
     │         └─ RecyclerView + HomeProfileAdapter + item_home_profile_card.xml
     └─ portrait: FastClientHomeFragment (fragment_home_fastclient.xml)
          └─ profile card + PremiumPlayButtonView (btn_play_main) + NestedScrollView
```

- `Tools.swapFragment()` / `MainMenuFragment.openChildPane()` are the navigation primitives.
- Bottom bar: home / news / discord / custom controls / cursor studio / settings etc.

## A3. Play flow (deep)

**Path:** `PremiumPlayButtonView.beginLaunch()` → `ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true)`
→ `LauncherActivity.mLaunchGameListener` (LauncherActivity.java:180):
1. rejects if `ProgressKeeper.hasProcesses()`
2. validates selected profile + `lastVersionId`
3. requires a signed-in account
4. `AsyncMinecraftDownloader.downloadMinecraft(...)` → downloads version/`assets`/libs if missing (download UI = `ProgressLayout` + `KineticProgressView` + `ProgressKeeper` tasks — **this is the "download animation" the user says is perfect; DO NOT touch**)
5. JREUtils builds Java args → `GameService` (foreground service) → `MainActivity` renders `MinecraftGLSurface` → `screen_opening_game.xml` boot overlay ("Opening Game…").

**Key finding (user's complaint):** `PremiumPlayButtonView` mirrors `ProgressKeeper.getTaskCount()`:
- When launching an **already-installed** game, download tasks still exist briefly (version list refresh, auth, etc.), so the button shows the **same wave/glow animation** as downloading → "it still feels like another download animation."
- There is **no "installed vs not-installed" branch** in the button logic.

`PremiumPlayButtonView` (app_pojavlauncher/.../ui/PremiumPlayButtonView.java, 325 lines) already has:
- Idle platinum capsule + diagonal sheen (ValueAnimator, zero per-frame allocs)
- `beginLaunch()` morph: scale pulse + particles + violet glow + **horizontal wave** (looks progress-like → "download feeling")
- Task-count failsafe reset.

Used in 2 places: `fragment_home_fastclient.xml` `@id/btn_play_main` (36dp+ big) and `item_home_profile_card.xml` `@id/btn_profile_play` (36dp small).

## A4. Current cursor system

Files (all in `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/`):

| File | Role |
|---|---|
| `customcontrols/mouse/CursorManager.java` | glow FX, load/save cursor PNGs to `Tools.DIR_CURSORS` |
| `customcontrols/mouse/CustomCursorRenderer.java` | **single global cursor**: decodes PNG/GIF (`Movie`), applies glow/scale/opacity/hotspot, builds `android.view.PointerIcon`, sets it on the GL surface view; 60fps GIF loop via `Tools.MAIN_HANDLER` |
| `customcontrols/mouse/CursorDesignerView.java` | 32×32 pixel-art editor (pencil/eraser/fill + undo/redo + pan/zoom) |
| `customcontrols/mouse/Touchpad.java` | virtual mouse overlay; `onDraw()` draws `CustomCursorRenderer.getCurrentFrameBitmap()` at mouse pos (hotspot offset); listens to `ExtraConstants.REFRESH_CURSOR` |
| `customcontrols/mouse/InGUIEventProcessor.java` | touch → GLFW mouse (drag = LMB, tap = click) |
| `customcontrols/mouse/AndroidPointerCapture.java`, `HotbarView.java`, `GyroControl.java` | hardware pointer capture / hotbar / gyro |
| `MinecraftGLSurface.java` | `onTouchEvent`, `dispatchGenericMotionEvent` (mouse/stylus → CallbackBridge), `updateGrabState` starts/stops cursor animation |
| `org/lwjgl/glfw/CallbackBridge.java` | `glfwSetCursor(w, c)` → `CustomCursorRenderer.updateCursorFrame()` (same cursor, no shape) |
| `fragments/CursorCustomizationFragment.java` | **Cursor Studio UI** (1137 lines): preview, import PNG, packs, styles (classic/gamepad/custom), glow color rings, scale/opacity/glow seekbars, save → prefs `custom_cursor_*` |
| `prefs/LauncherPreferences.java` | single global cursor prefs: `custom_cursor_path/enabled/glow_radius/glow_color/scale/opacity/hotspot_x/hotspot_y` |

**Limitations (what Phase 4 must fix):**
1. **One cursor for everything** — no Arrow / Hand / IBeam / Loading / Forbidden / Move / Resize / Help states.
2. **Game cursor shape never reaches UI.** In all three LWJGL stubs (`jre_lwjgl3glfw/lwjgl-3.3.3|3.4.1|3.4.2/.../GLFW.java`):
   - `glfwCreateStandardCursor(int shape)` → **returns constant `4L`** (shape discarded!)
   - `glfwSetCursor(w, c)` → `CallbackBridge.glfwSetCursor(w, c)` (no shape)
   Minecraft's own GLFW cursor changes (button→hand, text field→IBeam, resize, forbidden…) are invisible.
3. **No rules engine** — launcher-side hover (Button→Hand, EditText→IBeam…) does not exist.
4. Editor lacks: rotation, shadow, border, color tint, per-state config, animated preview grid.

## A5. Pointer events & input pipeline (summary)

```
Hardware mouse/stylus ──► MinecraftGLSurface.dispatchGenericMotionEvent
                              ├─ ACTION_HOVER/MOVE → CallbackBridge.mouseX/Y + sendCursorPos
                              ├─ ACTION_SCROLL → sendScroll
                              └─ BUTTON_PRESS/RELEASE → sendMouseButton
Touch ──► InGUIEventProcessor / TouchControllerUtils ──► CallbackBridge (GLFW events)
Game (JVM) ──► CallbackBridge.native* ──► JNI input_bridge_v3.c ──► LWJGL callbacks in game
```
- Grab states: `CallbackBridge.isGrabbing` + `GrabListener`s (Touchpad hides when grabbed).
- Gamepad: `Gamepad`, `GamepadMap`, SDL passthrough.

## A6. Settings system

- `prefs/screens/LauncherPreferenceFragment.java` (**1737 lines**) — the real Settings page.
- Layout `fragment_custom_settings.xml` — Phase 2 redesign: glass header (`bg_csp_header`), back button, live badge, **global search box**, category pill rail, `RecyclerView` of category cards, floating "Unsaved changes" save bar.
- `SettingsAdapter` (inner class) binds `SettingCategory`/`SettingItem` model (from `SettingsMetaStore`) into:
  - `item_settings_category.xml` (card) → dashboard header + dynamic rows:
  - `item_setting_toggle`, `item_setting_slider`, `item_setting_dropdown`, `item_setting_button`, `item_setting_theme_selector`, `fragment_settings_fastclient`, `item_settings_nav_chip`, `item_settings_dashboard_stat`, `item_settings_pin_card`
- Draft-based editing: `initializeDraft` → `mDraftPrefs` → save bar → `saveChanges()` (writes + `LauncherPreferences.reload()` + `notifyHomeFragmentBgChanged`).
- `SettingsSaveManager` (migration/legacy), `QuickSettingSideDialog`, `CustomSeekBarPreference`, `CustomToggleView`, `FastClientPreference`.

**User request:** full redesign with brand-new layouts/drawables/cards/nav/animations — NOT a recolor. Current look is already "premium glass", but user wants a completely different visual system.

## A7. World Manager

- `worlds/WorldManagerFragment.java` (565 lines) + `worlds/WorldListAdapter.java` (318 lines) + `worlds/WorldOps.java` + `worlds/WorldEntry.java` + `worlds/NbtIO.java`.
- Layout: `fragment_world_manager.xml` (header, search bar, sort chips, **RecyclerView** `world_list` inside a weighted FrameLayout — no ScrollView nesting, empty-state overlay, import bar) + `item_world.xml` + `dialog_world_details.xml`.
- Adapter already has: `DiffUtil` (`dispatchSwap`), `setHasStableIds`, `LruCache` icon decoding off-thread, search filter, 4 sort modes, empty state, landscape 2-col grid.
- Entry: opened from profile cards ("Worlds") with `BUNDLE_GAME_DIR`/profile args.

**Verdict:** HEAD code is largely fixed already; the "broken" symptoms are likely from edge cases + older build. Hardening list (see C5): stable-ID safety, initial-load skeleton, sort persistence, search debounce, keyboard-inset handling, save-dir change detection, `getItemId` guard, DiffUtil `areContentsTheSame` with version field, RecyclerView `setItemAnimator(null)`-free item animations, `onDestroyView` null-guards.

## A8. UI animation engine

- `net.kdt.pojavlaunch.UiMotion` — small dependency-free motion toolkit: `revealScreen` (fade/slide/scale entrance + child cascade), `revealChrome`, `pressFeedback` (scale-down/spring), `fadeInDown`, `revealList` (layout animation `list_item_enter`).
- `PremiumPlayButtonView` — custom Canvas animation (ValueAnimator-driven sheen/wave/particles).
- `MainMenuFragment.applyPremiumTouchAnimation`, `CursorCustomizationFragment.animateEntry`, `RightPaneHomeFragment` FAB/refresh entrances, `screen_opening_game.xml` boot overlay, `anim/` resources (`fragment_enter_forward`, `fade_in_slide_up`, `list_item_enter`, `card_press_anim`).
- Patterns: view-property animators, hardware layer toggling, cancel-on-detach, no per-frame allocations.

## A9. Design tokens

- Background `#0B0B0E` (obsidian), text `#F5F5F5`, accent `#E4E4EA`/`#B9BBC4` (platinum), purple accent `#9B59E8` (launch/glow), muted `#6B7280`/`#9CA3AF`, card bg `#0B1320`/`#F20D0D10`, glass `bg_cs_glass_card`, chips `bg_cs_pill_*`, `bg_csp_*` settings kit.
- Fonts: `sans-serif-light` headings + `sans-serif-medium` body; letterSpacing 0.08–0.24 for eyebrows.
- All drawables are XML gradients (no heavy assets).

---

# PART B — ZALITH LAUNCHER 2 CURSOR SYSTEM (REFERENCE)

## B1. How Zalith does it (concept, verified in source)

```
Minecraft (JVM)                              Android (Compose)
glfwCreateStandardCursor(shape)              ┌──────────────────────────────┐
   └► CursorRegistry: handle ⇄ shape map    │ ZLBridgeStates (StateFlow)   │
glfwSetCursor(window, handle)                │  cursorShape: CursorShape    │
   └► CallbackBridge.onCursorShapeChanged    │  cursorMode: EN/DISABLED     │
        └► debounce (Choreographer 16ms)     └──────────────┬───────────────┘
        └► map → CursorShape enum                          ▼
             Arrow/IBeam/Hand/CrossHair/   ┌──────────────────────────────┐
             ResizeNS/ResizeEW/ResizeAll/  │ MouseLayout / TouchInput     │
             NotAllowed                    │  pointerIcon = shape.icon     │
                                           │  mouseFile  = shape file      │
                                           │  hotspot    = shape hotspot   │
                                           │  AsyncImage(GIF/PNG/SVG)      │
                                           └──────────────────────────────┘
```

Key pieces:
1. **`CursorRegistry.java`** (LWJGL module) — `ConcurrentHashMap` handle⇄shape; `glfwCreateStandardCursor(shape)` registers shape and returns a real handle instead of a constant.
2. **`CallbackBridge.onCursorShapeChanged(int shape)`** — JVM-side hook; maps GLFW constants to `CursorShape` enum (Arrow, IBeam, Hand, CrossHair, ResizeNS, ResizeEW, ResizeAll, NotAllowed), debounced with `Choreographer.postFrameCallbackDelayed(…, 16)` so flickering shapes don't thrash the UI.
3. **`ZLBridgeStates.kt`** — `MutableStateFlow<CursorShape>` + `cursorMode` (enabled/disabled), consumed by Compose.
4. **Per-shape assets & hotspots** — `getMouseFile(cursorShape)` returns per-state custom file; per-shape hotspot stored in `AllSettings.*MouseHotspot`; per-shape default drawables (`img_mouse_pointer_arrow/ibeam/link/crosshair/resize_ns/resize_ew/resize_move/not_allowed`).
5. **Rendering** — `MousePointer` composable draws the shape's image (Coil: GIF/PNG/SVG) at pointer pos offset by hotspot; `TouchpadLayout`/`TouchInput` sets `pointerHoverIcon(shape.composeIcon)` for real hardware pointer.
6. **Settings UI** — `ControlSettingsScreen.kt` lets you pick a mouse file per shape.

## B2. What we reuse vs. rewrite (for CSL — View-based, NOT Compose)

| Zalith concept | CSL adaptation |
|---|---|
| Handle⇄shape registry | Port `CursorRegistry` → `net.kdt.pojavlaunch.cursor.CursorRegistry` (same API, Java) |
| GLFW shape → enum mapping | `CursorState` enum with MORE states (add LOADING/Hourglass, HELP, MOVE, TEXT, custom) |
| StateFlow state | Static `CursorController` singleton + `ExtraCore` events (`REFRESH_CURSOR`, new `CURSOR_STATE_CHANGED`) + listener list |
| Per-shape file/hotspot | `CursorStyle` (per-state config object) persisted as JSON in SharedPreferences |
| Compose `PointerIcon` | `android.view.PointerIcon` + **Touchpad bitmap** (existing CSL rendering path) |
| Coil AsyncImage (GIF) | Existing `Movie`-based GIF decode + `CursorManager.applyGlow` pipeline, extended with rotation/shadow/border/tint |
| Rules (hover→shape) | **New** `CursorRules` engine (user-editable) — Zalith relies on the game's own shapes only; we add launcher-UI rules on top |

No Zalith code is copied; architecture concepts only.

---

# PART C — PHASE 4 IMPLEMENTATION PLAN

## C0. Guiding principles (from user brief)

1. Understand first, code second — **this document is that step**.
2. Keep our design language (obsidian/platinum, `bg_cs_*`/`bg_csp_*` tokens, `UiMotion` motion language).
3. Do NOT touch the working download animation (`ProgressLayout`, `KineticProgressView`, `DownloadListFragment`).
4. Do NOT blindly copy Zalith — reuse concepts, rewrite for View-based Android.
5. Perf: zero per-frame allocations, hardware layers, cancel animators on detach, off-thread IO.
6. Everything configurable, nothing hardcoded: cursor states AND rules are user-editable and persist.

---

## C1. PREMIUM LAUNCH ANIMATION (Priority 0)

**Goal:** pressing PLAY on an installed game must NOT look like downloading. New premium launch sequence:

```
PLAY pressed
  ▼
button morph (scale + color shift to energy state)
  ▼
horizontal energy beam sweeps across button (NOT progress — an energy pulse)
  ▼
profile card reacts (slight lift + glow ring + icon pulse)
  ▼
background subtly animates (ambient aura breathes)
  ▼
game icon scales up (mark tile grow)
  ▼
particle burst + glow pulse
  ▼
"Launching…" state text
  ▼
Minecraft starts (existing boot overlay takes over seamlessly)
```

**Design decisions**
- New class `ui/LaunchSequenceController.java` — orchestrates the sequence over the home screen: `PremiumPlayButtonView` + `card_profile` + background `ImageView`/gradient, then hands off to the existing `screen_opening_game.xml` overlay.
- `PremiumPlayButtonView` gets `beginInstalledLaunch()` (distinct drawing path: energy beam + stronger morph, **no** progress-wave) while `beginLaunch()` stays for download-path tasks. **Task-count listener remains download-only** so installed launches never re-trigger the wave.
- Add `isGameInstalled(profile)`: version JSON + assets presence check (`Tools`/`AsyncMinecraftDownloader` helpers) to choose sequence at click time.
- Sequence choreography via `ValueAnimator` chain + `UiMotion`; respects `onWindowVisibilityChanged`; fail-safe reset 9s (reuse pattern).

**Files**
- NEW `ui/LaunchSequenceController.java`, NEW drawable(s) `bg_launch_energy_beam.xml`, `bg_launch_aura.xml`
- MODIFY `ui/PremiumPlayButtonView.java` (installed-launch mode), `fragments/FastClientHomeFragment.java`, `fragments/HomeProfileAdapter.java` (call `beginInstalledLaunch()` when installed, else `beginLaunch()`), `fragment_home_fastclient.xml` + `item_home_profile_card.xml` (add energy-beam/aura layers + card reaction hooks: IDs only, minimal)
- NEW strings `cs_launch_launching`, `cs_launch_installed`, etc.

**Acceptance:** Pressing PLAY on installed game → energy sequence (no progress bar, no download icon); pressing on uninstalled game → existing download flow unchanged.

---

## C2. ADVANCED CURSOR SYSTEM — ENGINE (Priority 0)

New package `net.kdt.pojavlaunch.cursor`:

| New file | Purpose |
|---|---|
| `CursorState.java` | enum: `ARROW, HAND, IBEAM, LOADING, FORBIDDEN, MOVE, RESIZE_NS, RESIZE_EW, RESIZE_NWSE, RESIZE_NESW, RESIZE_ALL, HELP, CROSSHAIR, TEXT, CUSTOM` + GLFW-shape constants + default drawable + default hotspot per state |
| `CursorRegistry.java` | port of Zalith concept: handle⇄shape `ConcurrentHashMap`, `registerStandardShape(int)`, `shapeFor(long)` |
| `CursorStyle.java` | per-state config: `path, enabled, scale, opacity, glowRadius, glowColor, rotation, shadow(radius/color/offset), border(width/color), tint, hotspotX/Y, animSpeed` + `apply(Bitmap)` pipeline (scale→rotate→tint→shadow→border→glow→opacity) |
| `CursorStore.java` | JSON persistence (`prefs_key_cursor_styles_v1`), default styles, import/export, file mgmt in `Tools.DIR_CURSORS/<state>/` |
| `CursorRules.java` | rule model: `trigger` (view-type / state / custom selector) + `target CursorState`; JSON persisted; default rules (clickable→HAND, EditText→IBEAM, disabled→FORBIDDEN, dragging→MOVE, loading→LOADING, help→HELP, resize→RESIZE_*) |
| `CursorController.java` | **the heart**: computes active `CursorState` from (a) in-game GLFW shape (via CursorRegistry), (b) launcher UI hover rules; debounces (16ms Choreographer, Zalith-style); pushes to (1) `PointerIcon` on surface view, (2) `Touchpad` bitmap, (3) `ExtraCore.REFRESH_CURSOR`; owns GIF frame loop; `reset()/updateFrame()/getCurrentBitmap()` |

**Wiring changes (existing files, minimal edits)**
1. `jre_lwjgl3glfw/lwjgl-3.3.3|3.4.1|3.4.2/src/main/java/org/lwjgl/glfw/GLFW.java`
   - `glfwCreateStandardCursor(shape)` → `CursorRegistry.registerStandardShape(shape)` (return unique handle)
   - `glfwSetCursor(w,c)` → already routes to `CallbackBridge.glfwSetCursor` (keep)
2. `app_pojavlauncher/.../org/lwjgl/glfw/CallbackBridge.java` — `glfwSetCursor`: resolve shape from registry → `CursorController.onGameCursorShape(int)` instead of blindly refreshing.
3. `MinecraftGLSurface.java` — `updateGrabState` uses `CursorController.startAnimation/stopAnimation`; `onTouchEvent`/`dispatchGenericMotionEvent` keep forwarding (they already do) + **launcher hover hook** for hardware pointer.
4. `Touchpad.java` — `onDraw` draws `CursorController.getCurrentBitmap()` with per-state hotspot/scale; keep REFRESH_CURSOR listener.
5. `prefs/LauncherPreferences.java` — legacy `custom_cursor_*` keys mapped into `CursorStore` default/`ARROW` state (back-compat); new `cursor_system_enabled`.
6. `ExtraConstants` — add `CURSOR_STATE_CHANGED` (for Studio live preview).
7. `SettingsMetaStore` — register "Cursor" category entries (states list, rules editor, designer).

**Launcher-UI hover rules (auto cursor switching):**
- New `cursor/CursorHoverTracker.java`: attaches to each screen root (`MainMenuFragment`, `FastClientHomeFragment`, `RightPaneHomeFragment`, settings, world manager, dialogs) via `View.OnHoverListener` + `dispatchTouchEvent`; walks the hit-path; applies `CursorRules` → `CursorController.setUiState(...)`. Cheap: only recomputes when hovered view changes.

**Acceptance:** in-game, hovering MC buttons → Hand, text field → IBeam, un-clickable → Forbidden, resize edges → Resize, normal → Arrow. In launcher, buttons→Hand, text fields→IBeam, disabled→Forbidden, dragging→Move.

---

## C3. CURSOR STUDIO v2 — per-state customization (Priority 1)

Rebuild `CursorCustomizationFragment` into a **multi-tab Studio** (keep the good existing pieces: designer, glow rings, packs):

- **Tab 1 — States:** list of all `CursorState`s (Arrow, Hand, Loading, Forbidden, Move, Resize ×5, Help, IBeam, Crosshair, Custom) with mini live previews; tap → editor.
- **Tab 2 — Style editor (per state):** all controls the user asked for, updating **instantly** (live preview stage at top):
  - Import PNG / animated cursor (GIF; keep `Movie` pipeline)
  - Choose from presets/packs (existing pack grid reused)
  - Reset to default for this state
  - Scale, Rotation, Opacity, Glow (radius + color rings), Shadow (radius/color/offset), Border (width/color), Color Tint
  - Hotspot editor (tap-to-set on preview; keep manual X/Y)
  - Live preview: static frame + animated preview + "test on hover" simulation
- **Tab 3 — Rules:** see C4.
- Save = write one `CursorStyle` → `CursorStore` → `CursorController.reset()` + `REFRESH_CURSOR`.

New layouts: `fragment_cursor_studio.xml` (tabs + stage), `item_cursor_state.xml`, `item_cursor_style_slider.xml` (reused for all numeric props), `item_cursor_rule.xml`. New drawables: `bg_cursor_stage`, `bg_cursor_rule_card`, etc. (match `bg_cursor_*` family already present).

**Acceptance:** every state fully customizable incl. GIF import, with live preview and instant apply; defaults match current classic pointer so existing users see no regression.

---

## C4. CURSOR RULE EDITOR (Priority 1)

- New `fragments/CursorRuleEditorFragment.java` + `item_cursor_rule.xml`:
  - List of rules: `IF <trigger> → USE <cursor>`.
  - Triggers: Button/Clickable, TextInput, Disabled, Dragging, Loading, Help, Resize, Default/Other; each rule's target cursor is a dropdown of all `CursorState`s (Arrow→“Diamond Cursor” etc. — any state can point to any asset).
  - Add / edit / delete / reorder; toggle rule on/off; reset defaults.
  - Persist via `CursorStore`; `CursorController` re-reads rules live.
- Game-side shapes remain fixed GLFW mapping (in-game), launcher-side uses rules.

**Acceptance:** user can set Hand→“Sword Cursor” by importing a sword PNG into the HAND state; rules survive restart.

---

## C5. WORLD MANAGER FIX (Priority 1)

Hardening pass on `WorldManagerFragment` + `WorldListAdapter` (keep all current features):

1. **Adapter safety:** guard `getItemId`/`getItemCount` when empty (`mVisible` empty → return `NO_ID`); `areContentsTheSame` includes `versionName` + `hardcore`; keep DiffUtil but run `calculateDiff` off-thread for big folders (already off-thread scan — move diff off too).
2. **Loading UX:** initial-load skeleton/progress (worlds appear only after scan — add `LOADING` state so page never looks “broken/empty” during scan); empty state only after first scan completes.
3. **Layout:** verify no measure-all path in landscape grid; add `RecyclerView.setItemAnimator` safe defaults; keep weighted FrameLayout (already correct) — add `android:windowSoftInputMode`/`setOnApplyWindowInsetsListener` so search + keyboard don't squash the list; NestedScrollView free (already).
4. **Search:** debounce `afterTextChanged` (300ms) + clear-filter action.
5. **Sort:** persist last sort mode + query in prefs; re-apply on resume.
6. **Data loading:** watch `saves/` dir (`FileObserver`) + refresh on resume (already); refresh count chip + storage card after ops; avoid `requireView()` after detach (null-guards).
7. **Entry fixes:** ensure profile card "Worlds" button passes correct `BUNDLE_GAME_DIR` (verify at implementation).

**Acceptance:** scrolls smoothly, worlds appear fast with skeleton, search/sort stable, layout intact portrait+landscape, empty state correct.

---

## C6. SETTINGS REDESIGN (Priority 2 — after cursor & play)

Complete visual redesign, not a recolor:
- **New layout system:** `fragment_settings_v4.xml` — hero header (title + live profile chip + gradient hairline), sticky search, animated category rail (material motion), content cards with new radius/elevation/glass (`bg_settings_v4_card` family), floating action save bar with morph animation.
- **New components:** settings section headers, stat tiles, pin cards, new toggle/slider/dropdown/button rows (`item_settings_v4_*`) with typography scale (eyebrow 10sp letterspaced / title 16.5sp / value 13sp), new icons (`ic_setting_*`), premium press feedback via `UiMotion.pressFeedback`.
- **New navigation:** category rail scrolls + snap; page transitions via `UiMotion.revealScreen`; search jumps with `flashPendingFocusRow` (keep existing behavior).
- **Keep:** `LauncherPreferenceFragment` logic (draft/save/meta), all `SettingCategory`/`SettingItem` definitions, `SettingsMetaStore` — only swap presentation + IDs per new layouts; update adapter inflation mapping.
- New drawables: `bg_setting_hero`, `bg_setting_card_v4`, `bg_setting_row_active`, `bg_setting_search_v4`, divider hairlines, `ic_setting_*` set.

**Acceptance:** page looks completely different (new cards/nav/glass/typography/spacing), still functional (draft bar, search, categories).

---

## C7. FILE MANIFEST (create / modify)

**CREATE**
```
app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/cursor/
    CursorState.java  CursorRegistry.java  CursorStyle.java
    CursorStore.java  CursorRules.java  CursorController.java
    CursorHoverTracker.java
app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/LaunchSequenceController.java
app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/fragments/CursorRuleEditorFragment.java
res/layout/: fragment_cursor_studio.xml  fragment_settings_v4.xml
             item_cursor_state.xml item_cursor_style_slider.xml item_cursor_rule.xml
             item_settings_v4_*.xml (toggle/slider/dropdown/button/category/nav/stat/pin/header)
res/drawable/: bg_launch_energy_beam.xml bg_launch_aura.xml bg_cursor_stage.xml
               bg_cursor_rule_card.xml bg_setting_hero.xml bg_setting_card_v4.xml
               bg_setting_row_active.xml bg_setting_search_v4.xml  ic_setting_*.xml
```

**MODIFY**
```
jre_lwjgl3glfw/lwjgl-3.3.3|3.4.1|3.4.2 .../org/lwjgl/glfw/GLFW.java   (glfwCreateStandardCursor)
app_pojavlauncher .../org/lwjgl/glfw/CallbackBridge.java              (glfwSetCursor→shape)
.../customcontrols/mouse/Touchpad.java                                (per-state draw)
.../customcontrols/mouse/CustomCursorRenderer.java                    (delegate → CursorController)
.../MinecraftGLSurface.java                                          (hover hooks, grab wiring)
.../ui/PremiumPlayButtonView.java                                    (installed-launch mode)
.../fragments/FastClientHomeFragment.java                            (launch sequence branch)
.../fragments/HomeProfileAdapter.java                                (same)
.../fragments/CursorCustomizationFragment.java                       (→ Studio v2)
.../fragments/MainMenuFragment.java                                  (hover tracker attach)
.../prefs/LauncherPreferences.java                                   (new keys + legacy mapping)
.../prefs/SettingsMetaStore.java                                     (cursor category entries)
.../extra/ExtraConstants.java                                        (CURSOR_STATE_CHANGED)
.../worlds/WorldManagerFragment.java  worlds/WorldListAdapter.java   (hardening)
.../prefs/screens/LauncherPreferenceFragment.java                    (v4 layouts, logic intact)
res/layout/: fragment_home_fastclient.xml item_home_profile_card.xml fragment_cursor_customization.xml
res/values/: strings.xml colors.xml (new tokens) styles.xml (new chips)
```

## C8. Compatibility & risks

| Risk | Mitigation |
|---|---|
| Patching 3 LWJGL copies is repetitive | Shared helper `CursorRegistry` in app module; 3 identical small edits; verify each builds |
| `CursorRegistry` used by game classpath | Keep it in `app_pojavlauncher` (already on classpath for LWJGL stubs) |
| GIF decode memory | Reuse existing `Movie` path; cap cursor bitmap size (≤ 512px, scale down) |
| Hover tracker cost | Only recompute when hovered view changes; skip on `isGrabbing`; no allocations |
| Settings redesign breaking prefs | Keep keys/IDs of settings logic; only presentation changes; draft model untouched |
| Play sequence conflicting with ProgressKeeper | Task listener drives ONLY download; installed sequence uses independent animators with failsafe |
| World manager crash paths | All callbacks null-guarded; DiffUtil off-thread; stable IDs guarded |
| minSdk 26 (Android 8+) | PointerIcon API N+ OK; `FileObserver` OK; hover events OK with mouse; touch-only devices rely on touchpad rendering |

## C9. Build & verify strategy

1. `./gradlew :app_pojavlauncher:compileDebugJavaWithJavac` (or `assembleDebug`) after each milestone.
2. Manual test matrix:
   - Cursor: touchpad mode (virtual cursor changes per state), hardware mouse (PointerIcon + hover rules), in-game MC menu (button→Hand, chat→IBeam), GIF animated cursor, per-state import.
   - Play: installed profile → energy sequence; fresh profile → download flow unchanged; mid-download cancel → button resets.
   - World manager: 1, 50, 500 worlds; search/sort; portrait/landscape; import/export ops.
   - Settings: open each category, edit, save, restart → persisted.
3. No unit-test infra present — rely on compile + manual matrix + logcat (`CursorController` TAG logs state switches for verification).

---

## Suggested execution order (after approval)

1. **C2 engine + GLFW/CallbackBridge wiring** (unblocks everything cursor)
2. **C3 Studio v2 + C4 Rules** (UI on top of engine)
3. **C1 Launch animation** (independent)
4. **C5 World Manager hardening** (independent)
5. **C6 Settings redesign** (largest, last)

Each milestone = its own commit, compile-checked, before moving on.

---

# PART D — IMPLEMENTATION COMPLETED (2026-08-04) + BUILD VERIFICATION

## D1. What was built (all milestones, compile-verified)

| # | Milestone | Status |
|---|---|---|
| C2 | Cursor engine (`cursor/` package: `CursorState`, `CursorRegistry`, `CursorStyle`, `CursorStore`, `CursorRules`, `CursorController`, `CursorHoverTracker`) + GLFW shape→state wiring (3 LWJGL stubs + app `CallbackBridge`) + per-state Touchpad rendering + `CustomCursorRenderer` facade | ✅ |
| C3 | Cursor Studio V2 (`CursorStudioFragment` + `fragment_cursor_studio.xml`): per-state chips, live preview, scale/rotation/opacity/glow/shadow/border/tint/hotspot/anim-speed sliders, presets, PNG/GIF import, reset/save, system master switch | ✅ |
| C4 | Rule Editor tab: editable IF-then table, per-rule target spinner + enable switch + delete, add/reset defaults, auto-persist | ✅ |
| C1 | Premium launch sequence: `PremiumPlayButtonView.beginInstalledLaunch()` (energy beam — NO progress feel), `LaunchSequenceController` (card lift + glow ring + icon scale + background aura + "Launching…"), installed-vs-download branch in `FastClientHomeFragment` + `HomeProfileAdapter` | ✅ |
| C5 | World Manager hardening: loading state (no empty-flash), debounced search, sort+query persistence, stable-ID guards, null-safe teardown | ✅ |
| C6 | Settings V4 redesign: new header/search/card/rail/save-bar drawables, rewritten `fragment_custom_settings.xml`, restyled category card + nav chip + dashboard stat, new rail selection | ✅ |

## D2. Build verification (sandbox, JDK 17 + Android SDK 34 + NDK 27.3)

```
:app_pojavlauncher:compileDebugJavaWithJavac  → BUILD SUCCESSFUL
:app_pojavlauncher:processDebugResources      → BUILD SUCCESSFUL  (all new layouts/drawables/strings link)
:jre_lwjgl3glfw:lwjgl-3.3.3:compileJava/jar   → BUILD SUCCESSFUL
:jre_lwjgl3glfw:lwjgl-3.4.1:compileJava/jar   → BUILD SUCCESSFUL
:jre_lwjgl3glfw:lwjgl-3.4.2:compileJava/jar   → BUILD SUCCESSFUL
:app_pojavlauncher:assembleDebug              → blocked by sandbox RAM (1.9 GB) — daemon OOM during
                                                LWJGL jar-merge; NOT a code issue (Java+resources+all
                                                modules compile & link cleanly).
```

**To build on a normal machine:**
```bash
# JDK 17 required (AGP 8.7.2). Android SDK 34 + NDK 27.3.13750724 auto-install.
./gradlew :app_pojavlauncher:assembleDebug
```

## D3. Notes / caveats
- Workspace snapshot between sessions dropped `.git` and untracked binary libs (`app_pojavlauncher/libs/*`); they were restored from a fresh clone of the repo (all committed libs re-downloaded, `.git` re-attached, working-tree edits preserved). `git status` shows exactly the Phase-4 changes.
- Legacy single-cursor prefs (`custom_cursor_*`) are honoured live for the ARROW state until the user saves through the new Studio — no user-facing regression.
- `CursorCustomizationFragment` (old page) left in place (unused) for rollback safety; the bottom-bar button now opens the new Studio.
- In-game cursor shapes (Hand/I-Beam/Forbidden/Resize…) now reach the UI via `CursorRegistry` — this was the pre-existing missing link (stubs returned a constant).
