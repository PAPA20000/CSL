# CS LAUNCHER — PROFILE-BASED DOWNLOAD SYSTEM — TECHNICAL IMPLEMENTATION PLAN
## Status: Planning — NO CODE YET — AWAITING USER APPROVAL

**Priority:** HIGHEST — All other design work (home screen, theme, animations) deferred until this system is complete.
**Repo:** `/home/user/CS-LAUNCHER-v2` (workspace) → `https://github.com/rohit-45-95/CRAFT-STUDIO-LAUNCHER-.git` (remote main)
**Branch:** `main`
**Author:** Arena Agent
**Date:** 16 July 2026

---

## 1. CURRENT STATE ANALYSIS (Verified)

### 1.1 Existing Download Pipeline (Intact — Must Remain Safe)
- `SearchModFragment.java` (`fragments/SearchModFragment.java`) — search results, filter support
- `ModItemAdapter.java` (`fragments/ModItemAdapter.java`) — item click passes to `ModDetailFragment`
- `ModDetailFragment.java` (`fragments/ModDetailFragment.java`) — version selection, download button
- `ModVersionPickerFragment.java` (`fragments/ModVersionPickerFragment.java`) — version list
- `ModVersionListFragment.java` (`fragments/ModVersionListFragment.java`) — version data
- `ModDownloadHelper.java` (`fragments/ModDownloadHelper.java`) — download logic
- `ModpackCreateFragment.java` (`fragments/ModpackCreateFragment.java`) — current global download entry (`button_browse_modpacks` → `SearchModFragment`, `button_import_modpack` → file picker)
- `ModpackInstaller.java` (`modloaders/modpacks/api/ModpackInstaller.java`) — installs `.cf` / `.jar` to `Tools.DIR_GAME_HOME/custom_instances/<modpackName>`
- `ModloaderInstallTracker.java` — tracks installation progress
- `ProgressLayout.java` — progress display (`ProgressLayout.INSTALL_MODPACK`)

### 1.2 Existing Profile System (Intact — Must Remain Safe)
- `ProfileEditorFragment.java` (`fragments/ProfileEditorFragment.java`) — profile settings (`name`, `gameDir`, `lastVersionId`, `javaArgs`, `icon`, `renderer`, `defaultRuntime`, `defaultRenderer`)
- `ProfileTypeSelectFragment.java` (`fragments/ProfileTypeSelectFragment.java`) — profile selection (`vanilla_profile`, `optifine_profile`, `modded_profile_fabric`, etc.)
- `LauncherProfiles.java` (`profiles/LauncherProfiles.java`) — JSON persistence (`launcher_profiles.json`)
- `MinecraftProfile.java` (`profiles/MinecraftProfile.java`) — profile data model (`name`, `gameDir`, `lastVersionId`, `icon`, `lastVersionId`, etc.)
- `ProfileIconCache.java` (`profiles/ProfileIconCache.java`) — icon caching

### 1.3 Current Download Workflow (What User Does NOT Want)
```
Download Mod (ModpackCreateFragment / SearchModFragment)
    ↓
Select Profile (dialog or manual selection after download)
    ↓
Install (ModpackInstaller installs to custom_instances/ or global mods/)
```
**Problem:** Download is global; profile selection is separate or missing; files do not go to profile-specific folders automatically.

---

## 2. NEW WORKFLOW (What User Wants)

```
Profile (ProfileEditorFragment — open profile first)
    ↓
Mods / Resource Packs / Shader Packs (tabs/sections inside profile)
    ↓
Download Mods / Download Resource Packs / Download Shader Packs
    ↓
Automatic Installation into:
    <Profile Game Directory>/mods/
    <Profile Game Directory>/resourcepacks/
    <Profile Game Directory>/shaderpacks/
```
**Rules:**
- NO profile selection dialog after download.
- NO extra confirmation.
- Currently edited profile is always the target.
- Each profile manages its own `mods/`, `resourcepacks/`, `shaderpacks/` independently.

---

## 3. ARCHITECTURE — HOW NEW SYSTEM WORKS

### 3.1 Profile as Context
Every download operation must receive the current profile key (`mProfileKey` from `ProfileEditorFragment`) and the profile's `gameDir` (`mTempProfile.gameDir`).

**Profile info passing mechanism:**
- `ProfileEditorFragment` holds `mProfileKey` (String) and `mTempProfile` (`MinecraftProfile`).
- When user clicks `Download Mods`, `ProfileEditorFragment` creates a `Bundle` with:
  - `"profile_key"` → `mProfileKey`
  - `"game_dir"` → `mTempProfile.gameDir`
  - `"target_folder"` → `"mods"` (or `"resourcepacks"` / `"shaderpacks"`)
