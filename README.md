# CS Launcher V3

CS Launcher V3 (`com.craftstudio.cslauncherv3`) is a Minecraft: Java Edition launcher for Android devices. It is built on the open-source foundations of Boardwalk, PojavLauncher, and Amethyst, optimized for high performance, modern landscape layouts, and real-time synchronization.

---

## Key Features

- **Landscape-First Design**: Optimized horizontal UI across all screens, Settings, and authentication pages.
- **Pure Dark Graphite Theme**: Professional true neutral dark grey (`#121212` to `#333333`) and platinum/silver typography.
- **Account Authentication**:
  - **Offline Accounts**: Local Yggdrasil Server integration with automatic skin proxying from Mojang and Ely.by.
  - **Ely.by Accounts**: Direct authentication with authlib-injector support.
  - **Microsoft / Mojang Accounts**: Full standard OAuth login support.
- **Mod & Resource Support**:
  - In-app search and download for Resource Packs, Shaders, and Modpacks (Forge, Fabric, Quilt).
  - 1-Click Performance Setup presets (Low-End Mobile, Medium Mobile, High-End Mobile).
- **Push Notifications (FCM)**:
  - Real-time Firebase Cloud Messaging integration for launcher updates, server news, and announcements.
  - Automatic topic subscription (`launcher_updates`, `launcher_announcements`, `server_news`, `maintenance`).
- **In-Launcher Updater**:
  - Direct background APK downloading from GitHub Releases with native Android package installation.

---

## Building from Source

### Requirements

- **JDK**: OpenJDK 21
- **Android SDK**: API 34 (Build-Tools 34.0.0, NDK 27.3.13750724, CMake 3.22.1)
- **Minimum Android SDK**: Android 8.0 (API 26)

### Build Command

```bash
./gradlew :app_pojavlauncher:assembleRelease -Dorg.gradle.jvmargs="-Xmx1200m -Dfile.encoding=UTF-8" --max-workers=1 -Pandroid.enableR8.fullMode=true --no-daemon
```

The compiled APK will be generated at:
`app_pojavlauncher/build/outputs/apk/release/app_pojavlauncher-release-unsigned.apk`

---

## Push Notification Testing (Debug)

To view and copy the Firebase Cloud Messaging (FCM) token on mobile devices without ADB access:

1. Open **CS Launcher V3** and navigate to the **About** screen from the sidebar.
2. Tap the **App Version** chip (`Version 3.x.x`) **20 times**.
3. Once unlocked, a modal dialog displays the current FCM registration token and a **Copy Token** button.

---

## License

This project is licensed under the GNU General Public License v3.0 (GPL-3.0).
