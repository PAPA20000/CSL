# CS Launcher V3 — Admin Panel (Firebase)

A **single HTML file** (`admin.html`) — pure HTML/CSS/JS, no build step, no
dependencies. Firebase config is already prefilled from `google-services.json`
(apiKey + databaseURL). Real-time sync: changes appear in the launcher within
~1 second via the Firebase SDK.

## How to use

1. **Open** `admin.html` in any browser (double-click, or host on Netlify /
   GitHub Pages / any static host).
2. **Sign in** with your Firebase Auth email/password
   (Firebase console → Authentication → Email/Password → add your admin user).
3. Manage everything — announcements, notifications, sponsorship, updates.
   Every save goes straight to the Realtime Database.

## Launcher side

The launcher ships with `google-services.json`, so **Firebase Sync is ON by
default** — no setup needed. (Settings → Advanced → Firebase Sync to
disable, or paste a different Database URL.)

## Database schema (auto-created by the panel)

```
/announcements/{id}
    title, body (markdown), type ("popup"|"card"|"page"),
    pinned (bool), enabled (bool), createdAt, updatedAt
/notifications/{id}
    title, message, icon (emoji), priority ("low"|"normal"|"high"),
    expiresAt (epoch ms, 0 = never), enabled (bool), createdAt, updatedAt
/settings/sponsorshipEnabled   (bool)
/update
    version, minVersion, url, changelog (markdown), force (bool), updatedAt
```

## Security rules (recommended)

```json
{
  "rules": {
    "announcements": { ".read": true, ".write": "auth != null" },
    "notifications": { ".read": true, ".write": "auth != null" },
    "settings": { ".read": true, ".write": "auth != null" },
    "update": { ".read": true, ".write": "auth != null" }
  }
}
```

Reads are public (the launcher needs them), writes require admin login.
