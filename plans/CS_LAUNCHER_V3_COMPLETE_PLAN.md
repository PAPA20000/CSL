# CS LAUNCHER V3 — COMPLETE IMPLEMENTATION PLAN
## Phase 1 — Planning (NO CODE YET)

**Status:** Planning Complete — Awaiting User Approval Before Implementation  
**Repo:** `https://github.com/craftstudioteam/CS-LAUNCHER-v2` (current workspace) / `https://github.com/JoooaoooDVD/CRAFT-STUDIO-LAUNCHER-.git` (referenced)  
**Branch:** `main` (will be verified for push workflow fix)  
**Branding Target:** `CS Launcher V3` (replaces all `CS LAUNCHER V2` / `CS LAUNCHER v2`)  
**Design Target:** Premium mobile-first launcher — comparable to commercial launchers (Nova, Apex, Niagara-style premium aesthetic)  
**Layout Target:** `res/layout-land/` (complete redesign — nothing kept)  
**Download Target:** Profile-based (per-profile Mods / Resource Packs / Shader Packs — no global download buttons)  
**Performance Target:** Low memory, smooth 60 FPS animations, reduced overdraw  
**Compatibility:** Must remain fully compatible with existing launcher architecture (2-process, SDL-AWT bridge, profile JSON, `LauncherProfiles`, `PojavApplication`, `ProgressKeeper`)

---

## 1. CURRENT STATE ANALYSIS (Verified)

### 1.1 Workspace Verification
- Cloned repo exists at `/home/user/CS-LAUNCHER-v2`
- Git history intact (`.git` present)
- Previous modifications applied: `ModpackGenerator`, `ModpackBuilderFragment`, `ShareCode*`, `SmartRecommender`, `CompatibilityEngine`, layouts, and `ProfileTypeSelectFragment` references removed.
- `Modpack Download` pipeline (`SearchModFragment`, `ModpackCreateFragment`, `ModpackInstaller`, APIs, `ModloaderInstallTracker`, image cache) — **fully intact and verified safe**.
- `reports/CS_LAUNCHER_V2_ANALYSIS_REPORT.md` exists with full previous analysis.

