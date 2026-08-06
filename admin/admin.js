// ═══════════════════════════════════════════════════════════════════════
// CS Launcher V3 — Admin Panel logic
// Pure HTML/CSS/JS + Firebase REST (Auth + Realtime Database).
// Real-time: polls every 4s; changes written straight to Firebase, the
// launcher picks them up instantly via its SSE stream.
// ═══════════════════════════════════════════════════════════════════════
"use strict";

const DB = FIREBASE_CONFIG.databaseURL.replace(/\/$/, "");
let idToken = null;
let pollTimer = null;
let cached = { announcements: {}, notifications: {}, settings: {}, update: null };

// ── Tiny markdown → HTML (GitHub-flavored subset) ────────────────────────
function md(src) {
    if (!src) return "";
    let s = String(src)
        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    // code blocks first
    s = s.replace(/```([\s\S]*?)```/g, (m, c) => `<pre><code>${c.trim()}</code></pre>`);
    // headings
    s = s.replace(/^### (.*)$/gm, "<h3>$1</h3>");
    s = s.replace(/^## (.*)$/gm, "<h2>$1</h2>");
    s = s.replace(/^# (.*)$/gm, "<h1>$1</h1>");
    // blockquote
    s = s.replace(/^&gt; (.*)$/gm, "<blockquote>$1</blockquote>");
    // lists
    s = s.replace(/^[-*] (.*)$/gm, "<li>$1</li>");
    s = s.replace(/(<li>[\s\S]*?<\/li>)/g, m => `<ul>${m}</ul>`);
    // inline
    s = s.replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>");
    s = s.replace(/\*([^*]+)\*/g, "<i>$1</i>");
    s = s.replace(/`([^`]+)`/g, "<code>$1</code>");
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    s = s.replace(/\n/g, "<br>");
    return s;
}

// ── Firebase REST helpers ────────────────────────────────────────────────
async function fb(path, method, body) {
    const url = `${DB}${path}.json${idToken ? `?auth=${encodeURIComponent(idToken)}` : ""}`;
    const res = await fetch(url, {
        method: method || "GET",
        headers: body ? { "Content-Type": "application/json" } : undefined,
        body: body ? JSON.stringify(body) : undefined
    });
    if (!res.ok) throw new Error(`Firebase ${res.status} on ${path}`);
    const text = await res.text();
    return text ? JSON.parse(text) : null;
}

async function login(email, password) {
    const res = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${FIREBASE_CONFIG.apiKey}`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password, returnSecureToken: true })
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error ? data.error.message : "Login failed");
    idToken = data.idToken;
}

// ── Views ────────────────────────────────────────────────────────────────
function showView(name) {
    document.querySelectorAll(".view").forEach(v => v.classList.add("hidden"));
    document.getElementById("view-" + name).classList.remove("hidden");
    document.querySelectorAll(".nav-item").forEach(n => n.classList.toggle("active", n.dataset.view === name));
}

async function refreshAll() {
    try {
        cached.announcements = (await fb("/announcements")) || {};
        cached.notifications = (await fb("/notifications")) || {};
        cached.settings = (await fb("/settings")) || {};
        cached.update = await fb("/update");
        renderDashboard();
        renderAnnouncements();
        renderNotifications();
        renderSponsorship();
        renderUpdate();
    } catch (e) { console.warn("refresh", e); }
}

// ── Dashboard ────────────────────────────────────────────────────────────
function renderDashboard() {
    const anns = Object.values(cached.announcements || {});
    const nots = Object.values(cached.notifications || {});
    const enabled = countEnabled(anns), nEnabled = countEnabled(nots);
    document.getElementById("stat-ann").textContent = `${enabled}/${anns.length}`;
    document.getElementById("stat-notif").textContent = `${nEnabled}/${nots.length}`;
    document.getElementById("stat-sponsor").textContent = cached.settings.sponsorshipEnabled ? "ON" : "OFF";
    document.getElementById("stat-version").textContent = cached.update && cached.update.version ? cached.update.version : "—";
    const live = document.getElementById("live-feed");
    live.innerHTML = "";
    const items = [
        ...anns.filter(a => a.enabled !== false).slice(0, 3).map(a => `<div class="card"><div class="head"><b>📣 ${esc(a.title)}</b><span class="badge on">LIVE</span></div></div>`),
        ...nots.filter(n => n.enabled !== false).slice(0, 3).map(n => `<div class="card"><div class="head"><b>🔔 ${esc(n.title)}</b><span class="badge on">LIVE</span></div></div>`)
    ];
    live.innerHTML = items.length ? items.join("") : `<div class="card"><div class="body">Nothing live right now.</div></div>`;
}

