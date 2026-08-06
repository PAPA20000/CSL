// ═══════════════════════════════════════════════════════════════════════
// CS Launcher V3 — Firebase configuration
// ─────────────────────────────────────────────────────────────────────────
// 1. Go to https://console.firebase.google.com → create a project
// 2. Build → Realtime Database → Create database (start in test mode)
// 3. Project settings → General → copy the "Database URL" (e.g.
//    https://cs-launcher-v3-default-rtdb.firebaseio.com)
// 4. Project settings → Your apps → Web app → copy "apiKey"
// 5. Paste both below. That's it — the admin panel and the launcher share
//    these two values (the launcher reads them from its Advanced settings).
// ═══════════════════════════════════════════════════════════════════════
const FIREBASE_CONFIG = {
    apiKey: "YOUR_FIREBASE_WEB_API_KEY",
    databaseURL: "https://YOUR-PROJECT-default-rtdb.firebaseio.com"
};