- `Bundle` passed to `SearchModFragment` via `FragmentTransaction` arguments or `newInstance(Bundle)`.
- `SearchModFragment` reads arguments and passes profile info through to `ModItemAdapter`.

### 3.2 Installation — Automatic Profile Folder Installation
Current `ModpackInstaller.installModpack()` saves to:
```
new File(Tools.DIR_GAME_HOME, "custom_instances/" + modpackName)
```

New mechanism for profile-based download:
- For `.jar` (mods): Save directly to `File(profileGameDir, "mods/")` — no `custom_instances` wrapper.
- For `.zip` (resource packs): Save to `File(profileGameDir, "resourcepacks/")`.
- For `.zip` (shader packs): Save to `File(profileGameDir, "shaderpacks/")`.

If `profileGameDir` is null or empty, fall back to existing behavior (`custom_instances/`).

### 3.3 Profile Editor Redesign (Layout + Fragment)
`ProfileEditorFragment.java` (`fragment_profile_editor.xml`) redesign:
- Add tab navigation (`TabLayout` or segmented control): `General`, `Mods`, `Resource Packs`, `Shaders`.
- `General`: Existing settings (`name`, `gameDir`, `version`, `javaArgs`, `icon`, `runtime`, `renderer`).
- `Mods`: `RecyclerView` of installed `.jar` files (`mods/`); button `Download Mods`; button `Import Mod` (existing `mModPicker`).
- `Resource Packs`: `RecyclerView` of installed `.zip` (`resourcepacks/`); button `Download Resource Packs`; button `Import Resource Pack` (existing `mResourcePackPicker`).
- `Shaders`: `RecyclerView` of installed `.zip` (`shaderpacks/`); button `Download Shader Packs`; button `Import Shader Pack` (existing `mShaderPackPicker`).

---

## 4. JAVA CLASSES THAT MUST CHANGE

| Class | File Path | Change Type | Details |
|---|---|---|---|
| `ProfileEditorFragment` | `fragments/ProfileEditorFragment.java` | Modify | Add `Bundle` creation for profile context; redesign `bindViews()` for new tabs/sections; wire `mModPicker`, `mResourcePackPicker`, `mShaderPackPicker` to pass profile info. |
| `ProfileTypeSelectFragment` | `fragments/ProfileTypeSelectFragment.java` | Modify | Ensure `ProfileEditorFragment` launched with correct profile key; no removal of download buttons required here (downloads now live in `ProfileEditorFragment`). |
| `SearchModFragment` | `fragments/SearchModFragment.java` | Modify | Read `Bundle` arguments (`profile_key`, `game_dir`, `target_folder`); pass to `ModItemAdapter`. |
| `ModItemAdapter` | `fragments/ModItemAdapter.java` | Modify | Receive profile info from `SearchModFragment`; pass through click handler to `ModDetailFragment`. |
| `ModDetailFragment` | `fragments/ModDetailFragment.java` | Modify | Receive profile info; pass to download mechanism. |
| `ModpackInstaller` | `modloaders/modpacks/api/ModpackInstaller.java` | Modify / Add overload | Add `installModpackToProfile(ModDetail, profileKey, targetFolder, File instanceDir)` or modify existing to accept profile `gameDir`. |
| `ModpackCreateFragment` | `fragments/ModpackCreateFragment.java` | Modify / Keep | Keep `button_browse_modpacks` and `button_import_modpack`; redesign with premium glass cards if needed; no removal of download flow (only redesign, per user instruction). |
| `ProfileQuickOverlayFragment` | New: `fragments/ProfileQuickOverlayFragment.java` | Create (optional) | Compact profile overlay if needed — user did not explicitly require this, but improves UX. If time permits, create. Otherwise skip. |
| `DownloadDashboardFragment` | New: `fragments/DownloadDashboardFragment.java` | Create (optional) | Dedicated download manager — optional enhancement. Skip if user only wants profile-integrated flow. |

---

## 5. XML LAYOUTS THAT MUST CHANGE