// ── Announcements ────────────────────────────────────────────────────────
function renderAnnouncements() {
    const wrap = document.getElementById("ann-list");
    const items = Object.entries(cached.announcements || {}).sort((a, b) =>
        (b[1].pinned === true) - (a[1].pinned === true) || ((b[1].createdAt || 0) - (a[1].createdAt || 0)));
    wrap.innerHTML = items.length ? "" : `<div class="card"><div class="body">No announcements yet — create your first one.</div></div>`;
    for (const [id, a] of items) {
        const el = document.createElement("div");
        el.className = "card";
        el.innerHTML = `
            <div class="head">
                <b>${esc(a.title)}</b>
                ${a.pinned ? '<span class="badge pin">PINNED</span>' : ""}
                ${a.enabled === false ? '<span class="badge off">DISABLED</span>' : '<span class="badge on">LIVE</span>'}
                <span class="badge">${esc(a.type || "popup")}</span>
            </div>
            <div class="body">${md(a.body).slice(0, 220)}</div>
            <div class="actions">
                <button class="btn small" onclick="editAnn('${id}')">✏️ Edit</button>
                <button class="btn small" onclick="toggleAnn('${id}', ${a.pinned === true})">${a.pinned ? "📌 Unpin" : "📌 Pin"}</button>
                <button class="btn small" onclick="toggleAnnEnabled('${id}', ${a.enabled !== false})">${a.enabled === false ? "✅ Enable" : "⏸ Disable"}</button>
                <button class="btn small danger" onclick="delAnn('${id}')">🗑 Delete</button>
            </div>`;
        wrap.appendChild(el);
    }
}

function openAnnModal(id) {
    const a = id ? cached.announcements[id] : {};
    document.getElementById("ann-id").value = id || "";
    document.getElementById("ann-title").value = a.title || "";
    document.getElementById("ann-body").value = a.body || "";
    document.getElementById("ann-type").value = a.type || "popup";
    document.getElementById("ann-pinned").checked = a.pinned === true;
    document.getElementById("ann-enabled").checked = a.enabled !== false;
    document.getElementById("ann-preview").innerHTML = md(a.body || "");
    document.getElementById("modal-ann").classList.remove("hidden");
}
function previewAnn() { document.getElementById("ann-preview").innerHTML = md(document.getElementById("ann-body").value); }

async function saveAnn() {
    const id = document.getElementById("ann-id").value;
    const key = id || `ann_${Date.now()}`;
    const data = {
        title: document.getElementById("ann-title").value.trim(),
        body: document.getElementById("ann-body").value,
        type: document.getElementById("ann-type").value,
        pinned: document.getElementById("ann-pinned").checked,
        enabled: document.getElementById("ann-enabled").checked,
        createdAt: cached.announcements[key]?.createdAt || Date.now(),
        updatedAt: Date.now()
    };
    if (!data.title) return flash("Title required", true);
    await fb(`/announcements/${key}`, "PUT", data);
    closeModal("modal-ann"); flash("Announcement saved"); refreshAll();
}
function editAnn(id) { openAnnModal(id); }
async function toggleAnn(id, pinned) { await fb(`/announcements/${id}/pinned`, "PUT", !pinned); flash("Updated"); refreshAll(); }
async function toggleAnnEnabled(id, enabled) { await fb(`/announcements/${id}/enabled`, "PUT", !enabled); flash("Updated"); refreshAll(); }
async function delAnn(id) { if (!confirm("Delete this announcement?")) return; await fb(`/announcements/${id}`, "DELETE"); flash("Deleted"); refreshAll(); }