### 1.2 Branding Audit (Current References)
| File / Resource | Current Value | Must Change To |
|---|---|---|
| `strings.xml` `app_name` | `CS LAUNCHER V2` | `CS Launcher V3` |
| `strings.xml` `app_short_name` | `CS LAUNCHER v2` | `CS Launcher V3` |
| `strings.xml` `cs_launcher_title` | `CS LAUNCHER v2` | `CS Launcher V3` |
| `strings.xml` `cs_launcher_subtitle` | `YOUR MINECRAFT. YOUR WAY.` | `YOUR MINECRAFT. REIMAGINED.` (or new) |
| `strings.xml` `error_fatal` | `CS LAUNCHER V2 has unexpectedly crashed` | `CS Launcher V3 has unexpectedly crashed` |
| `strings.xml` `lazy_service_default_title` | `CS Launcher Foreground Service` | `CS Launcher V3 Foreground Service` |
| `README.md` | `CS LAUNCHER V2`, `V2` branding | `CS Launcher V3` |
| `build.gradle` `resValue` | `CS LAUNCHER V2` (debug/release) | `CS Launcher V3` |
| `README.md` header badges | `Version-V2`, `Platform-Android`, etc. | `Version-V3` |
| `layout-land/activity_pojav_launcher.xml` `tv_launcher_title` | `@string/cs_launcher_title` (V2) | Same reference, value updated |
| `layout-land/activity_pojav_launcher.xml` header text | No subtitle currently | Add subtitle `YOUR MINECRAFT. REIMAGINED.` |
| `.github/workflows/android.yml` `name:` | `CS Launcher V2 CI` | `CS Launcher V3 CI` |
| `.github/workflows/android.yml` release name | `CS Launcher V2 — Build ...` | `CS Launcher V3 — Build ...` |
| `.github/workflows/android.yml` artifacts | `CS-LAUNCHER-V2.apk` | `CS-LAUNCHER-V3.apk` (or keep filenames? User didn't specify — plan proposes `CS-LAUNCHER-V3.apk` for new branding) |

### 1.3 Theme / Color Audit
| Color Name | Current Value | Plan |
|---|---|---|
| `neon_green` | `#39FF14` (primary accent) | Keep or shift to premium accent (e.g., `#00E5FF` cyan + `#FF2D55` pink for premium dual-tone) |
| `bg_launcher_dark` | Dark background | Upgrade to premium dark gradient (`#0A0E17` → `#141B2D`) |
| Card backgrounds (`bg_profile_button`, etc.) | `#1A1A2E` style | Glass effect cards (`#15FFFFFF` white at 8-12% opacity) with glass blur |
| `primary_text` | `#F5F5F5` | Keep white but improve typography scale |
| Theme presets (`AppTheme`, `AppTheme_Gradient`, etc.) | 6 presets | Expand to 8-10 premium presets; all updated for V3 branding |

### 1.4 Layout Structure Audit (`layout-land/`)
Only 2 files exist:
- `activity_pojav_launcher.xml` — Main launcher container (`ConstraintLayout`) with header bar (`header_bar` LinearLayout), fragment container (`container_fragment`), progress bar (`ProgressLayout`)
- `fragment_launcher.xml` — Minimal launcher fragment layout

The user wants the **entire home screen redesigned from scratch** in `layout-land/`. This means `activity_pojav_launcher.xml` must be rebuilt completely. `fragment_launcher.xml` may also need redesign depending on navigation flow.

---

## 2. PHASE 1 — COMPLETE IMPLEMENTATION PLAN

### 2.1 High-Level Architecture Changes (No Breaking Changes to Core Engine)

**Must Remain Unchanged:**
- `build.gradle` plugin version (`8.7.2`), `compileSdk` (`34`), `minSdk` (`21`), `targetSdk` (`34`), `multiDexEnabled`
- `AndroidManifest.xml` activities (`LauncherActivity`, `MainActivity`, `JavaGUILauncherActivity`, etc.), permissions (`INTERNET`, `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"`, `POST_NOTIFICATIONS`), services (`ProgressService`, `GameService`), providers (`FolderProvider`)
- `PojavApplication.java` (`sExecutorService`, crash handler, `AsyncAssetManager`)
- `LauncherProfiles` JSON persistence (`launcher_profiles.json`)
- `ProgressKeeper` and `ProgressLayout`
- SDL-AWT bridge (`AWTCanvasView`, `AWTInputBridge`)
- `CustomControlsActivity` and gamepad mapping libraries
- Mod loader installation fragments (`FabricInstallFragment`, `ForgeInstallFragment`, `NeoForgeInstallFragment`, etc.)
- Authentication fragments (`MicrosoftLoginFragment`, `LocalLoginFragment`, etc.)
- `LauncherPreferences` and settings structure
- Download pipeline (`SearchModFragment`, `ModpackCreateFragment`, `ModpackInstaller`, `ModItemAdapter`, APIs, image cache) — **intact, but integrated into new profile-based download flow**

**Can Be Modified:**
- All layout XML in `res/layout-land/` and `res/layout/` (complete redesign allowed)
- All `drawable/` resources (cards, buttons, backgrounds, icons)
- `values/colors.xml`, `values/styles.xml`, `values/dimens.xml`, `values/attrs.xml`
- `values/strings.xml` (branding update + any new strings for redesigned UI)
- `mipmap/` icons (`ic_launcher_round`, `ic_launcher`) — must be updated to V3 branding
- `res/layout-land/activity_pojav_launcher.xml` — rebuilt from scratch
- Fragment classes referenced from redesigned layouts — may need layout changes but core logic preserved
- Navigation flow within `LauncherActivity` — can be redesigned (new fragment transactions, new pane structure)
- `ProfileTypeSelectFragment` — redesigned UI (cards, buttons, layout) — logic preserved
- `MainMenuFragment` and `FastClientHomeFragment` — redesigned UI — navigation logic preserved

---

### 2.2 Design System — V3 Premium Design Language

**Concept:** "Premium Mobile Gaming Hub"
- Dark, glass-morphic aesthetic
- Deep space / cosmic dark background with subtle gradient (`#060A14` → `#0D152A` → `#0A0E17`)
- Premium dual-tone accent: Cyan (`#00E5FF`) + Rose (`#FF2D55`) — used selectively, not everywhere
- Glass cards: `#12FFFFFF` (8% white) with `#1AFFFFFF` (10% white) borders, rounded `16dp` or `24dp`
- Glass header: `#0DFFFFFF` (5% white) with `#1AFFFFFF` border
- Typography: System font (Roboto / sans-serif) but scaled premium:
  - Title: 32sp-36sp (bold, white `#FFFFFF`)
  - Subtitle: 16sp-18sp (medium, `#A8B2D1` or `#8892B0`)
  - Body: 14sp-15sp (regular, `#A8B2D1`)
  - Caption: 11sp-12sp (`#64748B` or `#8892B0`)
- Shadows: Deep, soft, large radius (`android:elevation="12dp"` or `16dp` for main play button, `8dp` for cards)
- Rounded corners everywhere: `24dp` for large cards, `16dp` for medium, `12dp` for buttons, `8dp` for small chips
- Consistent spacing: 8dp grid (`_8sdp`, `_12sdp`, `_16sdp`, `_24sdp`, `_32sdp`, `_48sdp`)

**Animation Design System:**
- **Page transitions:** `slide_in_right` / `slide_out_left` (current) upgraded to `fade_in_slide` with faster duration (`250ms` instead of default `300ms`)
- **Card entrance:** Staggered fade + translate Y (`30f` → `0f`) with `DecelerateInterpolator`, delay `40ms`
- **Button press:** Scale `0.94x` / `0.94y` (`100ms` down), `0.96x` (`80ms` up) + `OvershootInterpolator(1.8f)` — smoother than current `0.96`
- **Button ripple:** Custom ripple drawable with `#20FFFFFF` overlay and `200ms` duration
- **Play button pulse:** Continuous `pulse_animation` (`scaleX`/`scaleY` `1.0` → `1.05` → `1.0`, `alpha` `0.9` → `1.0`), `duration` `1200ms`, `repeatCount` `infinite`
- **Loading animations:** Custom `ProgressLayout` redesigned — thin cyan progress line (`2dp` height) with `#00E5FF` color, glass container, subtle shimmer
- **Fragment transitions:** `fade_in_slide_up` / `fade_out_slide_down` for settings/mod store; `slide_in_left` / `slide_out_right` for profile editor; faster (`200ms`)

---

### 2.3 Branding Replacement Plan (Every File)

**Files That Must Be Updated:**

| File Path | Action | Details |
|---|---|---|
| `app_pojavlauncher/src/main/res/values/strings.xml` | Update all V2 references | `app_name`, `app_short_name`, `cs_launcher_title`, `cs_launcher_subtitle`, `error_fatal`, `lazy_service_default_title`, `modpack_builder_title` (if kept or replaced), `search_modpack_*`, `modpack_install_*`, `notification_permission_*`, etc. — all must reference `CS Launcher V3` or new subtitle. |
| `README.md` (root) | Full rewrite | Replace all `CS LAUNCHER V2`, `V2`, badges (`Platform-Android`, `Min SDK-26`, `Version-V2`), links, download links, feature descriptions, build instructions. |
| `build.gradle` | Update `resValue` strings | `app_name` and `app_short_name` for debug/release/proguard/gplay. |
| `.github/workflows/android.yml` | Update workflow name, artifact names, release title/body | `CS Launcher V3 CI`, `CS-LAUNCHER-V3.apk`, `v3.` tag format. Also fix `on.push` trigger (see Section 4). |
| `.github/ISSUE_TEMPLATE/*.yml` | Update references | Any `V2` references in templates. |
| `dataremoval.md` | Update branding | Any launcher name references. |
| `GPLAY_PRIVACY_POLICY` | Update branding | App name references. |
| `crowdin.yml` | Update project info | If any branding references exist. |
| `mailmap.txt` | Check and update | Email/domain associations if they reference old branding. |

**Files That Must Have Branding Applied Visually:**
- `res/layout-land/activity_pojav_launcher.xml` — `tv_launcher_title` references `cs_launcher_title` (updated in strings). Must also show subtitle.
- `res/drawable/ic_launcher_background.xml` / `ic_launcher_foreground.png` / `mipmap-*` — Must be redesigned for V3 branding. Plan proposes a modern geometric or abstract icon (gradient circle or diamond) instead of the current block-style icon.
- `res/drawable/bg_client_features_btn.xml` / `bg_client_features_btn_filled.xml` — Must use new premium colors.
- `res/drawable/bg_profile_button_highlight.xml` — Highlight color updated to premium accent (`#00E5FF` or `#FF2D55`).
- `res/drawable/bg_neon_indicator.xml` — Color updated to premium cyan (`#00E5FF`).
- `res/drawable/bg_hero_minecraft.png` / `amethyst.png` — Replace with new hero image for V3 (premium dark cosmic scene or geometric abstract).

---

### 2.4 Complete Home Screen Redesign — `layout-land/activity_pojav_launcher.xml`

**Current Problems:**
- Header bar (`header_bar`) is crowded: Logo + Title + 5 nav icons + Client Features button + Skin nav + Spacer + Home nav + Account Spinner + Settings = too many elements in a single `52sdp` height bar.
- No subtitle (`cs_launcher_subtitle` exists in strings but is not used in layout).
- Fragment container (`container_fragment`) is simple; progress layout (`ProgressLayout`) overlaps with header due to `constraintTop_toBottomOf="header_bar"` but has `layout_marginTop="4dp"`, making it visually disconnected.
- No footer or bottom navigation bar for quick actions (Play, Profile, Downloads).
- No premium glass effects.

**New Design — `layout-land/activity_pojav_launcher.xml` (Rebuilt from Scratch):**

```xml
<!-- Conceptual structure (will be fully written in Phase 2) -->
<androidx.constraintlayout.widget.ConstraintLayout
    ...
    android:background="@drawable/bg_premium_cosmic_gradient">

    <!-- Glass Header -->
    <LinearLayout android:id="@+id/glass_header" ...>
        <!-- Logo (left) -->
        <!-- Title + Subtitle (center-left) -->
        <!-- Premium Nav Pills (center-right): Download | Controls | Cursor | Skin -->
        <!-- Account + Settings (right) -->
    </LinearLayout>

    <!-- Main Content Area -->
    <androidx.fragment.app.FragmentContainerView android:id="@+id/container_fragment" ... />

    <!-- Premium Play Section (Overlapping / Floating) -->
    <androidx.cardview.widget.CardView
        android:id="@+id/play_card"
        android:elevation="16dp"
        app:cardCornerRadius="24dp"
        ... >
        <!-- Profile Selector -->
        <!-- Version Selector -->
        <!-- RAM Selector -->
        <!-- Play Button -->
    </androidx.cardview.widget.CardView>

    <!-- Quick Actions Bar (Bottom Glass) -->
    <LinearLayout android:id="@+id/quick_actions_bar" ... >
        <!-- Profile Manager -->
        <!-- Download Manager -->
        <!-- News / Updates -->
    </LinearLayout>

    <!-- Glass Progress Indicator -->
    <com.kdt.mcgui.ProgressLayout android:id="@+id/progress_layout" ... />

</androidx.constraintlayout.widget.ConstraintLayout>
```

**Detailed Component Plan:**

**Glass Header (`glass_header`):**
- Height: `56sdp` (slightly taller for premium feel)
- Background: `@drawable/bg_glass_header` — `#0DFFFFFF` (5% white) with `#1AFFFFFF` (10% white) bottom border (`1dp` height)
- Left: Logo (`ImageView`, `28sdp`) — new V3 geometric icon; Title (`TextView`, `20sp`, bold, `#FFFFFF`) + Subtitle (`TextView`, `13sp`, `#A8B2D1`)
- Center-Right: Premium nav pills (not icons only) — small glass buttons (`#12FFFFFF`) with icon + text label (`Download Mods`, `Controls`, `Cursor`, `Skin`) — `36sdp` width, `36sdp` height, rounded `18dp`
- Far Right: Client Features (`Button` — redesigned pill: `#12FFFFFF` background, `#00E5FF` text, `✦` icon, `14sp`, bold) + Account Spinner (`mcAccountSpinner` — redesigned glass spinner) + Settings (`ImageButton` — redesigned circular button, `36sdp`)

**Main Fragment Container (`container_fragment`):**
- Same ID preserved (`R.id.container_fragment`). Size: `0dp` (constraints between header bottom and quick actions top, with `8dp` margin on all sides).
- Background: Transparent (shows premium cosmic gradient from root layout).

**Premium Play Card (`play_card`):**
- Floating card (not full-width) — `maxWidth` `480sdp`, centered horizontally, positioned in upper-middle of content area (`constraintTop_toBottomOf="glass_header"` + `24dp`, `constraintBottom_toTopOf="quick_actions_bar"` — `48dp` gap).
- Background: `@drawable/bg_glass_card_premium` — `#12FFFFFF` (8% white) with `#1AFFFFFF` (10% white) border (`1.5dp`), `cornerRadius="24dp"`.
- Shadow: Deep (`android:elevation="16dp"` or custom shadow drawable for better control).
- Internal layout (`LinearLayout`, vertical):
  1. **Profile Selector Row:** Label (`Profile`, `11sp`, `#8892B0`) + Profile name (`TextView`, `18sp`, bold, `#FFFFFF`) + Arrow (`ImageView`, `20sdp`, `#00E5FF`) — click navigates to profile picker.
  2. **Version Selector Row:** Label (`Version`, `11sp`) + Version ID (`TextView`, `14sp`, `#A8B2D1`) — click navigates to version picker.
  3. **RAM Selector Row:** Label (`RAM`, `11sp`) + RAM value (`TextView`, `14sp`, `#A8B2D1`) — click opens RAM settings dialog (new or existing).
  4. **Download Status / Quick Downloads:** If profile has download tasks (`ModloaderInstallTracker` active), show a compact progress row (`2dp` cyan progress bar, `#00E5FF`, `12sp` status text). Otherwise hidden.
  5. **Play Button:** Large, centered, glass pill button (`#0DFFFFFF` background with `#00E5FF` border `2dp`, `text="▶ PLAY"`, `22sp` bold, `#00E5FF` text, `paddingVertical="16dp"`, `paddingHorizontal="48dp"`, `cornerRadius="32dp"`, ripple `#2000E5FF`). Animates with `pulse_animation` continuously (subtle).

**Quick Actions Bar (`quick_actions_bar`):**
- Height: `72sdp`
- Background: `@drawable/bg_glass_bar` — `#0DFFFFFF` with `#1AFFFFFF` top border.
- 3 equal columns (`LinearLayout` with `layout_weight="1"`):
  - **Profile Manager:** Glass card button (`#12FFFFFF`), icon (`ic_profile`), text (`Profiles`, `12sp`), click → `ProfileTypeSelectFragment` swap.
  - **Download Manager:** Glass card button, icon (`ic_download_box`), text (`Downloads`, `12sp`), shows badge (`9dp` circle, `#FF2D55`) if active downloads exist, click → new `DownloadDashboardFragment` (or `SearchModFragment` redesigned as full-screen download hub).
  - **News / Updates:** Glass card button, icon (`ic_news` or `ic_info`), text (`What's New`, `12sp`), click → new `NewsFragment` (or simple dialog showing changelog / V3 features).

**Progress Layout (`ProgressLayout`):**
- Redesign: Instead of full-width bar at top, make it a thin floating glass bar (`height="28sdp"`, `cornerRadius="14dp"`, background `#12FFFFFF`, border `#1AFFFFFF`) positioned at `constraintTop_toBottomOf="glass_header"`, centered horizontally (`0dp` width with `32dp` margins), `elevation="8dp"`.
- Internal progress bar: `2dp` height, cyan (`#00E5FF`), animated shimmer effect (gradient shimmer drawable).
- Text: `14sp`, `#A8B2D1` (task name), `11sp` `#64748B` (progress %).

---

### 2.5 Theme Redesign Plan

**New Theme System (`values/styles.xml`, `values/colors.xml`, `values/dimens.xml`, `values/attrs.xml`):**

**Colors (`colors.xml`):**
- Keep existing structure. Update key colors:
  - `neon_green` → `#00E5FF` (primary cyan)
  - `background_app` → `#060A14` (deeper dark)
  - `background_overlay` → `#0D152A` (surface)
  - `primary_text` → `#FFFFFF`
  - `secondary_text` → `#A8B2D1`
  - `divider` → `#1AFFFFFF` (glass border)
  - `background_surface` → `#0D152A`
  - New `glass_card_bg`: `#12FFFFFF`
  - New `glass_card_border`: `#1AFFFFFF`
  - New `glass_header_bg`: `#0DFFFFFF`
  - New `premium_rose`: `#FF2D55`
  - New `premium_cyan`: `#00E5FF`

**Styles (`styles.xml`):**
- `AppTheme` (base): Dark, no action bar (full-screen launcher), `windowNoTitle`, `windowBackground` = `@drawable/bg_premium_cosmic_gradient`
- `AppTheme_Gradient`: Gradient background (`bg_premium_cosmic_gradient` drawable — `gradient` from `#060A14` to `#0A0E17` with a subtle cyan glow at bottom-right)
- New `AppTheme_Glass`: Higher glass opacity (`#18FFFFFF` cards) for users who prefer more opaque UI
- `AppTheme_MidnightBlue` (existing preset): Update to match new deep dark (`#060A14` base, `#1B2A3B` surfaces)

**Drawables Redesign (`drawable/`):**
- `bg_premium_cosmic_gradient.xml`: Linear gradient (`#060A14` → `#0A152A` → `#060A14`) with `angle="135"`
- `bg_glass_header.xml`: Shape drawable, `solid` `#0DFFFFFF` (5% white), `corners` `bottomLeft="16dp"`, `bottomRight="16dp"`
- `bg_glass_card_premium.xml`: Shape drawable, `solid` `#12FFFFFF` (8% white), `stroke` (`#1AFFFFFF`, `1.5dp`), `corners` `24dp`
- `bg_glass_bar.xml`: Shape drawable, `solid` `#0DFFFFFF`, `stroke` `#1AFFFFFF` (`1dp`), `corners` `16dp`
- `bg_profile_button.xml`: Replace with glass card style (`#12FFFFFF` + `#1AFFFFFF` border, `cornerRadius="16dp"`)
- `bg_profile_button_highlight.xml`: Highlighted download button — `#12FFFFFF` + `#00E5FF` border (`2dp`)
- `bg_client_features_btn.xml` / `bg_client_features_btn_filled.xml`: Redesign to premium pill (`cornerRadius="20dp"`, `#12FFFFFF` bg, `#00E5FF` text, filled version: `#00E5FF` bg, `#060A14` text)
- `bg_account_spinner.xml`: Glass spinner (`#0DFFFFFF` bg, `#1AFFFFFF` border)
- `bg_badge_pill.xml`: Small glass pill (`#12FFFFFF`, `#00E5FF` text, `cornerRadius="8dp"`)
- `bg_neon_indicator.xml`: Update to cyan (`#00E5FF`) instead of `#39FF14`
- `ic_launcher_background.xml`: Redesign — geometric abstract shape (diamond or circle with gradient `#00E5FF` → `#FF2D55`)
- `ic_launcher_foreground.png` / `mipmap-*`: Regenerate with new V3 geometric design (must be done externally; plan assumes new PNG assets will be placed)

---

### 2.6 Navigation Flow Redesign (Compatible with Existing `LauncherActivity`)

**Current Flow:**
- `LauncherActivity` hosts `FragmentContainerView` (`container_fragment`)
- `MainMenuFragment` (root) opens child panes (`ProfileTypeSelectFragment`, `SearchModFragment`, etc.) via `openChildPane()`
- Two-pane mode: `right_pane_container` for settings/search

**New Flow (Same Architecture, Better Navigation):**
- Root fragment: `MainMenuFragment` (redesigned UI with premium cards, glass header)
- Child navigation preserved: `ProfileTypeSelectFragment`, `SearchModFragment`, `ProfileEditorFragment`, `FastClientHomeFragment`, `ClientFeaturesFragment`, etc.
- No change to `navigateTo()` or `openChildPane()` logic — only layout updates.
- **New Addition:** When user selects a profile from redesigned `ProfileTypeSelectFragment`, a profile detail overlay/card appears (instead of navigating to full-screen editor immediately) — allows quick play without leaving home screen. This requires a new overlay fragment or dialog (`ProfileQuickOverlayFragment`) that can be swapped quickly. Plan proposes adding `ProfileQuickOverlayFragment` (new class) or redesigning `ProfileEditorFragment` to support a compact overlay mode.

**Profile-Based Download Navigation (New Requirement):**
- Current: `ModpackCreateFragment` has `button_browse_modpacks` (→ `SearchModFragment`) and `button_import_modpack` (file picker for `.zip`/`.mrpack`)
- New Requirement: Each profile manages its own Mods, Resource Packs, Shader Packs with download options inside the profile.
- Implementation approach (plan only, no code):
  1. Redesign `ProfileEditorFragment` to include 3 new tabs/sections: `Mods`, `Resource Packs`, `Shader Packs`.
  2. Each section has a `Download` button that opens `SearchModFragment` (for mods) or a new resource-pack search (`ResourcePackSearchFragment` — may reuse `SearchModFragment` with different filter) directly within the profile editor.
  3. Downloaded files (`.jar` for mods, `.zip` for resource packs) are saved to the profile's `gameDir` subfolders (`mods/`, `resourcepacks/`, `shaderpacks/`) automatically by `ModpackInstaller` or a new `ProfileResourceInstaller`.
  4. No profile selection dialog appears — the currently edited profile is the target.

---

### 2.7 Animation Plan (Detailed — Each Component)

**Header Bar (`glass_header`):**
- Entrance: `fade_in` (`alpha` `0` → `1`, `duration` `400ms`, `delay` `100ms` from root layout start)
- Icon hover (nav icons): `scale` `0.9` (`80ms`) + `scale` `1.0` (`100ms`) with `bounce` (`OvershootInterpolator(2f)`)
- Active indicator (`nav_home_indicator`, etc.): `slide_down` (`translateY` `-4dp` → `0dp`, `alpha` `0` → `1`, `duration` `200ms`) when navigation changes

**Play Card (`play_card`):**
- Entrance: `slide_up_fade` (`translateY` `40dp` → `0dp`, `alpha` `0` → `1`, `duration` `500ms`, `interpolator` `DecelerateInterpolator`)
- Continuous pulse: `pulse_animation.xml` (`scaleX` `1.0` → `1.03` → `1.0`, `scaleY` same, `duration` `1500ms`, `repeatCount` `infinite`, `interpolator` `LinearInterpolator` for smoothness)
- Press: `scale` `0.96` (`80ms` down), `scale` `1.0` (`120ms` up, `OvershootInterpolator`), `alpha` `0.85` (down) → `1.0` (up)
- Ripple: Custom ripple (`bg_ripple_premium.xml`) — `#15FFFFFF` circle expanding from touch point (`duration` `300ms`)

**Quick Actions Bar (`quick_actions_bar`):**
- Entrance: `fade_in_slide_up` (`translateY` `20dp` → `0dp`, `alpha` `0.6` → `1`, `duration` `300ms`, `startDelay` `200ms` after play card)
- Button press: Same as header nav icons (`scale` `0.92`, `bounce` up)

**Profile Selector / Version Selector Rows:**
- Click: `scale` `0.97` (`60ms` down), `scale` `1.0` (`100ms` up) + `alpha` `0.9` → `1.0`
- Arrow rotation: `rotate` `0°` → `90°` (`200ms`, `DecelerateInterpolator`) when expanded; reverse on collapse

**Fragment Transitions:**
- `ProfileTypeSelectFragment` (profile selection): `fade_in_slide_right` (`translateX` `30dp` → `0`, `duration` `250ms`)
- `ProfileEditorFragment`: `fade_in_slide_up` (`translateY` `30dp` → `0`, `duration` `300ms`)
- `SearchModFragment` (downloads): `fade_in` (`alpha` `0` → `1`, `duration` `200ms`) — faster for quick access
- `ClientFeaturesFragment`: `slide_in_right` (`translateX` `full` → `0`, `duration` `250ms`)

**Progress Indicators:**
- `ProgressLayout` redesigned: Glass container `fade_in` (`150ms`), cyan progress bar grows smoothly (`LinearInterpolator` but with `accelerateDecelerate` for premium feel), shimmer overlay (`translateX` `-100%` → `+100%`, `repeatCount` `infinite`, `duration` `1000ms`)

---

### 2.8 Performance Impact Assessment

**Expected Improvements:**
- Reduced layout nesting: Current `activity_pojav_launcher.xml` has `ConstraintLayout` → `LinearLayout` (`header_bar`) with many nested `FrameLayout` + `LinearLayout` elements. Redesign will use flatter structure: `ConstraintLayout` root with direct `CardView` and `TextView` elements where possible, reducing `measure/layout` passes.
- Glass effects: `#12FFFFFF` solid colors are faster than complex gradients (`#464646` → `#1A39FF14` etc.). New glass style uses simple semi-transparent colors (faster compositing) rather than complex shape drawables.
- Animation optimization: Faster durations (`200ms`-`300ms` instead of `350ms`-`500ms`) + `DecelerateInterpolator` (fewer frames needed) = lower CPU usage.
- Image caching: Existing `IconCacheJanitor` and `ReadFromDiskTask` preserved — no performance regression.
- Download pipeline preserved (`ModloaderInstallTracker`, `ProgressKeeper`) — no performance change.

**Potential Risks:**
- `glass_header` with `elevation="16dp"` on main card + `8dp` on progress + `4dp` on buttons = multiple shadow layers. Must ensure `elevation` values are not stacked excessively (one high, others low). Plan uses `16dp` only for `play_card`, `8dp` for progress, `4dp` for quick actions.
- Continuous `pulse_animation` (`infinite`) on play button — low CPU impact (`scale` transforms are GPU-accelerated on modern Android), but must be paused when fragment is not visible (`LifecycleObserver` to stop/restart pulse).
- New gradient background (`bg_premium_cosmic_gradient`) — linear gradient is GPU-rendered; no significant CPU overhead.
- `FragmentContainerView` preserved — same performance.

**Memory Impact:**
- New drawables (`bg_premium_cosmic_gradient`, glass shapes) are vector XML or small PNG — minimal memory.
- No new bitmap assets in layout (only reference to new `ic_launcher` PNG in `mipmap/` — external asset replacement needed).
- No additional fragments planned (only redesign of existing `ProfileTypeSelectFragment`, `ProfileEditorFragment`, `SearchModFragment`, `ModpackCreateFragment`, `FastClientHomeFragment`, `MainMenuFragment`).

---

### 2.9 Compatibility with Existing Launcher

**Fully Compatible:**
- `LauncherActivity.java` — only layout reference updated (`activity_pojav_launcher.xml` rebuilt with same `container_fragment`, `header_bar`, `progress_layout` IDs where needed, or new IDs handled in Java). Plan proposes keeping same `container_fragment` ID to avoid Java changes.
- `PojavApplication.java` — no changes.
- `ProgressLayout.java` — preserved; only layout XML updated (new glass design, same `android:id="@+id/progress_layout"`).
- `mcAccountSpinner.java` — preserved; layout reference updated in header.
- `LauncherProfiles.java` — preserved; profile JSON format unchanged.
- `ModpackInstaller.java` — preserved; profile-based download uses same `installModpack()` method. Only new wrapper logic needed (see Section 3.
- `SearchModFragment.java` — preserved; layout XML redesigned but fragment class unchanged.
- `ProfileEditorFragment.java` — preserved; layout XML redesigned.
- `ProfileTypeSelectFragment.java` — preserved; layout updated.
- `FastClientHomeFragment.java` — preserved; layout updated.
- `ClientFeaturesFragment.java` — preserved.
- `CustomControlsActivity.java` — no changes.
- `ThemeManager.java` — preserved; styles/colors updated in XML.
- `BaseActivity.java` — preserved.

**Requires Minor Java Updates (Not Breaking):**
- If new IDs are introduced in `layout-land/activity_pojav_launcher.xml` (e.g., `glass_header` instead of `header_bar`), `LauncherActivity.setupNavButtons()` and `bindViews()` must reference new IDs. Plan proposes keeping `header_bar` ID to avoid Java changes, or updating references (low effort, non-breaking).
- `ProfileTypeSelectFragment.java` button wiring (`wireButton()`) — if new layout removes `modpack_builder` / `modpack_import` (already removed), remaining buttons (`vanilla_profile`, `modded_profile_fabric`, etc.) must still exist. Plan confirms these IDs preserved or redesigned with same names.
- `ProfileTypeSelectFragment` redesigned to include subtitle (`cs_launcher_subtitle` added to strings, but layout must reference it). If layout uses `findViewById()` for subtitle, Java changes needed only if subtitle needs dynamic updates (plan proposes static subtitle, no Java change needed).

---

### 2.10 Git Workflow Plan

**Requirements:**
- After every major milestone: commit with meaningful message.
- If user explicitly says `"Push"`: commit pending changes (if needed), push latest branch to configured GitHub repo.
- Fix `.github/workflows/android.yml` so every push to `main` triggers build.

**Plan Implementation:**

1. **Fix Workflow Trigger:**
```yaml
on:
  push:
    branches: [main]
  pull_request:
    branches-ignore:
      - 'l10n_v3_openjdk'
    types: [opened, reopened]
  workflow_dispatch:
```
(Current workflow only has `pull_request` and `workflow_dispatch`. Add `push` to `main`.)

2. **Commit Strategy:**
- Milestone 1: `feat: redesign layout-land home screen with glass header, premium cards, play card, quick actions`
- Milestone 2: `feat: redesign theme colors, styles, drawables, icons for V3 premium aesthetic`
- Milestone 3: `feat: redesign navigation fragments (ProfileTypeSelect, MainMenu, FastClient) with premium animations`
- Milestone 4: `feat: implement profile-based download sections in ProfileEditorFragment`
- Milestone 5: `feat: redesign ProgressLayout with glass design and shimmer animation`
- Milestone 6: `feat: update branding from V2 to V3 across all files`
- Milestone 7: `fix: update CI workflow for push-to-main builds and V3 artifact names`
- Milestone 8: `docs: update README with V3 branding and build instructions`

3. **Push Command:**
When user says `"Push"`, execute:
```bash
git add -A
git commit -m "feat: V3 milestone [description]" || echo "No changes to commit"
git push origin main
```

---

### 2.11 Profile-Based Download System Design (New Requirement — Detailed)

**Requirement:** Remove global download buttons. Place download options inside each profile: `Profile → Mods → Download Mods`, `Profile → Resource Packs → Download Resource Packs`, `Profile → Shader Packs → Download Shader Packs`. Downloaded files go automatically into profile's `mods/`, `resourcepacks/`, `shaderpacks/` folders. No profile selection dialog.

**Plan (Design Only — Implementation in Phase 2):**

1. **Profile Editor Redesign (`ProfileEditorFragment`):**
   - Current `ProfileEditorFragment` layout (`fragment_profile_editor.xml`) redesigned with tab navigation (`TabLayout` or segmented control) for: `General`, `Mods`, `Resource Packs`, `Shaders`.
   - `General` tab: Existing fields (`name`, `lastVersionId`, `gameDir`, `javaArgs`, `icon`, `renderer`).
   - `Mods` tab: List of installed mods (`RecyclerView`) from `mods/` folder; `Download Mods` button opens `SearchModFragment` (redesigned). Downloaded `.jar` saved to profile `gameDir/mods/`.
   - `Resource Packs` tab: List of installed `.zip` from `resourcepacks/`; `Download Resource Packs` button opens new `ResourcePackSearchFragment` (may be new fragment or `SearchModFragment` with `SearchFilters.isModpack = false` and `isResourcePack = true`).
   - `Shaders` tab: List of installed shader packs (`shaderpacks/`); `Download Shader Packs` button opens new `ShaderPackSearchFragment` or `SearchModFragment` with shader filter.

2. **New Fragments (Plan — May Create or Reuse):**
   - `ProfileQuickOverlayFragment`: Compact overlay for quick profile selection/play (optional enhancement — not strictly required by user, but improves UX).
   - `DownloadDashboardFragment`: If user wants a dedicated download manager (not required by instructions, but improves organization). Plan proposes keeping `ModpackCreateFragment` as the main download entry point but redesigning it as a profile-integrated flow.

3. **Download Installation (Using Existing `ModpackInstaller` / New Wrapper):**
   - For mods (`.jar`): Currently `ModInstallFragment` handles file picker (`OpenDocumentWithExtension("jar")`). Plan proposes: `SearchModFragment` (for searching) + direct download to profile folder (instead of global install). The download mechanism (`ModItemAdapter` click → `ModDetailFragment` → version selection → download) must be adapted to save to profile-specific folder.
   - For resource packs (`.zip`): Same mechanism, save to `resourcepacks/`.
   - For shader packs (`.zip`): Same mechanism, save to `shaderpacks/`.

4. **Implementation Approach (Plan — Not Code):**
   - Modify `ProfileEditorFragment` layout to include 3 new sections or tabs.
   - Modify `SearchModFragment` to accept a `Bundle` argument specifying target profile key (`profile_key` from `LauncherProfiles`) and target folder (`mods`, `resourcepacks`, `shaderpacks`).
   - Modify `ModpackInstaller` or create `ProfileModInstaller` that accepts profile key and saves to profile `gameDir` subfolder.
   - Modify `ModItemAdapter` click handler to pass profile info through `Bundle`.
   - Remove or redesign `ModpackCreateFragment`: Instead of separate profile creation screen for downloads, the download buttons should live inside `ProfileEditorFragment`. `ModpackCreateFragment` can be kept as a quick-access screen (`Install Modpack` from file + `Browse Modpacks`) but redesigned with premium glass cards.

---

### 2.12 New Layout Files (Plan — Not Created Yet)

**Complete List of New / Modified Layout Files for Phase 2:**

**Modified (`layout-land/` — rebuilt):**
- `activity_pojav_launcher.xml` — rebuilt from scratch (glass header, fragment container, play card, quick actions bar, progress bar)

**Modified (`layout/` — redesigned):**
- `fragment_profile_type.xml` — premium card design, glass buttons, new subtitle
- `fragment_profile_editor.xml` — new tabs/sections (General, Mods, Resource Packs, Shaders)
- `fragment_main_menu.xml` — redesigned with premium cards, glass header
- `fragment_fastclient_home.xml` — redesigned with premium aesthetic
- `fragment_modpack_builder.xml` — already removed; no new version needed unless builder restored
- `fragment_create_modpack_profile.xml` — redesigned (`ModpackCreateFragment`) with glass card buttons (`Browse Modpacks`, `Import Modpack`, `Search Mods`)
- `fragment_search_mod.xml` (`SearchModFragment`) — redesigned list cards with premium style
- `fragment_mod_detail.xml` — redesigned detail card
- `fragment_version_picker.xml` — premium version selection list
- `fragment_settings_fastclient.xml` — redesigned settings cards

**New (`layout/` — may be created):**
- `fragment_profile_quick_overlay.xml` — optional quick profile overlay (if implemented)
- `fragment_download_dashboard.xml` — optional download manager (if implemented)
- `item_profile_card.xml` — profile card item for lists (`ProfileTypeSelectFragment` or `MainMenuFragment`)
- `item_mod_list.xml` — redesigned mod list item (`ManageModsFragment`, `ModInstallFragment`)
- `item_resource_pack.xml` — resource pack item (new or redesigned)
- `item_shader_pack.xml` — shader pack item (new or redesigned)
- `item_version_row.xml` — redesigned version selector row
- `dialog_ripples_premium.xml` — premium ripple effect drawable (XML)
- `bg_premium_cosmic_gradient.xml` — new gradient drawable
- `bg_glass_card_premium.xml` — new glass card drawable
- `bg_glass_header.xml` — new glass header drawable
- `bg_glass_bar.xml` — new glass bar drawable
- `bg_ripple_premium.xml` — new ripple drawable
- `pulse_animation.xml` — updated pulse animation (`duration` `1500ms`)

---

### 2.13 Resources to Update

**Colors (`values/colors.xml`):**
- Update `neon_green`, `background_app`, `background_overlay`, `primary_text`, `secondary_text`, `divider`, add `glass_card_bg`, `glass_card_border`, `premium_rose`, `premium_cyan`

**Dimensions (`values/dimens.xml`):**
- Add new spacing scale (`_4sdp`, `_6sdp`, `_8sdp`, `_10sdp`, `_12sdp`, `_16sdp`, `_20sdp`, `_24sdp`, `_28sdp`, `_32sdp`, `_36sdp`, `_40sdp`, `_48sdp`, `_56sdp`, `_64sdp`)
- Update card sizes (`play_card_width`, `play_card_height`, `quick_action_height`)

**Attributes (`values/attrs.xml`):**
- Update `bgMainDrawable`, `cardCornerRadius`, `glassOpacity`

**Strings (`values/strings.xml`):**
- All branding updates (`CS Launcher V3`)
- Any new labels for redesigned sections (`Download Mods`, `Resource Packs`, `Shader Packs`, `Profile`, `Version`, `RAM`)

**Themes (`values/styles.xml`):**
- Redesign all `AppTheme*` presets
- Update `AppTheme` with new colors, fonts, window background

**Animations (`res/anim/`):**
- Update `pulse_animation.xml`
- Add new transition animations: `fade_in_slide_right.xml`, `slide_in_right.xml`, `slide_out_right.xml`, `fade_in_slide_up.xml`

---

### 2.14 Performance Impact — Detailed Assessment

| Component | Change | Performance Impact |
|---|---|---|
| Root background (`bg_premium_cosmic_gradient`) | Linear gradient (`#060A14` → `#0A152A` → `#060A14`) | Minimal — GPU-rendered gradient, single layer |
| Glass cards (`bg_glass_card_premium`) | `#12FFFFFF` solid + `#1AFFFFFF` stroke (`1.5dp`) | Low — simple shape drawable, no complex gradients |
| Glass header (`bg_glass_header`) | `#0DFFFFFF` solid + `#1AFFFFFF` bottom border | Low — same as card |
| Continuous pulse (`pulse_animation`) | `scale` + `alpha`, `duration` `1500ms`, `infinite` | Very Low — GPU-accelerated transforms |
| Fragment transitions (`fade_in_slide_*`) | `alpha` + `translateX`/`Y`, `duration` `200ms`-`300ms` | Low — short duration, GPU-accelerated |
| Card entrance (`staggered fade`) | `alpha` `0` → `1`, `translateY` `30f` → `0`, `duration` `400ms`, `delay` `40ms` | Low — only played once on fragment creation |
| Image caching (`IconCacheJanitor`, `ReadFromDiskTask`) | Unchanged | None — preserved |
| Download pipeline (`ProgressKeeper`) | Unchanged | None — preserved |
| Thread pool (`sExecutorService`) | Unchanged | None — preserved |

---

### 2.15 GitHub Actions Fix Plan

**Current Issue:** Workflow only triggers on `pull_request` and `workflow_dispatch` — not on `push` to `main`. User requires automatic build on every `main` push.

**Fix (`.github/workflows/android.yml`):**
```yaml
on:
  push:
    branches: [main]
  pull_request:
    branches-ignore:
      - 'l10n_v3_openjdk'
    types: [opened, reopened]
  workflow_dispatch:
```

**Additional Fix:** Ensure build produces `CS-LAUNCHER-V3.apk` with updated branding (`resValue` in `build.gradle` updates app name, which affects APK manifest label but not filename). Plan proposes updating artifact filenames in workflow:
- `out/CS-LAUNCHER-V3.apk` (instead of `CS-LAUNCHER-V2.apk`)
- `out/CS-LAUNCHER-V3.md5`
- `out/CS-LAUNCHER-V3.sha256`
- Release tag: `v3.${{ github.run_number }}` (instead of `v2.`)
- Release name: `CS Launcher V3 — Build ...`

**Stability Measures:**
- Keep `cache-read-only: ${{ github.event_name == 'pull_request' }}`
- Keep `validate-wrappers: false` (if needed)
- Maintain all build steps: checkout, JDK 21, JRE 8 download, Gradle setup, signing, language list update, release APK, debug APK, no-runtime APK, APK size report, artifact upload, release creation.

---

### 2.16 Implementation Milestones — Ordered Sequence

**Phase 1 (Planning):** ✅ This document.

**Phase 2 — Implementation (Only Starts After User Approval):**

**Milestone 1 — Branding Update (Low Risk, Foundation):**
- Update `strings.xml` (all V2 → V3)
- Update `README.md`
- Update `build.gradle` (`resValue` branding)
- Update `.github/workflows/android.yml` (name + artifacts + trigger fix)
- Update `.github/` issue templates, funding, stale, etc.
- **Commit:** `feat: V3 branding — all text and workflow references updated`
- **Push:** Only if user says `"Push"`.

**Milestone 2 — Theme Redesign (Visual Foundation):**
- Redesign `colors.xml` (new premium dark, glass colors, cyan accent)
- Redesign `styles.xml` (`AppTheme`, presets)
- Create new drawables (`bg_premium_cosmic_gradient`, `bg_glass_*`, ripple)
- Regenerate `ic_launcher` assets (`mipmap-*`) — requires external design or AI generation; plan assumes external replacement file will be added to workspace.
- Update `values/dimens.xml` (new spacing scale if needed)
- **Commit:** `feat: V3 premium theme — colors, styles, glass drawables, new icon assets`
- **Push:** Only on `"Push"`.

**Milestone 3 — Layout Redesign (`layout-land/activity_pojav_launcher.xml`):**
- Rebuild `layout-land/activity_pojav_launcher.xml` (glass header, fragment container, play card, quick actions bar, progress bar)
- Rebuild `layout/activity_pojav_launcher.xml` (portrait version — must also be redesigned for consistency, though user emphasized `layout-land/`)
- **Commit:** `feat: V3 home screen redesign — premium glass layout and structure`
- **Push:** Only on `"Push"`.

**Milestone 4 — Fragment Layout Redesign (UI Components):**
- Redesign `fragment_profile_type.xml` (premium profile cards)
- Redesign `fragment_profile_editor.xml` (new tabs/sections for profile-based downloads)
- Redesign `fragment_main_menu.xml` (premium main menu)
- Redesign `fragment_fastclient_home.xml`
- Redesign `fragment_create_modpack_profile.xml` (glass buttons, premium style)
- Redesign `fragment_search_mod.xml` (premium search results)
- Redesign `fragment_mod_detail.xml`
- Redesign `fragment_version_picker.xml`
- Redesign `fragment_settings_fastclient.xml`
- **Commit:** `feat: V3 fragment layouts — profile cards, editor tabs, search, settings`
- **Push:** Only on `"Push"`.

**Milestone 5 — Animation System:**
- Update `res/anim/` files (`pulse_animation.xml`, new transition animations)
- Add `res/drawable/bg_ripple_premium.xml`
- Verify all new animations in `LauncherActivity`, fragment transactions, `ProfileTypeSelectFragment`
- **Commit:** `feat: V3 premium animations — pulse, ripple, glass transitions, staggered entrances`
- **Push:** Only on `"Push"`.

**Milestone 6 — Profile-Based Download Integration:**
- Modify `ProfileEditorFragment.java` (add new sections for Mods, Resource Packs, Shader Packs; wire download buttons)
- Modify `ProfileTypeSelectFragment.java` (redesign UI — cards, subtitle, glass style — logic preserved)
- Modify `SearchModFragment.java` (accept profile key and target folder in `Bundle`)
- Modify `ModItemAdapter.java` (pass profile info through click handlers)
- Modify `ModpackInstaller.java` or create `ProfileModInstaller` (install to profile subfolder)
- Modify `ModpackCreateFragment.java` (redesign buttons; keep `button_browse_modpacks` and `button_import_modpack` — no removal of download feature)
- Create new `ResourcePackSearchFragment` / `ShaderPackSearchFragment` (if needed) or reuse `SearchModFragment`
- **Commit:** `feat: V3 profile-based download system — per-profile mods, resource packs, shader packs`
- **Push:** Only on `"Push"`.

**Milestone 7 — Performance Optimization & Cleanup:**
- Verify no memory leaks (no new `AlertDialog` leaks, listener removal preserved)
- Verify `ProgressLayout` redesign works with existing `ProgressKeeper`
- Verify `LauncherProfiles.loadAsync()` and `load()` work with redesigned profile editor
- Verify animation performance (`StrictMode` check for main-thread operations — optional)
- Verify new layouts have no `NullPointerException` (`findViewById()` calls match new IDs or preserved IDs)
- Clean up any leftover V2 references (`grep -rni` check)
- **Commit:** `perf: V3 optimization — memory, animation performance, build cleanup`
- **Push:** Only on `"Push"`.

**Milestone 8 — Final Integration & Release Prep:**
- Full build test (`./gradlew :app_pojavlauncher:assembleRelease`)
- Verify `.github/workflows/android.yml` builds successfully on `main` push
- Verify APK includes new branding (`app_name` in manifest)
- Verify release artifacts (`CS-LAUNCHER-V3.apk`, `.md5`, `.sha256`, `NO-RUNTIME.apk`)
- Update `README.md` with V3 branding, new download system explanation, build instructions
- **Commit:** `release: V3 final — branding, CI, release artifacts, documentation`
- **Push:** Only on `"Push"`.

---

### 2.17 Compatibility Verification Checklist (Before Any Implementation)

Before coding any milestone, verify:
- [ ] `LauncherActivity.java` references (`findViewById`) match either new or preserved IDs in redesigned layout.
- [ ] `PojavApplication.java` requires no changes.
- [ ] `ProgressLayout.java` (`R.id.progress_layout`) reference preserved or updated.
- [ ] `mcAccountSpinner.java` (`R.id.account_spinner`) reference preserved or updated.
- [ ] `FragmentContainerView` (`container_fragment`) ID preserved.
- [ ] `build.gradle` `resValue` updated correctly.
- [ ] `.github/workflows/android.yml` trigger updated (`push: branches: [main]`).
- [ ] `AndroidManifest.xml` unchanged (unless branding requires `label` change — `label="@string/app_name"` handles it automatically via strings).
- [ ] `LauncherProfiles` JSON format unchanged.
- [ ] `ThemeManager.java` references (`R.style.AppTheme`) preserved or updated via new `styles.xml`.

---

### 2.18 User Approval Gateway — Required Before Implementation

**Before any code changes for Milestones 1-8, this plan must be approved by the user.**

The user must confirm:
1. Design concept (`Premium Mobile Gaming Hub`, glass-morphic, dark cosmic, cyan + rose accents) approved.
2. Layout structure (`glass_header`, `fragment_container`, `play_card`, `quick_actions_bar`, `progress_bar`) approved.
3. Brand name (`CS Launcher V3`) and subtitle (`YOUR MINECRAFT. REIMAGINED.` — or alternate proposal) approved.
4. Profile-based download design (download buttons inside profile editor; no global download buttons removed from `ProfileTypeSelectFragment`) approved.
5. No removal of `ModpackCreateFragment` download buttons (`button_browse_modpacks`, `button_import_modpack`) — only redesign.
6. Animation durations and styles (`pulse_animation` `1500ms`, `DecelerateInterpolator`, `staggered fade` `40ms` delay, `scale` `0.96`) approved.
7. Performance targets (`low memory`, `60 FPS`, `reduced overdraw`) acceptable.
8. Milestone sequence approved.

Once approved, the user should say:
> **"Proceed with Milestone X"** or **"Proceed with all milestones"**

And for pushes:
> **"Push"** (triggers automatic commit + push to `main` on `CRAFT-STUDIO-LAUNCHER-.git` or configured repo).

---

### 2.19 New Download System — Detailed Implementation Approach (Plan Only — Code in Phase 2)

**Current State (`ModpackCreateFragment`):**
- `fragment_create_modpack_profile.xml`: `button_browse_modpacks` (`SearchModFragment`), `button_import_modpack` (`modpackImportLauncher` — file picker for `.zip`/`.mrpack`)
- `ProfileTypeSelectFragment`: `modded_profile_modpack` button navigates to `ModpackCreateFragment`

**New Design (Profile-Based):**
- `ProfileEditorFragment` redesigned with tabs (`General`, `Mods`, `Resource Packs`, `Shaders`).
- `ProfileTypeSelectFragment` redesigned: Each profile card shows quick info (`name`, `version`, `loader`, `mod count`, `resource pack count`). Click opens `ProfileQuickOverlayFragment` (compact overlay with `Play` button) or full `ProfileEditorFragment`.
- `ProfileEditorFragment` — `Mods` section: `RecyclerView` of installed `.jar` files. `Download Mods` button opens `SearchModFragment` with `profile_key` passed in `Bundle`.
- `SearchModFragment` — accepts `Bundle` with:
  - `SEARCH_FILTER_KEY` (existing `SearchFilters`)
  - `profile_key` (new) — profile to install into
  - `target_folder` (`"mods"`, `"resourcepacks"`, `"shaderpacks"`)
- `ModItemAdapter` — on click (`setOnItemClickListener`), creates `Bundle` with `mod_item`, `profile_key`, `target_folder`, passes to `ModVersionPickerFragment` or download handler.
- `ModpackInstaller` — new overload or wrapper (`installModpackToProfile(ModDetail, profileKey, targetFolder, File instanceDir)`) that saves to `instanceDir + "/" + targetFolder` instead of `DIR_GAME_HOME/custom_instances/<modpackName>` (for profile-based installation).
- For profile-based mod installation: Download `.jar` (mod) or `.zip` (resource/shader) to profile's `gameDir` subfolder (`mods/`, `resourcepacks/`, `shaderpacks/`). No new profile created — file added to existing profile.

**Files Affected by New Download System:**
- `ProfileEditorFragment.java` (redesign sections, wire download buttons)
- `ProfileTypeSelectFragment.java` (redesign cards, add subtitle reference if needed)
- `SearchModFragment.java` (handle new `Bundle` arguments)
- `ModItemAdapter.java` (pass profile/folder info)
- `ModpackInstaller.java` (add profile-based installation method or wrapper)
- `ModloaderInstallTracker.java` (may need to observe profile-based installations)
- `ModDetailFragment.java` (show download target — profile/folder info)
- `ProfileQuickOverlayFragment.java` (optional new fragment)
- `DownloadDashboardFragment.java` (optional new fragment)

---

### 2.20 Performance Requirements — Detailed

**Requirements (User Specified):**
- Keep memory usage low.
- Avoid unnecessary view nesting.
- Optimize animations.
- Keep rendering smooth.
- Reduce layout overdraw.
- Maintain high FPS.

**Plan Actions:**
1. **Reduced View Nesting:** Redesign `layout-land/activity_pojav_launcher.xml` to use `ConstraintLayout` with direct children (`glass_header`, `container_fragment`, `play_card`, `quick_actions_bar`, `progress_layout`) rather than nested `LinearLayout` + `FrameLayout` chains. Each major section has at most 1-2 levels of nesting.
2. **Overdraw Reduction:** Use `#060A14` (deep dark) as root background. Cards (`#12FFFFFF`) are semi-transparent but not heavily layered (no multiple overlapping semi-transparent layers). Progress bar (`#0DFFFFFF` glass) is single-layer with cyan (`#00E5FF`) progress line.
3. **Animation Optimization:** Short durations (`200ms`-`300ms` for transitions, `1500ms` for infinite pulse). `DecelerateInterpolator` (fewer frames at end). `LinearInterpolator` for pulse (constant speed, GPU-efficient).
4. **Memory Low:** No new bitmaps in layouts (only drawable XML). `ic_launcher` replacement in `mipmap/` — must be optimized PNG (`< 100KB` per density). Glass cards use vector/shape drawables (low memory). No new `RecyclerView` adapters with image loading changes (existing `ModItemAdapter` preserved, only layout updated).
5. **Rendering Smooth:** `elevation` values limited (`4dp`, `8dp`, `16dp`) to avoid excessive shadow rendering. No `clipToOutline` or complex clipping needed.
6. **High FPS:** `hardwareAccelerated="true"` preserved in manifest. `ProgressLayout` shimmer animation uses `TranslateAnimation` or `ObjectAnimator` (`translateX`), which is GPU-accelerated.

---

### 2.21 Final Deliverables — Phase 1 Plan File

This file (`plans/CS_LAUNCHER_V3_COMPLETE_PLAN.md`) serves as the single source of truth for all V3 development.

**Deliverables List (To Be Completed After Approval):**
- [ ] `plans/CS_LAUNCHER_V3_COMPLETE_PLAN.md` (this file) — approved by user
- [ ] `reports/` preserved (previous analysis intact)
- [ ] `README.md` updated (Milestone 8)
- [ ] `res/layout-land/activity_pojav_launcher.xml` rebuilt (Milestone 3)
- [ ] `res/layout/fragment_profile_type.xml` redesigned (Milestone 4)
- [ ] `res/layout/fragment_profile_editor.xml` redesigned (Milestone 4)
- [ ] `res/layout/fragment_main_menu.xml` redesigned (Milestone 4)
- [ ] `res/layout/fragment_create_modpack_profile.xml` redesigned (Milestone 4)
- [ ] `res/drawable/bg_premium_cosmic_gradient.xml` (Milestone 2)
- [ ] `res/drawable/bg_glass_*.xml` (Milestone 2)
- [ ] `res/drawable/bg_ripple_premium.xml` (Milestone 2)
- [ ] `res/anim/pulse_animation.xml` updated (Milestone 5)
- [ ] `res/anim/fade_in_slide_*.xml` (Milestone 5)
- [ ] `res/values/colors.xml` updated (Milestone 2)
- [ ] `res/values/styles.xml` redesigned (Milestone 2)
- [ ] `res/values/strings.xml` updated (Milestone 1)
- [ ] `mipmap/ic_launcher_*` regenerated (external — Milestone 2)
- [ ] `.github/workflows/android.yml` fixed (Milestone 1 / 8)
- [ ] `ProfileEditorFragment.java` redesigned (Milestone 6)
- [ ] `ProfileTypeSelectFragment.java` redesigned (Milestone 4)
- [ ] `SearchModFragment.java` updated (Milestone 6)
- [ ] `ModpackInstaller.java` updated (Milestone 6)
- [ ] `ProfileQuickOverlayFragment.java` (optional — Milestone 6)
- [ ] `DownloadDashboardFragment.java` (optional — Milestone 6)

---

## 3. USER APPROVAL REQUESTED

Before any file is modified for Phase 2 implementation:

**Please confirm the following by responding explicitly:**

1. `"Plan approved"` — Confirms this complete plan (design, architecture, milestones, compatibility) is approved.
2. `"Proceed with Milestone X"` — Starts Milestone X (e.g., `"Proceed with Milestone 1"` starts branding update).
3. `"Proceed with all milestones"` — Starts Milestones 1 through 8 sequentially.
4. `"Modify plan: [description]"` — Requests changes to this plan (design, branding, milestones, etc.).
5. `"Push"` — After a milestone is completed and committed, pushes to configured Git repo (`CRAFT-STUDIO-LAUNCHER-.git` or workspace `main`).

**No code will be written until user confirms with one of the above responses.**

---

*Plan created: 16 July 2026*  
*Analyst: Arena.ai Agent Mode*  
*Workspace: `/home/user/CS-LAUNCHER-v2`*  
*Reference Repo: `https://github.com/JoooaoooDVD/CRAFT-STUDIO-LAUNCHER-.git`*  
*Previous Modifications Intact: Modpack Builder/Generator removed, Download Pipeline preserved, Deep Analysis Report saved to `reports/`*