| File | Path | Change Type | Details |
|---|---|---|---|
| `fragment_profile_editor` | `res/layout/fragment_profile_editor.xml` | Redesign | Add `TabLayout` / `LinearLayout` tabs (`General`, `Mods`, `Resource Packs`, `Shaders`); add `RecyclerView` for installed items; keep existing fields in `General`. |
| `fragment_profile_type` | `res/layout/fragment_profile_type.xml` | Modify (optional) | User did NOT request redesign of home screen; only profile selection. Keep current design unless profile download requires new cards. No change required unless needed for navigation. |
| `fragment_create_modpack_profile` | `res/layout/fragment_create_modpack_profile.xml` | Modify (optional) | Redesign buttons (`Browse Modpacks`, `Import Modpack`) with glass style if redesigning; DO NOT remove download functionality — user explicitly said keep. |
| `fragment_search_mod` | `res/layout/fragment_search_mod.xml` | Modify (optional) | Update if profile info needs display; otherwise keep. |
| `fragment_mod_detail` | `res/layout/fragment_mod_detail.xml` | Modify | Show profile target info if needed. |
| `item_profile_card` | `res/layout/item_profile_card.xml` | Create (optional) | If new profile cards needed — skip unless redesigning profile selection. |

---

## 6. PROFILE INFORMATION PASSING — EXACT MECHANISM

### 6.1 From ProfileEditorFragment to Search/Download
```java
// In ProfileEditorFragment.java
Bundle args = new Bundle();
args.putString("profile_key", mProfileKey);       // String — profile identifier
args.putString("game_dir", mTempProfile.gameDir);  // String — profile's game directory
args.putString("target_folder", "mods");          // "mods" | "resourcepacks" | "shaderpacks"

SearchModFragment searchFragment = new SearchModFragment();
searchFragment.setArguments(args);
```

### 6.2 From SearchModFragment to Adapter
`SearchModFragment` reads `getArguments()` and passes to adapter constructor or method:
```java
Bundle args = getArguments();
String profileKey = args != null ? args.getString("profile_key") : null;
String gameDir = args != null ? args.getString("game_dir") : null;
String targetFolder = args != null ? args.getString("target_folder", "mods") : "mods";
```

### 6.3 From Adapter Click to Download/Installation
`ModItemAdapter` click handler creates `Bundle` with profile info and passes to `ModDetailFragment`:
```java
Bundle detailArgs = new Bundle();
detailArgs.putString("profile_key", profileKey);
detailArgs.putString("game_dir", gameDir);
detailArgs.putString("target_folder", targetFolder);
detailArgs.putSerializable("mod_detail", modDetail);
```

`ModDetailFragment` reads `profile_key`, `game_dir`, `target_folder`, and passes to `ModpackInstaller` or download mechanism.

---

## 7. INSTALLATION MECHANISM — AUTOMATIC PROFILE FOLDER

### 7.1 For Mods (`.jar`)
- Source: Download URL from `ModDetailFragment.versionUrls[selectedVersion]`
- Destination: `new File(profileGameDir, "mods/")` — file saved directly here.
- No `ModpackInstaller.installModpack()` wrapper needed for simple `.jar` downloads; use direct file download via `DownloadUtils.downloadFileMonitored()`.
- If `.cf` / `.mrpack` format used: `ModpackInstaller` must extract/install to profile `gameDir/mods/` instead of `custom_instances/`.

### 7.2 For Resource Packs (`.zip`)
- Source: Resource pack download URL (reuse `SearchModFragment` with filter or new `ResourcePackSearchFragment`)
- Destination: `new File(profileGameDir, "resourcepacks/")`
- Installation: Save `.zip` directly; no extraction needed (Minecraft loads `.zip` from `resourcepacks/`).

### 7.3 For Shader Packs (`.zip`)
- Source: Shader pack download URL
- Destination: `new File(profileGameDir, "shaderpacks/")`
- Installation: Save `.zip` directly; no extraction.

---

## 8. COMPATIBILITY ISSUES

### 8.1 Existing Profiles
- Existing `launcher_profiles.json` profiles do not have `mods/`, `resourcepacks/`, `shaderpacks/` subfolders by default. System must create subfolders automatically (`File.mkdirs()`) when first download occurs.
- Existing downloads in global `custom_instances/` remain untouched; profile-based downloads create new folders separately.

### 8.2 Profile Editor Layout (`fragment_profile_editor.xml`)
- Adding `TabLayout` or new sections must not break existing `bindViews()` logic (`mSaveButton`, `mDeleteButton`, `mControlSelectButton`, etc.).
- `ProfileEditorFragment.java` uses `super(R.layout.fragment_profile_editor)`; layout redesign must preserve same `R.id.*` references or update `bindViews()` accordingly.