// ── Notifications ────────────────────────────────────────────────────────
function renderNotifications() {
    const wrap = document.getElementById("notif-list");
    const items = Object.entries(cached.notifications || {}).sort((a, b) => (b[1].createdAt || 0) - (a[1].createdAt || 0));
    wrap.innerHTML = items.length ? "" : `<div class="card"><div class="body">No notifications yet.</div></div>`;
    const now = Date.now();
    for (const [id, n] of items) {
        const expired = n.expiresAt && n.expiresAt < now;
        const el = document.createElement("div");
        el.className = "card";
        el.innerHTML = `
            <div class="head">
                <b>${esc(n.icon || "🔔")} ${esc(n.title)}</b>
                <span class="badge prio">${esc(n.priority || "normal")}</span>
                ${n.enabled === false ? '<span class="badge off">DISABLED</span>' : '<span class="badge on">LIVE</span>'}
                ${expired ? '<span class="badge">EXPIRED</span>' : ""}
            </div>
            <div class="body">${esc(n.message)}</div>
            <div class="actions">
                <button class="btn small" onclick="editNotif('${id}')">✏️ Edit</button>
                <button class="btn small" onclick="toggleNotif('${id}', ${n.enabled !== false})">${n.enabled === false ? "✅ Enable" : "⏸ Disable"}</button>
                <button class="btn small danger" onclick="delNotif('${id}')">🗑 Delete</button>
            </div>`;
        wrap.appendChild(el);
    }
}

function openNotifModal(id) {
    const n = id ? cached.notifications[id] : {};
    document.getElementById("notif-id").value = id || "";
    document.getElementById("notif-title").value = n.title || "";
    document.getElementById("notif-message").value = n.message || "";
    document.getElementById("notif-icon").value = n.icon || "🔔";
    document.getElementById("notif-priority").value = n.priority || "normal";
    document.getElementById("notif-expires").value = n.expiresAt ? new Date(n.expiresAt).toISOString().slice(0, 16) : "";
    document.getElementById("notif-enabled").checked = n.enabled !== false;
    document.getElementById("modal-notif").classList.remove("hidden");
}

async function saveNotif() {
    const id = document.getElementById("notif-id").value;
    const key = id || `ntf_${Date.now()}`;
    const exp = document.getElementById("notif-expires").value;
    const data = {
        title: document.getElementById("notif-title").value.trim(),
        message: document.getElementById("notif-message").value,
        icon: document.getElementById("notif-icon").value || "🔔",
        priority: document.getElementById("notif-priority").value,
        expiresAt: exp ? new Date(exp).getTime() : 0,
        enabled: document.getElementById("notif-enabled").checked,
        createdAt: cached.notifications[key]?.createdAt || Date.now(),
        updatedAt: Date.now()
    };
    if (!data.title) return flash("Title required", true);
    await fb(`/notifications/${key}`, "PUT", data);
    closeModal("modal-notif"); flash("Notification saved"); refreshAll();
}
function editNotif(id) { openNotifModal(id); }
async function toggleNotif(id, enabled) { await fb(`/notifications/${id}/enabled`, "PUT", !enabled); flash("Updated"); refreshAll(); }
async function delNotif(id) { if (!confirm("Delete this notification?")) return; await fb(`/notifications/${id}`, "DELETE"); flash("Deleted"); refreshAll(); }

// ── Sponsorship ──────────────────────────────────────────────────────────
function renderSponsorship() {
    const on = cached.settings.sponsorshipEnabled !== false;
    document.getElementById("sponsor-switch").checked = on;
    document.getElementById("sponsor-state").textContent = on ? "Sponsorship is VISIBLE in the launcher" : "Sponsorship is HIDDEN everywhere in the launcher";
}
async function toggleSponsor() {
    const on = document.getElementById("sponsor-switch").checked;
    await fb("/settings/sponsorshipEnabled", "PUT", on);
    flash(on ? "Sponsorship enabled — launcher shows sponsor cards" : "Sponsorship disabled — all sponsor UI hidden instantly");
    refreshAll();
}

