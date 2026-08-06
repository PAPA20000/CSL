# CS Launcher V3 — Admin Panel (Firebase)

Pure HTML/CSS/JS admin panel + launcher real-time sync via Firebase
**Realtime Database** (REST). No app update needed — everything the admin
changes appears in the launcher within seconds.

## Files

| File | Purpose |
|---|---|
| `index.html` | Admin panel UI (login, dashboard, announcements, notifications, sponsorship, updates) |
| `admin.css` | Panel styles (launcher's glass/violet theme) |
| `admin.js` | All logic — Firebase Auth + Realtime Database REST, 4s polling |
| `config.js` | **Your Firebase config (apiKey + databaseURL)** |

## Setup (once)

1. **Firebase project** → https://console.firebase.google.com
2. **Realtime Database** → Create database → *test mode* (then lock down with rules below)
3. **Authentication** → Sign-in method → enable **Email/Password** → add your admin user
4. **Web app** → Project settings → Your apps → `</>` → copy **apiKey**
5. Edit `config.js` → paste `apiKey` + `databaseURL`
6. Deploy: GitHub Pages / Netlify / any static host — or open `index.html` locally
7. Sign in with the admin email/password → manage everything

## Launcher side

In the launcher: **Settings → Advanced → Firebase Sync** — enable it and
paste the same **database URL** (no API key needed in the launcher).

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