### 8.3 SearchModFragment Filters
- `SearchModFragment` uses `SearchFilters` (`SearchFilters.isModpack`, etc.). Adding profile info must not break filter logic.

### 8.4 ModpackCreateFragment
- User explicitly said: DO NOT REMOVE `button_browse_modpacks` and `button_import_modpack`. These must remain.
- Redesign only allowed; functionality preserved.

---

## 9. EXISTING DOWNLOADS — HOW THEY CONTINUE TO WORK

- Global `ModpackCreateFragment` (`Browse Modpacks`, `Import Modpack`) continues to work.
- Downloads from `ModpackCreateFragment` can either:
  a) Remain global (current behavior — save to `custom_instances/`), OR
  b) Be updated to ask for profile if user wants profile-based flow.
- Plan proposes: Keep `ModpackCreateFragment` as quick-access entry; when download completes, offer user option to install to current profile (optional enhancement) or keep existing behavior. User wants profile-based; so download buttons inside `ProfileEditorFragment` become primary flow.
- Existing `.cf` / `.jar` installations from previous versions remain in `custom_instances/`; new profile-based downloads go to profile folders separately.

---

## 10. IMPLEMENTATION SEQUENCE (Ordered — Only After User Confirms Plan)

**Phase 1 — Analysis (This Document):** ✅ Complete — Committed to `plans/PROFILE_DOWNLOAD_SYSTEM_PLAN.md`

**Phase 2 — Profile-Based Download Implementation:**

**Step 1:** Modify `ProfileEditorFragment.java` and `fragment_profile_editor.xml`
- Add tabs/sections (`General`, `Mods`, `Resource Packs`, `Shaders`)
- Wire download buttons (`Download Mods`, etc.) to create `Bundle` with profile info

**Step 2:** Modify `ProfileTypeSelectFragment.java` (if needed for navigation to new `ProfileEditorFragment` sections)
- Ensure profile selection navigates to `ProfileEditorFragment` with correct `Bundle`

**Step 3:** Modify `SearchModFragment.java`
- Read `Bundle` arguments; pass profile info to adapter

**Step 4:** Modify `ModItemAdapter.java`, `ModDetailFragment.java`
- Pass profile info through click/download chain

**Step 5:** Modify `ModpackInstaller.java` or add `ProfileModInstaller`
- Add profile-based installation method

**Step 6:** Create new fragments if needed (`ProfileQuickOverlayFragment`, `ResourcePackSearchFragment`, `ShaderPackSearchFragment`) — optional; skip if not required.

**Step 7:** Verify compatibility (`ProfileEditorFragment` layout, existing profiles, `ModpackCreateFragment` preserved)

**Step 8:** Build (`./gradlew assembleRelease`), verify profile download works (`Profile → Mods → Download Mods` → saves to `gameDir/mods/`)

---

## 11. HOME SCREEN REDESIGN — REMOVED FROM CURRENT IMPLEMENTATION

**Status:** DEFERRED — NOT PART OF CURRENT PLAN
- `layout-land/activity_pojav_launcher.xml` redesign — REMOVED from this implementation phase
- `layout/activity_pojav_launcher.xml` redesign — REMOVED
- Theme redesign (`colors.xml`, `styles.xml`) — REMOVED (only profile download changes)
- Animation redesign — REMOVED
- Glass drawables (`bg_glass_*`) — REMOVED (only profile download changes)
- Branding (`README.md`, `build.gradle`, `.github/workflows`) — REMOVED (only profile download changes)

**User explicitly instructed:** Stop focusing on Home Screen redesign; work on Profile-Based Download System first.

---

## 12. GIT COMMIT / PUSH INSTRUCTIONS (After Plan Approval)

Once user confirms `"Proceed with Profile-Based Download System"` or `"Plan approved"`:

```bash
git add plans/PROFILE_DOWNLOAD_SYSTEM_PLAN.md
git commit -m "plan: profile-based download system — technical analysis, architecture, class/XML changes, installation mechanism, compatibility"
git push origin main
```

**No code written until user approves this plan.**

---

*Plan updated: 16 July 2026*  
*Workspace: `/home/user/CS-LAUNCHER-v2`*  
*Remote: `https://github.com/rohit-45-95/CRAFT-STUDIO-LAUNCHER-.git`*  
*Previous fixes preserved: `e70f3d6` (layout restore), `88b580c` (CI fix), `62adf64` (build.gradle fix), `7320c0e` (build.gradle fix)*