// ── Update manager ───────────────────────────────────────────────────────
function renderUpdate() {
    const u = cached.update || {};
    const box = document.getElementById("update-current");
    box.innerHTML = u.version ? `
        <div class="update-live">
            <div class="cell"><div class="k">Version</div><div class="v">${esc(u.version)}</div></div>
            <div class="cell"><div class="k">Min supported</div><div class="v">${esc(u.minVersion || "—")}</div></div>
            <div class="cell"><div class="k">Force update</div><div class="v">${u.force ? "⚠️ YES" : "No"}</div></div>
            <div class="cell"><div class="k">Download</div><div class="v"><a href="${esc(u.url || "#")}" target="_blank">link</a></div></div>
        </div>
        <div class="card"><div class="body">${md(u.changelog || "No changelog.")}</div></div>
    ` : `<div class="card"><div class="body">No published update yet — the launcher runs the latest published version.</div></div>`;

    document.getElementById("upd-version").value = u.version || "";
    document.getElementById("upd-min").value = u.minVersion || "";
    document.getElementById("upd-url").value = u.url || "";
    document.getElementById("upd-changelog").value = u.changelog || "";
    document.getElementById("upd-force").checked = u.force === true;
    document.getElementById("upd-preview").innerHTML = md(u.changelog || "");
}
function previewUpdate() { document.getElementById("upd-preview").innerHTML = md(document.getElementById("upd-changelog").value); }

async function saveUpdate() {
    const data = {
        version: document.getElementById("upd-version").value.trim(),
        minVersion: document.getElementById("upd-min").value.trim(),
        url: document.getElementById("upd-url").value.trim(),
        changelog: document.getElementById("upd-changelog").value,
        force: document.getElementById("upd-force").checked,
        updatedAt: Date.now()
    };
    if (!data.version) return flash("Version required", true);
    await fb("/update", "PUT", data);
    flash("Update published — launcher will prompt players");
    refreshAll();
}
async function clearUpdate() { await fb("/update", "DELETE"); flash("Update cleared"); refreshAll(); }

// ── Misc ─────────────────────────────────────────────────────────────────
function esc(s) { return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }
function countEnabled(list) { return list.filter(x => x.enabled !== false).length; }
function closeModal(id) { document.getElementById(id).classList.add("hidden"); }
function flash(msg, err) {
    const el = document.createElement("div");
    el.className = "flash"; el.style.background = err ? "var(--red)" : "var(--green)";
    el.style.color = "#08110b"; el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 2600);
}

// ── Boot ─────────────────────────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", () => {
    // Login
    document.getElementById("login-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        document.getElementById("login-error").textContent = "";
        try {
            await login(document.getElementById("login-email").value, document.getElementById("login-pass").value);
            document.getElementById("login-view").classList.add("hidden");
            document.getElementById("app-view").classList.remove("hidden");
            showView("dashboard");
            await refreshAll();
            pollTimer = setInterval(refreshAll, 4000);
        } catch (err) {
            document.getElementById("login-error").textContent = "Login failed — check email/password and Firebase config.";
            console.error(err);
        }
    });
    document.getElementById("logout-btn").addEventListener("click", () => {
        idToken = null; clearInterval(pollTimer);
        document.getElementById("app-view").classList.add("hidden");
        document.getElementById("login-view").classList.remove("hidden");
        document.getElementById("login-pass").value = "";
    });
    document.querySelectorAll(".nav-item").forEach(n => n.addEventListener("click", () => showView(n.dataset.view)));

    // Modal buttons
    document.getElementById("btn-new-ann").addEventListener("click", () => openAnnModal(null));
    document.getElementById("btn-new-notif").addEventListener("click", () => openNotifModal(null));
    document.getElementById("ann-save").addEventListener("click", saveAnn);
    document.getElementById("notif-save").addEventListener("click", saveNotif);
    document.getElementById("upd-save").addEventListener("click", saveUpdate);
    document.getElementById("upd-clear").addEventListener("click", clearUpdate);
    document.getElementById("sponsor-switch").addEventListener("change", toggleSponsor);
    document.querySelectorAll(".modal-bg").forEach(bg => bg.addEventListener("click", e => { if (e.target === bg) bg.classList.add("hidden"); }));
    document.getElementById("ann-body").addEventListener("input", previewAnn);
    document.getElementById("upd-changelog").addEventListener("input", previewUpdate);
});
