/* CMF Health – website. Vanilla JS, all user/server text is inserted with textContent (no innerHTML of data). */
(function () {
  "use strict";
  const C = window.Charts;
  let me = null;

  // ------------------------------------------------------------------ helpers
  function h(tag, attrs, ...kids) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === "class") e.className = v;
      else if (k === "style") e.style.cssText = v;
      else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
      else if (k === "html") e.innerHTML = v; // only used with static strings / sanitized markdown
      else e.setAttribute(k, v === true ? "" : v);
    }
    for (const k of kids.flat(9)) if (k != null && k !== false) e.append(k instanceof Node ? k : String(k));
    return e;
  }
  const nf = new Intl.NumberFormat("de-DE");
  const fmt1 = (v) => (v == null ? "–" : new Intl.NumberFormat("de-DE", { maximumFractionDigits: 1 }).format(v));
  const hm = (min) => (min == null ? "–" : `${Math.floor(min / 60)} h ${String(Math.round(min % 60)).padStart(2, "0")} min`);
  const hmShort = (min) => (min == null ? "–" : `${Math.floor(min / 60)}:${String(Math.round(min % 60)).padStart(2, "0")}`);
  const time = (iso) => new Date(iso).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" });
  const dayLabel = (d) => new Date(d + "T12:00:00").toLocaleDateString("de-DE", { day: "numeric", month: "numeric" });
  const longDate = (d) => new Date(d).toLocaleDateString("de-DE", { weekday: "long", day: "numeric", month: "long" });
  const ago = (iso) => {
    if (!iso) return "";
    const m = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
    return m < 1 ? "gerade eben" : m < 60 ? `vor ${m} min` : m < 1440 ? `vor ${Math.round(m / 60)} h` : `vor ${Math.round(m / 1440)} Tagen`;
  };
  const css = C.css;

  function toast(text) {
    const t = h("div", { class: "toast" }, text);
    document.body.append(t);
    setTimeout(() => t.remove(), 2600);
  }

  async function api(path, opts = {}) {
    const init = { credentials: "same-origin", headers: { "X-Requested-With": "cmf" }, method: opts.method || "GET" };
    if (opts.json !== undefined) { init.body = JSON.stringify(opts.json); init.headers["Content-Type"] = "application/json"; init.method = opts.method || "POST"; }
    if (opts.body) { init.body = opts.body; init.method = "POST"; }
    const r = await fetch(path, init);
    const data = r.headers.get("Content-Type")?.includes("json") ? await r.json() : await r.text();
    // session expired while using the site -> back to the login page (only once, never for the initial check)
    if (r.status === 401 && me && path !== "/api/me" && !path.startsWith("/api/auth")) { me = null; route(); throw new Error("Nicht angemeldet"); }
    if (!r.ok) throw new Error((data && data.error) || `Fehler ${r.status}`);
    return data;
  }

  const ICONS = {
    home: "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
    sleep: "M12.3 4a8 8 0 1 0 7.7 10.2A7 7 0 0 1 12.3 4z",
    heart: "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
    run: "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9L7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7",
    ai: "M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z",
    lungs: "M6.5 6C5 6 4 10 3 14s-1 7 1 7 5.5-1 5.5-4v-4.4L11 11.1V2h2v9.1l1.5 1.5V17c0 3 3.5 4 5.5 4s2-3 1-7-2-8-3.5-8S15 8 15 10v1.5l-2-2h-2l-2 2V10c0-2-1-4-2.5-4z",
    gear: "M19.14 12.94c.04-.3.06-.61.06-.94s-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96a7 7 0 0 0-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.47.47 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87a.48.48 0 0 0 .12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z",
    doc: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zm2 16H8v-2h8zm0-4H8v-2h8zm-3-5V3.5L18.5 9z",
    user: "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
  };
  const icon = (name) => { const s = document.createElementNS("http://www.w3.org/2000/svg", "svg"); s.setAttribute("viewBox", "0 0 24 24"); const p = document.createElementNS("http://www.w3.org/2000/svg", "path"); p.setAttribute("d", ICONS[name]); p.setAttribute("fill", "currentColor"); s.append(p); return s; };
  const NAV = [["", "Übersicht", "home"], ["schlaf", "Schlaf", "sleep"], ["herz", "Herz & Atmung", "heart"], ["tagebuch", "Asthma-Tagebuch", "lungs"],
    ["aktivitaet", "Aktivität", "run"], ["coach", "KI-Coach", "ai"], ["bericht", "Arztbericht", "doc"], ["konto", "Konto", "user"], ["server", "Server", "gear"]];
  const TABS = ["", "schlaf", "herz", "tagebuch", "aktivitaet", "konto"];

  // ------------------------------------------------------------------ shell & router
  function shell(content, active) {
    const side = h("aside", { class: "side" },
      h("div", { class: "brand" }, h("img", { src: "/static/icon.svg", alt: "" }), "CMF Health"),
      h("nav", { class: "nav" }, NAV.filter((n) => n[0] !== "server" || me.is_admin).map(([p, label, ic]) => h("a", { href: "#/" + p, class: p === active ? "active" : "" }, icon(ic), label))),
      h("div", { class: "who" }, "Angemeldet als ", h("b", {}, me.username)));
    const tabbar = h("nav", { class: "tabbar" }, NAV.filter((n) => TABS.includes(n[0])).map(([p, label, ic]) =>
      h("a", { href: "#/" + p, class: p === active ? "active" : "" }, icon(ic), label.split(" ")[0])));
    const app = document.getElementById("app");
    app.textContent = "";
    app.append(h("div", { class: "shell" }, side, h("main", {}, content)), tabbar);
  }

  async function route() {
    C.hideTip();
    if (!location.hash && location.pathname.length > 1) history.replaceState(null, "", "/#" + location.pathname);
    const path = location.hash.replace(/^#\/?/, "");
    if (!me) {
      try { me = await api("/api/me"); } catch { me = null; }
      if (!me) return authPage();
    }
    const [page, arg] = path.split("/");
    const pages = { "": overview, schlaf: sleepPage, herz: heartPage, aktivitaet: activityPage, training: workoutPage, coach: coachPage, konto: accountPage,
      tagebuch: diaryPage, bericht: reportPage, server: serverPage };
    document.body.classList.toggle("print-page", page === "bericht");
    const fn = pages[page] || overview;
    const main = h("div", {}, h("div", { class: "loading" }, "Lädt …"));
    shell(main, page === "training" ? "aktivitaet" : page);
    try { main.replaceWith(await fn(arg)); }
    catch (e) { main.replaceWith(h("div", { class: "card" }, h("b", {}, "Fehler: "), e.message)); }
    window.scrollTo(0, 0);
  }
  window.addEventListener("hashchange", route);
  let resizeTimer; window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => { if (me && !location.hash.startsWith("#/coach")) route(); }, 300); });

  // ------------------------------------------------------------------ auth
  async function authPage() {
    const st = await api("/api/auth/status").catch(() => ({ signup: false }));
    let mode = st.first_user ? "register" : "login";
    const err = h("div", { class: "err" });
    const user = h("input", { type: "text", autocomplete: "username", placeholder: "Benutzername", required: true });
    const pw = h("input", { type: "password", autocomplete: "current-password", placeholder: "Passwort", required: true });
    const btn = h("button", { class: "btn", type: "submit", style: "width:100%;justify-content:center" });
    const title = h("h1"), info = h("p", { class: "sub" });
    const toggle = h("a", { href: "#", class: "small muted" });
    const render = () => {
      title.textContent = mode === "login" ? "Anmelden" : "Konto anlegen";
      btn.textContent = mode === "login" ? "Anmelden" : "Registrieren";
      pw.autocomplete = mode === "login" ? "current-password" : "new-password";
      info.textContent = st.first_user ? "Willkommen! Das erste Konto wird Administrator." :
        mode === "login" ? "Deine Daten von der CMF Watch – Schlaf, Puls, Atmung, Training." : "Mindestens 8 Zeichen Passwort.";
      toggle.textContent = mode === "login" ? (st.signup ? "Noch kein Konto? Registrieren" : "") : "Schon ein Konto? Anmelden";
    };
    toggle.addEventListener("click", (e) => { e.preventDefault(); mode = mode === "login" ? "register" : "login"; err.textContent = ""; render(); });
    const form = h("form", { onsubmit: async (e) => {
      e.preventDefault(); btn.disabled = true; err.textContent = "";
      try {
        await api(mode === "login" ? "/api/auth/login" : "/api/auth/register", { json: { username: user.value, password: pw.value } });
        me = await api("/api/me"); location.hash = mode === "register" ? "#/konto" : "#/"; route();
      } catch (ex) { err.textContent = ex.message; } finally { btn.disabled = false; }
    } }, h("label", { class: "field" }, h("span", {}, "Benutzername"), user), h("label", { class: "field" }, h("span", {}, "Passwort"), pw), err, btn);
    render();
    const app = document.getElementById("app");
    app.textContent = "";
    app.append(h("div", { class: "auth" }, h("div", { class: "card stack" },
      h("div", { class: "brand", style: "padding:0 0 8px" }, h("img", { src: "/static/icon.svg", alt: "" }), "CMF Health"),
      title, info, form, h("div", { style: "text-align:center" }, toggle))));
  }

  // ------------------------------------------------------------------ building blocks
  const stageColors = () => ({ deep: css("--deep"), light: css("--light"), rem: css("--rem"), awake: css("--awake") });
  function sleepBar(n) {
    const total = (n.deep_min + n.light_min + n.rem_min + n.awake_min) || 1, c = stageColors();
    return h("div", { class: "sbar" }, ["deep", "light", "rem", "awake"].map((k) =>
      h("span", { style: `width:${(100 * n[k + "_min"]) / total}%;background:${c[k]}`, title: k })));
  }
  function stageLegend(n) {
    const c = stageColors();
    return h("div", { class: "legend" }, [["deep", "Tief"], ["light", "Leicht"], ["rem", "REM"], ["awake", "Wach"]].map(([k, l]) =>
      h("span", {}, h("i", { style: `background:${c[k]}` }), `${l} ${hmShort(n[k + "_min"])}`)));
  }
  function tile(label, value, unit, sub, color) {
    return h("div", { class: "card c3" }, h("div", { class: "label" }, label),
      h("div", { class: "num", style: color ? `color:${color}` : "" }, value ?? "–", value != null && unit ? h("span", { class: "unit" }, unit) : null),
      h("div", { class: "small muted" }, sub || ""));
  }
  const LEVEL = { alert: "--brand", warn: "--warn", info: "--info", good: "--good" };
  function flagList(flags, area) {
    const list = flags.filter((f) => !area || f.area === area);
    if (!list.length) return area ? null : h("div", { class: "empty small" }, "Keine Hinweise – alles im üblichen Bereich");
    return h("div", { class: "stack" }, list.map((f) => h("div", { class: "flag " + f.level },
      h("span", { class: "dot", style: `background:${css(LEVEL[f.level])}` }),
      h("div", {}, h("span", { class: "area" }, f.area), h("b", {}, f.title), h("p", {}, f.detail)))));
  }
  function chartBox(height, draw) {
    const box = h("div", { style: `height:${height}px` });
    requestAnimationFrame(() => draw(box));
    return box;
  }
  function seg(options, value, onChange) {
    const s = h("div", { class: "seg" });
    options.forEach(([v, l]) => s.append(h("button", { class: v === value ? "on" : "", onclick: (e) => {
      s.querySelectorAll("button").forEach((b) => b.classList.remove("on")); e.currentTarget.classList.add("on"); onChange(v);
    } }, l)));
    return s;
  }

  /** Very small, safe markdown (escape first, then a handful of rules). */
  function markdown(md) {
    const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    let out = "", inList = false;
    for (const raw of esc(md || "").split("\n")) {
      const line = raw.replace(/\*\*(.+?)\*\*/g, "<b>$1</b>").replace(/(^|\s)\*(\S.+?)\*/g, "$1<i>$2</i>");
      const li = line.match(/^\s*(?:[-*]|\d+\.)\s+(.*)/);
      if (li) { if (!inList) { out += "<ul>"; inList = true; } out += `<li>${li[1]}</li>`; continue; }
      if (inList) { out += "</ul>"; inList = false; }
      const hd = line.match(/^#{1,4}\s+(.*)/);
      if (hd) out += `<h3>${hd[1]}</h3>`;
      else if (line.trim()) out += `<p>${line}</p>`;
    }
    return out + (inList ? "</ul>" : "");
  }

  function recoveryText(v) {
    if (v == null) return ["Noch zu wenige Daten", "--muted"];
    if (v >= 75) return ["Gut erholt – bereit für Belastung", "--good"];
    if (v >= 50) return ["Mittel – moderates Training", "--warn"];
    return ["Schonen – Erholung hat Vorrang", "--brand"];
  }

  // ------------------------------------------------------------------ pages
  async function overview() {
    const d = await api("/api/dashboard");
    const s = d.state, n = d.last_night, ins = d.insights;
    const [rtext, rcolor] = recoveryText(ins.recovery);
    const ring = h("div", { class: "ring" });
    C.ring(ring, ins.recovery || 0, css(rcolor));
    ring.append(h("div", { class: "v" }, h("div", {}, h("b", {}, ins.recovery ?? "–"), h("span", { class: "small muted" }, "von 100"))));

    const nightCard = h("div", { class: "card c8 stack" },
      h("div", { class: "row between" }, h("div", { class: "label" }, "Letzte Nacht"), n ? h("a", { href: "#/schlaf", class: "small muted" }, "Details →") : null),
      n ? h("div", {},
        h("div", { class: "big" }, hm(n.asleep_min)),
        h("div", { class: "muted small" }, `${time(n.start)} – ${time(n.end)} · Effizienz ${fmt1(n.efficiency)} % · ${longDate(n.end)}`),
      ) : h("div", { class: "empty" }, "Noch keine Schlafdaten – App „CMF Export“ verbinden (Konto → In App verbinden)."),
      n ? sleepBar(n) : null, n ? stageLegend(n) : null,
      n && d.stages.length ? chartBox(120, (b) => C.hypnogram(b, d.stages, n.start_ts, n.end_ts)) : null);

    const naps = d.nights.filter((x) => x.type === "nap" && x.night_of === new Date().toISOString().slice(0, 10));
    const daily = d.daily;
    const today = daily[daily.length - 1] || {};
    const lastNights = d.nights.filter((x) => x.type === "night").slice(-30);
    const spo2Night = ins.spo2_nights[ins.spo2_nights.length - 1];

    const aiCard = h("div", { class: "card c5 stack" }, h("div", { class: "row between" }, h("h2", { style: "margin:0" }, "KI-Coach"),
      h("a", { href: "#/coach", class: "small muted" }, "Öffnen →")));
    if (d.report) aiCard.append(h("div", { class: "md small", html: markdown(d.report.content.split("\n").slice(0, 14).join("\n")) }));
    else if (me.ai) aiCard.append(h("p", { class: "muted small" }, "Lass dir einen Bericht über Schlaf, Atmung, Herz und Training schreiben."),
      h("button", { class: "btn", onclick: async (e) => { e.target.disabled = true; e.target.textContent = "Schreibt …"; try { await api("/api/ai/report", { json: {} }); route(); } catch (ex) { toast(ex.message); e.target.disabled = false; } } }, "Bericht erstellen"));
    else aiCard.append(h("p", { class: "muted small" }, me.is_admin ? "KI ist noch nicht eingerichtet – unter „Server“ den LiteLLM-Endpunkt eintragen. Die Hinweise funktionieren auch ohne KI." : "KI ist noch nicht eingerichtet (der Admin kann sie unter „Server“ einschalten). Die Hinweise funktionieren auch ohne KI."));

    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, `Hallo ${me.username}`),
        h("div", { class: "sub" }, s.last_upload ? `Zuletzt synchronisiert ${ago(s.last_upload)}` : "Noch keine Daten empfangen"))),
      h("div", { class: "grid" },
        h("div", { class: "card c4 row", style: "gap:18px;align-items:center" }, ring,
          h("div", {}, h("div", { class: "label" }, "Erholung"), h("div", { style: `font-weight:600;color:${css(rcolor)}` }, rtext),
            h("div", { class: "small muted" }, "aus Schlaf, Ruhepuls, HRV und Sauerstoff"))),
        nightCard,
        tile("Schritte heute", s.steps_today != null ? nf.format(s.steps_today) : null, "", progress(s.steps_today || 0, d.goals.steps, `Ziel ${nf.format(d.goals.steps)}`)),
        tile("Puls", s.heart_rate, "bpm", s.resting_heart_rate ? `Ruhepuls ${Math.round(s.resting_heart_rate)} bpm` : "", css("--brand")),
        tile("Sauerstoff (SpO₂)", s.spo2 != null ? fmt1(s.spo2) : null, "%", spo2Night ? `Nacht: Ø ${fmt1(spo2Night.avg)} %, min ${spo2Night.min} %` : ""),
        tile(s.respiratory_rate != null ? "Atemfrequenz" : "Stress", s.respiratory_rate != null ? fmt1(s.respiratory_rate) : s.stress, s.respiratory_rate != null ? "/min" : "",
          s.respiratory_rate != null && s.stress != null ? `Stress ${s.stress}` : s.watch_battery != null ? `Uhr-Akku ${s.watch_battery} %` : ""),
        envCard(d.environment, "c6"),
        quickLogCard(d.asthma, "c6"),
        h("div", { class: "card c7 stack" }, h("h2", {}, "Hinweise"), flagList(ins.flags), h("p", { class: "small muted", style: "margin:4px 0 0" }, ins.disclaimer)),
        aiCard,
        h("div", { class: "card c6" }, h("h2", {}, "Puls · letzte 24 h"),
          chartBox(170, (b) => C.line(b, [{ points: d.heart_today, color: css("--brand"), area: true }], { gap: 3600, height: 170, yFmt: (v) => Math.round(v) }))),
        h("div", { class: "card c6" }, h("h2", {}, "Schlaf · 30 Nächte"),
          chartBox(170, (b) => C.stacked(b, lastNights.map((x) => ({
            label: dayLabel(x.night_of), tip: `${dayLabel(x.night_of)}: ${hm(x.asleep_min)}`,
            parts: [{ v: x.deep_min, color: css("--deep") }, { v: x.light_min, color: css("--light") }, { v: x.rem_min, color: css("--rem") }],
          })), { height: 170, goal: d.goals.sleep_min }))),
        h("div", { class: "card c12" }, h("h2", {}, "Schritte · 30 Tage"),
          chartBox(150, (b) => C.bars(b, daily.map((x) => ({ label: dayLabel(x.date), value: x.steps, tip: `${dayLabel(x.date)}: ${x.steps != null ? nf.format(x.steps) : "–"} Schritte`, color: x.steps >= d.goals.steps ? css("--good") : css("--light") })), { height: 150, goal: d.goals.steps }))),
        naps.length ? h("div", { class: "card c12 small" }, `Nickerchen heute: ${hm(naps.reduce((a, x) => a + x.asleep_min, 0))} – zählt nicht zum Nachtschlaf.`) : null,
      ));
  }

  async function sleepPage() {
    const { nights } = await api("/api/sleep?days=60");
    const real = nights.filter((n) => n.type === "night");
    const last14 = real.slice(-14), avg = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
    const list = h("div", { class: "grid" });
    real.slice(-14).reverse().forEach((n) => {
      const spo2 = n.spo2 || [];
      list.append(h("div", { class: "card c6 stack" },
        h("div", { class: "row between" }, h("div", {}, h("div", { class: "label" }, longDate(n.end)),
          h("div", { class: "num" }, hm(n.asleep_min))), h("div", { class: "small muted", style: "text-align:right" },
          `${time(n.start)} – ${time(n.end)}`, h("br"), `Effizienz ${fmt1(n.efficiency)} %`, n.segments > 1 ? ` · ${n.segments} Abschnitte` : "")),
        sleepBar(n), stageLegend(n),
        n.stages?.length ? chartBox(110, (b) => C.hypnogram(b, n.stages, n.start_ts, n.end_ts, { height: 110 })) : null,
        spo2.length > 1 ? h("div", {}, h("div", { class: "label" }, `Sauerstoff · min ${Math.min(...spo2.map((p) => p[1]))} %`),
          chartBox(90, (b) => C.line(b, [{ points: spo2, color: css("--info"), label: "SpO₂", fmt: (v) => fmt1(v) + " %" }], { height: 90, ref: 90, band: [95, 100], gap: 1800 }))) : null));
    });
    const naps = nights.filter((n) => n.type === "nap").slice(-10).reverse();
    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, "Schlaf"), h("div", { class: "sub" }, "Nickerchen (10–20 Uhr, unter 4 h) zählen nicht zur Nacht"))),
      h("div", { class: "grid" },
        tile("Ø 14 Nächte", hm(avg(last14.map((n) => n.asleep_min)))),
        tile("Ø Tiefschlaf", hm(avg(last14.map((n) => n.deep_min)))),
        tile("Ø REM", hm(avg(last14.map((n) => n.rem_min)))),
        tile("Ø Wach", hm(avg(last14.map((n) => n.awake_min)))),
        h("div", { class: "card c12" }, h("h2", {}, "Schlafdauer nach Phasen · 60 Tage"),
          h("div", { class: "legend", style: "margin-bottom:8px" }, [["--deep", "Tief"], ["--light", "Leicht"], ["--rem", "REM"], ["--awake", "Wach"]].map(([c, l]) => h("span", {}, h("i", { style: `background:${css(c)}` }), l))),
          chartBox(200, (b) => C.stacked(b, real.map((x) => ({
            label: dayLabel(x.night_of), tip: `${dayLabel(x.night_of)}: ${hm(x.asleep_min)} (Tief ${hmShort(x.deep_min)}, REM ${hmShort(x.rem_min)})`,
            parts: [{ v: x.deep_min, color: css("--deep") }, { v: x.light_min, color: css("--light") }, { v: x.rem_min, color: css("--rem") }, { v: x.awake_min, color: css("--awake") }],
          })), { height: 200, goal: 420 }))),
        naps.length ? h("div", { class: "card c12" }, h("h2", {}, "Nickerchen"), h("div", { class: "list" }, naps.map((n) =>
          h("div", { class: "row between small" }, h("span", {}, longDate(n.start)), h("span", {}, `${time(n.start)} – ${time(n.end)} · ${hm(n.asleep_min)}`))))) : null),
      h("h2", { style: "margin:22px 4px 10px" }, "Die letzten Nächte"),
      real.length ? list : h("div", { class: "card empty" }, "Noch keine Nächte"));
  }

  async function heartPage() {
    const ins = await api("/api/insights");
    const hrBox = h("div", { style: "height:200px" });
    const loadHr = async (days) => {
      const { points } = await api(`/api/series?metric=heart_rate&days=${days}`);
      C.line(hrBox, [{ points, color: css("--brand"), area: true, label: "Puls", fmt: (v) => Math.round(v) + " bpm" }], {
        height: 200, gap: days > 2 ? 6 * 3600 : 3600,
        xFmt: days > 2 ? (v) => new Date(v * 1000).toLocaleDateString("de-DE", { day: "numeric", month: "numeric" }) : undefined,
      });
    };
    requestAnimationFrame(() => loadHr(1));
    const series = async (metric, days) => (await api(`/api/series?metric=${metric}&days=${days}`)).points;
    const [hrv, stress, rr] = await Promise.all([series("hrv", 60), series("stress", 14), series("respiratory_rate", 30)]);
    const dateFmt = (v) => new Date(v * 1000).toLocaleDateString("de-DE", { day: "numeric", month: "numeric" });
    const nightTs = (d) => new Date(d + "T06:00:00").getTime() / 1000;
    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, "Herz & Atmung"), h("div", { class: "sub" }, "Puls, Ruhepuls, HRV, Sauerstoff, Atemfrequenz und Stress"))),
      h("div", { class: "grid" },
        h("div", { class: "card c12 stack" }, h("h2", {}, "Atmung"), flagList(ins.flags, "Atmung"),
          h("p", { class: "small muted", style: "margin:0" }, "Bei Asthma sind nächtliche Sauerstoffabfälle, eine steigende Atemfrequenz im Schlaf und häufiges Aufwachen wichtige Frühzeichen. Tipp: In der CMF-App die SpO₂-Messung im Schlaf aktivieren.")),
        h("div", { class: "card c6" }, h("h2", {}, "Sauerstoff in der Nacht"), h("div", { class: "legend", style: "margin-bottom:6px" },
            h("span", {}, h("i", { style: `background:${css("--info")}` }), "Durchschnitt"), h("span", {}, h("i", { style: `background:${css("--brand")}` }), "Tiefster Wert")),
          chartBox(180, (b) => C.line(b, [
            { points: ins.spo2_nights.map((n) => [nightTs(n.night_of), n.avg]), color: css("--info"), dots: true, label: "Ø", fmt: (v) => fmt1(v) + " %" },
            { points: ins.spo2_nights.map((n) => [nightTs(n.night_of), n.min]), color: css("--brand"), dots: true, label: "min", fmt: (v) => v + " %" },
          ], { height: 180, xFmt: dateFmt, ref: 90, band: [95, 100], yMax: 100 }))),
        h("div", { class: "card c6" }, h("h2", {}, "Atemfrequenz im Schlaf"),
          rr.length || ins.respiratory_nights.length ? chartBox(180, (b) => C.line(b, [{
            points: ins.respiratory_nights.length ? ins.respiratory_nights.map((n) => [nightTs(n.night_of), n.avg]) : rr,
            color: css("--rem"), dots: ins.respiratory_nights.length > 0, label: "", fmt: (v) => fmt1(v) + " /min" }], { height: 180, xFmt: dateFmt, band: [12, 20] }))
            : h("div", { class: "empty small" }, "Deine Uhr liefert (noch) keine Atemfrequenz. Manche Modelle messen sie nur im Schlaf, wenn die erweiterte Schlafanalyse aktiv ist.")),
        h("div", { class: "card c12" }, h("div", { class: "row between" }, h("h2", { style: "margin:0" }, "Puls"),
          seg([[1, "24 h"], [7, "7 Tage"], [30, "30 Tage"]], 1, (v) => loadHr(v))), h("div", { style: "height:10px" }), hrBox),
        h("div", { class: "card c6" }, h("h2", {}, "Ruhepuls · 30 Tage"), flagList(ins.flags, "Herz"),
          chartBox(170, (b) => C.line(b, [{ points: ins.resting_hr.map((r) => [nightTs(r.date), r.value]), color: css("--brand"), dots: true, fmt: (v) => Math.round(v) + " bpm" }], { height: 170, xFmt: dateFmt }))),
        h("div", { class: "card c6" }, h("h2", {}, "HRV (Herzratenvariabilität)"),
          hrv.length > 1 ? chartBox(170, (b) => C.line(b, [{ points: hrv, color: css("--good"), dots: true, fmt: (v) => Math.round(v) + " ms" }], { height: 170, xFmt: dateFmt }))
            : h("div", { class: "empty small" }, "Keine HRV-Werte – die CMF-App schreibt HRV nur bei manchen Firmware-Versionen nach Health Connect.")),
        h("div", { class: "card c12" }, h("h2", {}, "Stress · 14 Tage"), flagList(ins.flags, "Stress"),
          stress.length > 1 ? chartBox(170, (b) => C.line(b, [{ points: stress, color: css("--warn"), area: true, fmt: (v) => Math.round(v) }], { height: 170, xFmt: dateFmt, gap: 6 * 3600, yMin: 0, yMax: 100 }))
            : h("div", { class: "empty small" }, "Keine Stresswerte – Stress kommt nur über Gadgetbridge (die CMF-App gibt ihn nicht an Health Connect weiter).")),
        h("p", { class: "small muted c12" }, ins.disclaimer)));
  }

  /** Free vector map (OpenFreeMap, OpenStreetMap data) – light or dark like the rest of the site. */
  const mapStyle = () => `https://tiles.openfreemap.org/styles/${matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "liberty"}`;

  const SPORT = { RUN: "Laufen", OUTDOOR_RUNNING: "Laufen", WALK: "Gehen", HIKE: "Wandern", RIDE: "Radfahren" };
  async function activityPage() {
    const [daily, workouts] = await Promise.all([api("/api/daily?days=60"), api("/api/workouts")]);
    const last30 = daily.slice(-30);
    const avgSteps = last30.filter((d) => d.steps).reduce((a, d, _, arr) => a + d.steps / arr.length, 0);
    const weekKm = workouts.filter((w) => Date.now() - new Date(w.start) < 7 * 86400e3).reduce((a, w) => a + (w.distance_km || 0), 0);
    return h("div", {},
      h("div", { class: "top" }, h("h1", {}, "Aktivität")),
      h("div", { class: "grid" },
        tile("Ø Schritte · 30 Tage", avgSteps ? nf.format(Math.round(avgSteps)) : null),
        tile("Trainings · 7 Tage", workouts.filter((w) => Date.now() - new Date(w.start) < 7 * 86400e3).length),
        tile("km · 7 Tage", fmt1(weekKm)),
        tile("Trainings gesamt", workouts.length),
        h("div", { class: "card c12" }, h("h2", {}, "Schritte · 60 Tage"),
          chartBox(170, (b) => C.bars(b, daily.map((x) => ({ label: dayLabel(x.date), value: x.steps, tip: `${dayLabel(x.date)}: ${x.steps != null ? nf.format(x.steps) : "–"}`, color: css("--good") })), { height: 170, goal: 10000, avg: avgSteps }))),
        h("div", { class: "card c12" }, h("h2", {}, "Trainings"),
          workouts.length ? h("div", { class: "list" }, workouts.map((w) => h("a", { class: "item", href: `#/training/${w.start_ts}`, style: "text-decoration:none" },
            h("div", { class: "grow" }, h("b", {}, SPORT[w.type] || w.type || "Training"), h("div", { class: "small muted" }, new Date(w.start).toLocaleString("de-DE", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }))),
            w.points ? h("span", { class: "pill" }, "Karte") : null,
            h("div", { style: "text-align:right" }, h("div", {}, w.distance_km != null ? `${fmt1(w.distance_km)} km` : ""), h("div", { class: "small muted" },
              [w.duration_min != null ? `${Math.round(w.duration_min)} min` : "", w.avg_hr ? `♥ ${w.avg_hr}` : ""].filter(Boolean).join(" · "))))))
            : h("div", { class: "empty" }, "Noch keine Trainings – nimm eins mit „CMF Track“ auf."))));
  }

  async function workoutPage(ts) {
    const w = await api(`/api/workouts/${ts}`);
    const mapBox = h("div", { class: "map" });
    const pace = w.distance_km && w.duration_min ? w.duration_min / w.distance_km : null;
    requestAnimationFrame(() => {
      if (!w.track.length || !window.maplibregl) { mapBox.replaceWith(h("div", { class: "empty" }, "Keine GPS-Strecke zu diesem Training")); return; }
      const coords = w.track.map((p) => [p[2], p[1]]);
      const bounds = coords.reduce((b, c) => b.extend(c), new maplibregl.LngLatBounds(coords[0], coords[0]));
      const map = new maplibregl.Map({ container: mapBox, style: mapStyle(), bounds, fitBoundsOptions: { padding: 40 }, attributionControl: { compact: true } });
      map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
      map.on("load", () => {
        map.addSource("route", { type: "geojson", data: { type: "Feature", geometry: { type: "LineString", coordinates: coords } } });
        map.addLayer({ id: "route-casing", type: "line", source: "route", layout: { "line-join": "round", "line-cap": "round" }, paint: { "line-color": "#ffffff", "line-width": 9 } });
        map.addLayer({ id: "route", type: "line", source: "route", layout: { "line-join": "round", "line-cap": "round" }, paint: { "line-color": css("--brand"), "line-width": 5 } });
        map.addSource("ends", { type: "geojson", data: { type: "FeatureCollection", features: [
          { type: "Feature", properties: { c: "#2EBD59" }, geometry: { type: "Point", coordinates: coords[0] } },
          { type: "Feature", properties: { c: "#141414" }, geometry: { type: "Point", coordinates: coords[coords.length - 1] } }] } });
        map.addLayer({ id: "ends", type: "circle", source: "ends", paint: { "circle-radius": 7, "circle-color": ["get", "c"], "circle-stroke-color": "#fff", "circle-stroke-width": 3 } });
      });
    });
    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("a", { href: "#/aktivitaet", class: "small muted" }, "← Aktivität"),
        h("h1", {}, SPORT[w.type] || w.type || "Training"), h("div", { class: "sub" }, new Date(w.start).toLocaleString("de-DE", { dateStyle: "full", timeStyle: "short" })))),
      h("div", { class: "grid" },
        h("div", { class: "card c12", style: "padding:10px" }, mapBox),
        tile("Distanz", w.distance_km != null ? fmt1(w.distance_km) : null, "km"),
        tile("Dauer", w.duration_min != null ? Math.round(w.duration_min) : null, "min"),
        tile("Tempo", pace ? `${Math.floor(pace)}:${String(Math.round((pace % 1) * 60)).padStart(2, "0")}` : null, "/km"),
        tile("Ø Puls", w.avg_hr, "bpm", w.max_hr ? `max ${w.max_hr} bpm` : "", css("--brand")),
        w.elevation_gain_m != null ? tile("Höhenmeter", Math.round(w.elevation_gain_m), "m") : null,
        w.calories != null ? tile("Kalorien", Math.round(w.calories), "kcal") : null,
        h("div", { class: "card c12" }, h("h2", {}, "Puls"),
          chartBox(180, (b) => C.line(b, [{ points: w.heart_rate, color: css("--brand"), area: true, fmt: (v) => Math.round(v) + " bpm" }], { height: 180, gap: 900 })))));
  }

  async function coachPage() {
    const day = new Date().toISOString().slice(0, 10);
    const dash = await api("/api/dashboard");
    const reportBox = h("div", { class: "md" });
    const setReport = (r) => { reportBox.innerHTML = r ? markdown(r.content) : ""; if (!r) reportBox.append(h("p", { class: "muted" }, "Noch kein Bericht für heute.")); };
    setReport(dash.report);
    const genBtn = h("button", { class: "btn", disabled: !me.ai, onclick: async () => {
      genBtn.disabled = true; genBtn.textContent = "KI schreibt …";
      try { setReport(await api("/api/ai/report", { json: {} })); } catch (e) { toast(e.message); }
      genBtn.disabled = false; genBtn.textContent = "Neu erstellen";
    } }, dash.report ? "Neu erstellen" : "Bericht erstellen");

    const history = [];
    const chat = h("div", { class: "chat" }, h("div", { class: "msg ai" }, "Frag mich zu deinen Daten – z. B. „Warum schlafe ich schlecht?“ oder „Wie war meine Sauerstoffsättigung diese Woche?“"));
    const input = h("input", { type: "text", placeholder: me.ai ? "Frage stellen …" : "KI nicht eingerichtet", disabled: !me.ai });
    const send = async (e) => {
      e.preventDefault();
      const q = input.value.trim(); if (!q) return;
      input.value = ""; chat.append(h("div", { class: "msg me" }, q));
      const wait = h("div", { class: "msg ai muted" }, "…"); chat.append(wait); chat.scrollTop = chat.scrollHeight;
      try {
        const { answer } = await api("/api/ai/chat", { json: { message: q, history } });
        history.push({ role: "user", content: q }, { role: "assistant", content: answer });
        wait.classList.remove("muted"); wait.textContent = answer;
      } catch (ex) { wait.textContent = ex.message; }
      chat.scrollTop = chat.scrollHeight;
    };
    const chips = ["Wie war meine Atmung nachts?", "Was löst mein Asthma aus?", "Bin ich heute erholt genug für ein Training?", "Was kann ich für besseren Schlaf tun?"];
    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, "KI-Coach"), h("div", { class: "sub" }, me.ai ? "Analysiert deine zusammengefassten Daten (inkl. Asthma-Tagebuch und Pollen) über deinen KI-Endpunkt" : "Die KI ist noch nicht eingerichtet – der Admin trägt den Endpunkt unter „Server“ ein"))),
      h("div", { class: "grid" },
        h("div", { class: "card c7 stack" }, h("div", { class: "row between" }, h("h2", { style: "margin:0" }, `Bericht · ${new Date(day).toLocaleDateString("de-DE")}`), genBtn), reportBox,
          h("p", { class: "small muted" }, dash.insights.disclaimer)),
        h("div", { class: "card c5 stack" }, h("h2", {}, "Frag deine Daten"), chat,
          h("div", { class: "row" }, chips.map((c) => h("button", { class: "btn tonal small", disabled: !me.ai, onclick: (e) => { input.value = c; send(e); } }, c))),
          h("form", { class: "row", onsubmit: send, style: "flex-wrap:nowrap" }, input, h("button", { class: "btn", disabled: !me.ai }, "Senden"))),
        h("div", { class: "card c12 stack" }, h("h2", {}, "Erkannte Auffälligkeiten (ohne KI)"), flagList(dash.insights.flags))));
  }

  async function accountPage() {
    me = await api("/api/me");
    const tokenText = h("span", {}, me.token);
    const copy = (text) => { navigator.clipboard?.writeText(text).then(() => toast("Kopiert"), () => toast(text)); };
    const fileInput = h("input", { type: "file" });
    const uploadMsg = h("div", { class: "small muted" });
    const card = (title, ...body) => h("div", { class: "card c6 stack" }, h("h2", {}, title), ...body);
    const adminCard = me.is_admin ? card("Server & Benutzer",
      h("p", { class: "small muted", style: "margin:0" }, "Als Admin richtest du MQTT/Home Assistant, die KI und die Registrierung ein und verwaltest Konten."),
      h("a", { class: "btn tonal", href: "#/server" }, "Servereinstellungen öffnen")) : null;
    const oldPw = h("input", { type: "password", placeholder: "Aktuelles Passwort", autocomplete: "current-password" });
    const newPw = h("input", { type: "password", placeholder: "Neues Passwort (min. 8)", autocomplete: "new-password" });
    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, "Konto"), h("div", { class: "sub" }, `Angemeldet als ${me.username}`)),
        h("button", { class: "btn outline", onclick: async () => { await api("/api/auth/logout", { json: {} }); me = null; location.hash = ""; route(); } }, "Abmelden")),
      h("div", { class: "grid" },
        card("Dein App-Token",
          h("p", { class: "small muted", style: "margin:0" }, "Damit senden die Apps deine Daten an diesen Server. Behandle es wie ein Passwort."),
          h("div", { class: "token" }, tokenText, h("button", { class: "btn tonal small", onclick: () => copy(me.token) }, "Kopieren")),
          h("div", { class: "small muted" }, "Server-Adresse: ", h("b", {}, me.server)),
          h("button", { class: "btn outline small", onclick: async () => {
            if (!confirm("Neues Token erzeugen? Die Apps müssen danach neu verbunden werden.")) return;
            me = await api("/api/me/token", { json: {} }); tokenText.textContent = me.token; toast("Neues Token erstellt");
          } }, "Neues Token erzeugen")),
        card("Mit der App verbinden",
          h("p", { class: "small muted", style: "margin:0" }, "Öffne diese Seite auf dem Handy und tippe – Adresse und Token werden automatisch eingetragen."),
          h("a", { class: "btn", href: me.connect.export }, "CMF Export verbinden"),
          h("a", { class: "btn tonal", href: me.connect.track }, "CMF Track verbinden"),
          h("p", { class: "small muted", style: "margin:0" }, "Am PC: Adresse und Token oben abtippen oder kopieren.")),
        card("Datei hochladen",
          h("p", { class: "small muted", style: "margin:0" }, "Gadgetbridge-Datenbank/-ZIP oder Export-ZIP der App."),
          fileInput, uploadMsg,
          h("button", { class: "btn tonal", onclick: async () => {
            if (!fileInput.files[0]) return toast("Bitte eine Datei wählen");
            uploadMsg.textContent = "Lädt hoch …";
            try {
              const r = await api(`/api/upload?name=${encodeURIComponent(fileInput.files[0].name)}`, { body: fileInput.files[0] });
              uploadMsg.textContent = "Importiert: " + Object.entries(r.counts).filter(([, v]) => v).map(([k, v]) => `${k} ${v}`).join(", ");
            } catch (e) { uploadMsg.textContent = e.message; }
          } }, "Hochladen")),
        profileCard(card),
        card("Home Assistant",
          h("label", { class: "switch" }, h("span", {}, "Meine Werte an Home Assistant senden", h("div", { class: "small muted" }, me.mqtt ? "über MQTT – Gerät „CMF Watch“" : me.ha ? "über die Home-Assistant-API" : me.is_admin ? "Noch keine Verbindung – unter „Server“ den MQTT-Broker eintragen" : "Der Admin hat noch keine Verbindung eingerichtet")),
            h("input", { type: "checkbox", checked: me.publish_ha, onchange: async (e) => { me = await api("/api/me/settings", { json: { publish_ha: e.target.checked } }); toast("Gespeichert"); } }))),
        card("Passwort ändern", h("form", { class: "stack", onsubmit: async (e) => {
          e.preventDefault();
          try { await api("/api/me/password", { json: { old: oldPw.value, new: newPw.value } }); oldPw.value = newPw.value = ""; toast("Passwort geändert"); } catch (ex) { toast(ex.message); }
        } }, oldPw, newPw, h("button", { class: "btn tonal" }, "Ändern"))),
        card("Deine Daten",
          h("a", { class: "btn tonal", href: "/api/export" }, "Alle Daten herunterladen (JSON)"),
          h("button", { class: "btn outline", onclick: async () => {
            const pw = prompt("Konto und ALLE Daten endgültig löschen? Zur Bestätigung Passwort eingeben:");
            if (!pw) return;
            try { await api("/api/me", { method: "DELETE", json: { password: pw } }); me = null; location.hash = ""; route(); } catch (e) { toast(e.message); }
          } }, "Konto löschen")),
        adminCard,
        card("App installieren",
          h("p", { class: "small muted", style: "margin:0" }, "Die Website lässt sich wie eine App auf den Startbildschirm legen (Chrome: Menü → „App installieren“, iPhone: Teilen → „Zum Home-Bildschirm“)."),
          installBtn())));
  }

  // ------------------------------------------------------------------ shared new blocks
  function progress(value, goal, label) {
    const pct = Math.min(100, Math.round((100 * value) / (goal || 1)));
    return h("div", {}, h("div", { class: "pbar" }, h("span", { style: `width:${pct}%;background:${css(pct >= 100 ? "--good" : "--light")}` })),
      h("div", { class: "small muted", style: "margin-top:4px" }, `${label} · ${pct} %`));
  }
  const POLLEN_COLORS = ["--surface2", "--good", "--warn", "--brand"];
  const LEVEL_NAMES = ["keine", "gering", "mittel", "hoch"];
  function envCard(env, cls) {
    const c = h("div", { class: `card ${cls} stack` }, h("div", { class: "row between" }, h("h2", { style: "margin:0" }, "Luft & Pollen"),
      env && env.place ? h("span", { class: "small muted" }, env.place) : null));
    if (!env) {
      c.append(h("p", { class: "small muted", style: "margin:0" }, "Trag unter Konto deinen Ort ein – dann siehst du hier Pollenflug, Luftqualität und Wetter, und die Hinweise berücksichtigen sie."),
        h("a", { class: "btn tonal small", href: "#/konto" }, "Ort festlegen"));
      return c;
    }
    const aqiColor = env.aqi == null ? "--muted" : env.aqi <= 40 ? "--good" : env.aqi <= 60 ? "--warn" : "--brand";
    c.append(h("div", { class: "row", style: "gap:22px" },
      h("div", {}, h("div", { class: "label" }, "Luftqualität"), h("div", { class: "num", style: `color:${css(aqiColor)}` }, env.aqi != null ? Math.round(env.aqi) : "–"),
        h("div", { class: "small muted" }, env.aqi_label || "")),
      h("div", {}, h("div", { class: "label" }, "Pollen"), h("div", { class: "num" }, env.pollen_max), h("div", { class: "small muted" }, env.pollen_top || "kaum Flug")),
      env.weather ? h("div", {}, h("div", { class: "label" }, "Wetter"), h("div", { class: "num" }, env.weather.temp != null ? Math.round(env.weather.temp) : "–", h("span", { class: "unit" }, "°C")),
        h("div", { class: "small muted" }, [env.weather.text, env.weather.humidity != null ? `${env.weather.humidity} % Feuchte` : null].filter(Boolean).join(" · "))) : null));
    const shown = env.pollen.filter((p) => p.level || p.level_tomorrow);
    c.append(shown.length ? h("div", { class: "pollen" }, shown.map((p) => h("div", { class: "row between small" },
      h("span", {}, p.name),
      h("span", { class: "row", style: "gap:6px" },
        h("span", { class: "lvl", title: "heute", style: `background:${css(POLLEN_COLORS[p.level])}` }, LEVEL_NAMES[p.level]),
        h("span", { class: "muted" }, "morgen"),
        h("span", { class: "lvl", style: `background:${css(POLLEN_COLORS[p.level_tomorrow])}` }, LEVEL_NAMES[p.level_tomorrow])))))
      : h("div", { class: "small muted" }, "Heute und morgen kein nennenswerter Pollenflug."),
      h("div", { class: "small muted" }, `Feinstaub PM2,5 ${fmt1(env.pm2_5)} µg/m³ · Ozon ${fmt1(env.ozone)} µg/m³ · Quelle: Open-Meteo/CAMS`));
    return c;
  }

  async function logDiary(entries) {
    const r = await api("/api/diary", { json: { entries } });
    toast("Eingetragen");
    return r;
  }
  function quickLogCard(a, cls) {
    const pf = h("input", { type: "number", min: 50, max: 900, placeholder: "l/min", style: "max-width:120px" });
    const info = h("div", { class: "small muted" });
    const show = (x) => { info.textContent = `Heute ${x.today.reliever} Hübe Notfallspray · 7 Tage: ${x.reliever_7d}` + (x.control ? ` · Asthma ${x.control}` : ""); };
    show(a);
    return h("div", { class: `card ${cls} stack` }, h("div", { class: "row between" }, h("h2", { style: "margin:0" }, "Asthma-Tagebuch"), h("a", { href: "#/tagebuch", class: "small muted" }, "Alles →")),
      info,
      h("div", { class: "row" },
        h("button", { class: "btn", onclick: async () => show(await logDiary([{ kind: "reliever", value: 1 }])) }, "+1 Hub Notfallspray"),
        h("button", { class: "btn tonal", onclick: async () => show(await logDiary([{ kind: "controller", value: 1 }])) }, "Dauertherapie genommen")),
      h("form", { class: "row", style: "flex-wrap:nowrap", onsubmit: async (e) => { e.preventDefault(); if (!pf.value) return; try { show(await logDiary([{ kind: "peakflow", value: +pf.value }])); pf.value = ""; } catch (ex) { toast(ex.message); } } },
        h("span", { class: "small muted", style: "white-space:nowrap" }, "Peak-Flow"), pf, h("button", { class: "btn tonal small" }, "Speichern")),
      a.peakflow ? h("div", { class: "small" }, "Letzter Peak-Flow ", h("b", {}, `${Math.round(a.peakflow.value)} l/min`), ` · ${a.peakflow.pct} % vom Bestwert · Zone `,
        h("span", { class: "lvl", style: `background:${css({ grün: "--good", gelb: "--warn", rot: "--brand" }[a.peakflow.zone])}` }, a.peakflow.zone)) : null);
  }

  // ------------------------------------------------------------------ asthma diary page
  async function diaryPage() {
    const a = await api("/api/diary");
    const sym = new Set(), trig = new Set();
    let severity = 2, puffs = 0;
    const chip = (key, label, set) => h("button", { type: "button", class: "chip", onclick: (e) => {
      set.has(key) ? set.delete(key) : set.add(key); e.currentTarget.classList.toggle("on");
    } }, label);
    const puffLabel = h("b", {}, "0");
    const pf = h("input", { type: "number", min: 50, max: 900, placeholder: "z. B. 450" });
    const note = h("input", { type: "text", maxlength: 500, placeholder: "Notiz (optional)" });
    const when = h("input", { type: "datetime-local", value: new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16) });
    const controller = h("input", { type: "checkbox" });
    const save = async (e) => {
      e.preventDefault();
      const ts = Math.round(new Date(when.value).getTime() / 1000) || undefined;
      const entries = [];
      if (puffs) entries.push({ kind: "reliever", value: puffs, ts });
      if (controller.checked) entries.push({ kind: "controller", value: 1, ts });
      if (sym.size) entries.push({ kind: "symptom", detail: [...sym], value: severity, ts });
      if (trig.size) entries.push({ kind: "trigger", detail: [...trig], ts });
      if (pf.value) entries.push({ kind: "peakflow", value: +pf.value, ts });
      if (note.value.trim()) entries.push({ kind: "note", detail: note.value.trim(), ts });
      if (!entries.length) return toast("Nichts ausgewählt");
      try { await logDiary(entries); route(); } catch (ex) { toast(ex.message); }
    };
    const form = h("form", { class: "card c7 stack", onsubmit: save },
      h("h2", {}, "Eintragen"),
      h("div", { class: "label" }, "Notfallspray (Hübe)"),
      h("div", { class: "row" },
        h("button", { type: "button", class: "btn tonal small", onclick: () => { puffs = Math.max(0, puffs - 1); puffLabel.textContent = puffs; } }, "−"),
        puffLabel,
        h("button", { type: "button", class: "btn tonal small", onclick: () => { puffs = Math.min(20, puffs + 1); puffLabel.textContent = puffs; } }, "+"),
        h("label", { class: "row small", style: "margin-left:12px;gap:8px" }, controller, "Dauertherapie genommen")),
      h("div", { class: "label" }, "Beschwerden"),
      h("div", { class: "chips" }, Object.entries(a.symptom_names).map(([k, l]) => chip(k, l, sym))),
      h("div", { class: "row small" }, "Stärke", seg([[1, "leicht"], [2, "mittel"], [3, "stark"]], 2, (v) => { severity = v; })),
      h("div", { class: "label" }, "Mögliche Auslöser"),
      h("div", { class: "chips" }, Object.entries(a.trigger_names).map(([k, l]) => chip(k, l, trig))),
      h("div", { class: "grid2" }, h("label", { class: "field" }, h("span", {}, "Peak-Flow (l/min)"), pf), h("label", { class: "field" }, h("span", {}, "Zeitpunkt"), when)),
      note,
      h("button", { class: "btn", style: "width:100%;justify-content:center" }, "Speichern"));

    const ctrlColor = { kontrolliert: "--good", "teilweise kontrolliert": "--warn", "nicht kontrolliert": "--brand" }[a.control] || "--muted";
    const control = h("div", { class: "card c5 stack" }, h("h2", {}, "Asthma-Kontrolle · 4 Wochen"),
      h("div", { class: "num", style: `color:${css(ctrlColor)}` }, a.control || "noch keine Einträge"),
      h("div", { class: "list small" }, a.questions.map((q) => h("div", { class: "row between", style: "flex-wrap:nowrap" }, h("span", {}, q.q),
        h("span", { class: q.yes ? "pill red" : "pill" }, q.yes ? "ja" : "nein")))),
      h("p", { class: "small muted", style: "margin:0" }, "Angelehnt an die GINA-Kontrollfragen. Nur zur Orientierung – Änderungen an der Therapie immer mit der Ärztin oder dem Arzt besprechen."));

    const tl = a.timeline;
    const best = a.peakflow?.best;
    const symColor = (x) => x.severity >= 3 ? css("--brand") : x.severity === 2 ? css("--warn") : css("--light");
    const entries = [...a.entries].reverse().slice(0, 30);
    const KIND = { reliever: "Notfallspray", controller: "Dauertherapie", symptom: "Beschwerden", peakflow: "Peak-Flow", trigger: "Auslöser", note: "Notiz" };
    const describe = (e) => e.kind === "reliever" || e.kind === "controller" ? `${e.value || 1} Hub/Hübe` : e.kind === "peakflow" ? `${Math.round(e.value)} l/min`
      : e.kind === "symptom" ? (e.detail || "").split(",").map((k) => a.symptom_names[k] || k).join(", ") + (e.value ? ` (${["", "leicht", "mittel", "stark"][e.value]})` : "")
      : e.kind === "trigger" ? (e.detail || "").split(",").map((k) => a.trigger_names[k] || k).join(", ") : e.detail;
    const list = h("div", { class: "list small" }, entries.map((e) => h("div", { class: "row between", style: "flex-wrap:nowrap" },
      h("div", {}, h("b", {}, KIND[e.kind]), " ", describe(e), h("div", { class: "muted" }, new Date(e.ts * 1000).toLocaleString("de-DE", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }))),
      h("button", { class: "btn outline small", onclick: async () => { if (!confirm("Eintrag löschen?")) return; await api(`/api/diary/${e.id}`, { method: "DELETE" }); route(); } }, "Löschen"))));
    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, "Asthma-Tagebuch"), h("div", { class: "sub" }, "Notfallspray, Beschwerden, Peak-Flow und Auslöser – zusammen mit den Nachtwerten deiner Uhr"))),
      h("div", { class: "grid" },
        tile("Notfallspray heute", a.today.reliever, "Hübe", a.today.controller ? "Dauertherapie ✓" : "Dauertherapie heute noch nicht eingetragen"),
        tile("Spray · 7 Tage", a.reliever_7d, "Hübe", `an ${a.reliever_days_7d} Tagen · Woche davor ${a.reliever_prev_7d}`),
        tile("Peak-Flow", a.peakflow ? Math.round(a.peakflow.value) : null, "l/min", a.peakflow ? `${a.peakflow.pct} % vom Bestwert (${Math.round(a.peakflow.best)})` : "noch nicht gemessen",
          a.peakflow ? css({ grün: "--good", gelb: "--warn", rot: "--brand" }[a.peakflow.zone]) : null),
        tile("Häufigster Auslöser", a.triggers[0]?.name || null, "", a.triggers[0] ? `an ${a.triggers[0].days} Tagen` : ""),
        form, control,
        h("div", { class: "card c6" }, h("h2", {}, "Notfallspray · 30 Tage"),
          chartBox(160, (b) => C.bars(b, tl.map((x) => ({ label: dayLabel(x.date), value: x.reliever, tip: `${dayLabel(x.date)}: ${x.reliever} Hübe`, color: css("--brand") })), { height: 160 }))),
        h("div", { class: "card c6" }, h("h2", {}, "Peak-Flow"), h("div", { class: "small muted", style: "margin-bottom:6px" }, best ? `Grüner Bereich ab ${Math.round(best * 0.8)} l/min (80 % vom Bestwert)` : ""),
          chartBox(160, (b) => C.line(b, [{ points: tl.filter((x) => x.peakflow).map((x) => [new Date(x.date + "T12:00").getTime() / 1000, x.peakflow]), color: css("--info"), dots: true, fmt: (v) => Math.round(v) + " l/min" }],
            { height: 160, band: best ? [best * 0.8, best * 1.2] : undefined, ref: best ? best * 0.5 : undefined, xFmt: (v) => new Date(v * 1000).toLocaleDateString("de-DE", { day: "numeric", month: "numeric" }) }))),
        h("div", { class: "card c12" }, h("h2", {}, "Beschwerden · 30 Tage"),
          h("div", { class: "cal" }, tl.map((x) => h("div", { class: "d", title: `${dayLabel(x.date)}: ${x.symptoms.map((k) => a.symptom_names[k] || k).join(", ") || "keine"}`,
            style: x.symptoms.length ? `background:${symColor(x)}` : "" }, h("span", {}, new Date(x.date + "T12:00").getDate())))),
          h("div", { class: "legend", style: "margin-top:8px" }, [["--light", "leicht"], ["--warn", "mittel"], ["--brand", "stark"]].map(([c, l]) => h("span", {}, h("i", { style: `background:${css(c)}` }), l)))),
        h("div", { class: "card c12 stack" }, h("h2", {}, "Einträge"), entries.length ? list : h("div", { class: "empty small" }, "Noch keine Einträge"))));
  }

  // ------------------------------------------------------------------ profile (goals + location)
  function profileCard(card) {
    const steps = h("input", { type: "number", min: 1000, max: 50000, step: 500, value: me.goal_steps });
    const sleepH = h("input", { type: "number", min: 4, max: 12, step: 0.25, value: me.goal_sleep_min / 60 });
    const place = h("div", { class: "small" }, me.location ? `Ort: ${me.location.name || `${me.location.lat}, ${me.location.lon}`}` : "Noch kein Ort – für Pollen & Luftqualität");
    const q = h("input", { type: "text", placeholder: "Ort suchen, z. B. Leipzig" });
    const results = h("div", { class: "list small" });
    const setLoc = async (loc) => { me = await api("/api/me/settings", { json: { location: loc } }); place.textContent = loc ? `Ort: ${loc.name}` : "Kein Ort"; results.textContent = ""; toast("Gespeichert"); };
    let t;
    q.addEventListener("input", () => { clearTimeout(t); t = setTimeout(async () => {
      results.textContent = "";
      if (q.value.trim().length < 2) return;
      try {
        const list = await api(`/api/geo?q=${encodeURIComponent(q.value.trim())}`);
        list.forEach((r) => results.append(h("a", { href: "#", class: "item", onclick: (e) => { e.preventDefault(); setLoc({ lat: r.lat, lon: r.lon, name: r.name }); q.value = ""; } },
          h("b", {}, r.name), h("span", { class: "muted" }, r.region))));
        if (!list.length) results.append(h("div", { class: "muted" }, "Nichts gefunden"));
      } catch (e) { results.append(h("div", { class: "muted" }, e.message)); }
    }, 350); });
    return card("Ziele & Ort",
      h("div", { class: "grid2" }, h("label", { class: "field" }, h("span", {}, "Schritte pro Tag"), steps), h("label", { class: "field" }, h("span", {}, "Schlaf pro Nacht (h)"), sleepH)),
      h("button", { class: "btn tonal", onclick: async () => {
        try { me = await api("/api/me/settings", { json: { goal_steps: +steps.value, goal_sleep_min: Math.round(+sleepH.value * 60) } }); toast("Ziele gespeichert"); } catch (e) { toast(e.message); }
      } }, "Ziele speichern"),
      place, q, results,
      h("div", { class: "row" },
        navigator.geolocation ? h("button", { class: "btn outline small", onclick: () => navigator.geolocation.getCurrentPosition(
          (p) => setLoc({ lat: p.coords.latitude, lon: p.coords.longitude, name: "Mein Standort" }), () => toast("Standort nicht verfügbar")) }, "Aktuellen Standort verwenden") : null,
        me.location ? h("button", { class: "btn outline small", onclick: () => setLoc(null) }, "Ort entfernen") : null));
  }

  let installEvt = null;
  window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); installEvt = e; });
  function installBtn() {
    return h("button", { class: "btn tonal", onclick: async () => {
      if (installEvt) { installEvt.prompt(); installEvt = null; } else toast("Über das Browsermenü installieren");
    } }, "Als App installieren");
  }

  // ------------------------------------------------------------------ doctor report (printable)
  async function reportPage() {
    let days = 28;
    const box = h("div", {}, h("div", { class: "loading" }, "Lädt …"));
    const load = async () => {
      const r = await api(`/api/report?days=${days}`);
      const s = r.summary, a = r.asthma, ins = r.insights;
      const row = (k, v) => h("tr", {}, h("td", {}, k), h("td", {}, v ?? "–"));
      const nights = r.nights;
      box.replaceChildren(h("div", { class: "report" },
        h("div", { class: "row between" }, h("div", {}, h("h1", {}, "Gesundheitsbericht"),
          h("div", { class: "sub" }, `${r.user} · ${new Date(r.from).toLocaleDateString("de-DE")} – ${new Date(r.to).toLocaleDateString("de-DE")} · erstellt ${new Date(r.created).toLocaleString("de-DE")}`)),
          h("img", { src: "/static/icon.svg", alt: "", style: "width:42px" })),
        h("h2", {}, "Zusammenfassung"),
        h("table", { class: "kv" },
          row("Schlaf Ø pro Nacht", s.sleep_h != null ? `${fmt1(s.sleep_h)} h (${s.nights} Nächte, Effizienz ${fmt1(s.efficiency)} %)` : null),
          row("Wach pro Nacht Ø", s.awake_min != null ? `${Math.round(s.awake_min)} min` : null),
          row("Ruhepuls Ø", s.resting_hr != null ? `${Math.round(s.resting_hr)} bpm` : null),
          row("SpO₂ Ø (Tag)", s.spo2 != null ? `${fmt1(s.spo2)} %` : null),
          row("Schritte Ø pro Tag", s.steps != null ? nf.format(Math.round(s.steps)) : null),
          row("Erholung (heute)", ins.recovery != null ? `${ins.recovery} / 100` : null),
          row("Trainings", `${r.workouts.length}`)),
        h("h2", {}, "Atmung in der Nacht"),
        ins.spo2_nights.length ? h("table", { class: "grid-table" }, h("tr", {}, ["Nacht", "SpO₂ Ø", "min", "Messwerte < 92 %", "< 90 %"].map((x) => h("th", {}, x))),
          ins.spo2_nights.map((n) => h("tr", {}, h("td", {}, dayLabel(n.night_of)), h("td", {}, fmt1(n.avg) + " %"), h("td", { class: n.min < 90 ? "bad" : "" }, n.min + " %"), h("td", {}, n.below92), h("td", {}, n.below90))))
          : h("p", { class: "muted small" }, "Keine nächtlichen Sauerstoffwerte."),
        ins.respiratory_nights.length ? h("p", { class: "small" }, "Atemfrequenz im Schlaf: ", ins.respiratory_nights.map((n) => `${dayLabel(n.night_of)} ${fmt1(n.avg)}/min`).join(" · ")) : null,
        h("h2", {}, "Asthma-Tagebuch"),
        a.has_data ? h("div", {},
          h("table", { class: "kv" },
            row("Kontrolle (4 Wochen)", a.control),
            ...a.questions.map((q) => row(q.q, q.yes ? "ja" : "nein")),
            row("Notfallspray letzte 7 Tage", `${a.reliever_7d} Hübe an ${a.reliever_days_7d} Tagen (Woche davor ${a.reliever_prev_7d})`),
            row("Peak-Flow zuletzt", a.peakflow ? `${Math.round(a.peakflow.value)} l/min = ${a.peakflow.pct} % vom Bestwert ${Math.round(a.peakflow.best)} (${a.peakflow.zone})` : null),
            row("Häufige Auslöser", a.triggers.map((t) => `${t.name} (${t.days}×)`).join(", ") || null)),
          h("div", { style: "height:120px;margin-top:10px" }, (() => { const b = h("div", { style: "height:120px" }); requestAnimationFrame(() => C.bars(b, a.timeline.map((x) => ({ label: dayLabel(x.date), value: x.reliever, color: css("--brand") })), { height: 120 })); return b; })()),
          h("div", { class: "small muted" }, "Hübe Notfallspray pro Tag (30 Tage)"))
          : h("p", { class: "muted small" }, "Keine Tagebucheinträge."),
        h("h2", {}, "Auffälligkeiten"),
        h("ul", {}, ins.flags.filter((f) => f.level !== "good").map((f) => h("li", {}, h("b", {}, `${f.area}: ${f.title}. `), f.detail))),
        h("h2", {}, "Nächte"),
        h("table", { class: "grid-table" }, h("tr", {}, ["Nacht", "Schlaf", "Tief", "REM", "Wach", "Effizienz"].map((x) => h("th", {}, x))),
          nights.slice(-21).reverse().map((n) => h("tr", {}, h("td", {}, dayLabel(n.night_of)), h("td", {}, hmShort(n.asleep_min)), h("td", {}, hmShort(n.deep_min)), h("td", {}, hmShort(n.rem_min)), h("td", {}, hmShort(n.awake_min)), h("td", {}, fmt1(n.efficiency) + " %")))),
        h("p", { class: "small muted" }, ins.disclaimer + " Messwerte einer Fitnessuhr (CMF Watch), keine Medizinprodukt-Messung.")));
    };
    load().catch((e) => box.replaceChildren(h("div", { class: "card" }, e.message)));
    return h("div", {},
      h("div", { class: "top noprint" }, h("div", {}, h("h1", {}, "Arztbericht"), h("div", { class: "sub" }, "Zum Ausdrucken oder als PDF speichern und zum Termin mitnehmen")),
        h("div", { class: "row" }, seg([[14, "2 Wochen"], [28, "4 Wochen"], [90, "3 Monate"]], 28, (v) => { days = v; load(); }),
          h("button", { class: "btn", onclick: () => window.print() }, "Drucken / PDF"))),
      h("div", { class: "card" }, box));
  }

  // ------------------------------------------------------------------ server settings (admin)
  async function serverPage() {
    if (!me.is_admin) return h("div", { class: "card" }, "Nur für Admins.");
    let data = await api("/api/admin/settings");
    const st = data.settings;
    const inputs = {};
    const val = (k) => st[k].value;
    const text = (k, label, ph, type = "text") => {
      const i = h("input", { type, value: val(k) ?? "", placeholder: ph || "" });
      inputs[k] = () => (type === "number" ? (i.value === "" ? null : +i.value) : i.value.trim());
      return h("label", { class: "field" }, h("span", {}, label, src(k)), i);
    };
    const secret = (k, label) => {
      const i = h("input", { type: "password", autocomplete: "new-password", placeholder: st[k].set ? "•••••• gespeichert – leer lassen = behalten" : "" });
      const clear = h("input", { type: "checkbox" });
      inputs[k] = () => (clear.checked ? "" : i.value ? i.value : null);
      return h("label", { class: "field" }, h("span", {}, label, src(k)), i,
        st[k].set ? h("label", { class: "row small muted", style: "gap:6px;margin-top:4px" }, clear, "löschen") : null);
    };
    const toggle = (k, label, sub) => {
      const i = h("input", { type: "checkbox", checked: !!val(k) });
      inputs[k] = () => i.checked;
      return h("label", { class: "switch" }, h("span", {}, label, sub ? h("div", { class: "small muted" }, sub) : null), i);
    };
    function src(k) { const s = st[k].source; return s === "env" ? h("em", { class: "src" }, " · aus Umgebungsvariable") : s === "ui" ? null : null; }
    const collect = (keys) => Object.fromEntries(keys.map((k) => [k, inputs[k]()]));
    const statusBox = h("div", { class: "stack" });
    const renderStatus = (list) => {
      statusBox.textContent = "";
      if (!list.length) statusBox.append(h("div", { class: "small muted" }, "Keine Verbindung aktiv – MQTT einschalten und Broker eintragen."));
      list.forEach((p) => statusBox.append(h("div", { class: "row small" },
        h("span", { class: "dot", style: `background:${css(p.connected ? "--good" : "--brand")}` }),
        h("b", {}, p.type === "mqtt" ? `MQTT ${p.host}:${p.port}` : `HA-API ${p.host}`),
        h("span", { class: "muted" }, p.connected ? `verbunden${p.since ? " seit " + new Date(p.since * 1000).toLocaleTimeString("de-DE") : ""}${p.messages != null ? ` · ${nf.format(p.messages)} Nachrichten` : ""}` : (p.error || "verbindet …")))));
    };
    renderStatus(data.status);
    const poll = setInterval(async () => {
      if (!document.body.contains(statusBox)) return clearInterval(poll);
      try { renderStatus((await api("/api/admin/settings")).status); } catch { clearInterval(poll); }
    }, 4000);
    const saveBtn = (keys, label = "Speichern") => h("button", { class: "btn", type: "button", onclick: async (e) => {
      e.target.disabled = true;
      try { data = await api("/api/admin/settings", { json: collect(keys) }); renderStatus(data.status); me = await api("/api/me"); toast("Gespeichert – Verbindung wird neu aufgebaut"); }
      catch (ex) { toast(ex.message); } finally { e.target.disabled = false; }
    } }, label);
    const testOut = h("div", { class: "small" });
    const MQTT = ["mqtt_enabled", "mqtt_host", "mqtt_port", "mqtt_user", "mqtt_password", "mqtt_tls", "mqtt_tls_insecure", "mqtt_client_id", "discovery_prefix", "base_topic", "device_name"];
    const mqttCard = h("div", { class: "card c7 stack" }, h("h2", {}, "MQTT-Broker (Home Assistant)"),
      h("p", { class: "small muted", style: "margin:0" }, "CMF Health verbindet sich als Client mit deinem bestehenden Broker (z. B. dem Mosquitto-Add-on von Home Assistant). Die Sensoren erscheinen dann automatisch per MQTT-Discovery."),
      toggle("mqtt_enabled", "MQTT verwenden"),
      h("div", { class: "grid2" }, text("mqtt_host", "Host / IP", "192.168.1.10 oder core-mosquitto"), text("mqtt_port", "Port", "1883", "number")),
      h("div", { class: "grid2" }, text("mqtt_user", "Benutzer", "optional"), secret("mqtt_password", "Passwort")),
      toggle("mqtt_tls", "TLS verschlüsseln", "meist Port 8883"), toggle("mqtt_tls_insecure", "Zertifikat nicht prüfen", "nur für selbstsignierte Zertifikate"),
      h("details", {}, h("summary", { class: "small" }, "Erweitert"),
        h("div", { class: "grid2", style: "margin-top:10px" }, text("discovery_prefix", "Discovery-Präfix", "homeassistant"), text("base_topic", "Basis-Topic", "cmfwatch")),
        h("div", { class: "grid2" }, text("device_name", "Gerätename in HA", "CMF Watch Pro 3"), text("mqtt_client_id", "Client-ID", "cmfserver-cmf_watch"))),
      h("div", { class: "row" }, saveBtn(MQTT),
        h("button", { class: "btn tonal", type: "button", onclick: async () => {
          testOut.textContent = "Teste …";
          try { const r = await api("/api/admin/mqtt/test", { json: collect(["mqtt_host", "mqtt_port", "mqtt_user", "mqtt_password", "mqtt_tls", "mqtt_tls_insecure"]) });
            testOut.textContent = r.ok ? `✓ Broker erreichbar, Anmeldung ok (${r.ms} ms)` : `✗ ${r.error}`; testOut.style.color = css(r.ok ? "--good" : "--brand"); }
          catch (ex) { testOut.textContent = ex.message; }
        } }, "Verbindung testen")), testOut);
    const statusCard = h("div", { class: "card c5 stack" }, h("h2", {}, "Status"), statusBox,
      h("p", { class: "small muted", style: "margin:0" }, `cmfserver ${data.version}. Werte werden bei jedem Upload und alle ${val("publish_interval")} s gesendet.`),
      h("button", { class: "btn tonal small", onclick: async () => { await api("/api/publish", { json: {} }); toast("Gesendet"); } }, "Jetzt senden"));
    const HA = ["ha_url", "ha_token"];
    const haCard = h("div", { class: "card c6 stack" }, h("h2", {}, "Home-Assistant-API (statt MQTT)"),
      h("p", { class: "small muted", style: "margin:0" }, "Alternative ohne Broker: Werte direkt über die REST-API setzen (Langzeit-Zugangstoken aus dem HA-Profil)."),
      text("ha_url", "Home-Assistant-Adresse", "http://homeassistant.local:8123"), secret("ha_token", "Zugangstoken"), saveBtn(HA));
    const AI = ["llm_base_url", "llm_model", "llm_api_key"];
    const aiOut = h("div", { class: "small" });
    const aiCard = h("div", { class: "card c6 stack" }, h("h2", {}, "KI (LiteLLM / OpenAI-kompatibel)"),
      h("p", { class: "small muted", style: "margin:0" }, `Status: ${data.ai ? "aktiv" : "nicht eingerichtet"}. Es werden nur zusammengefasste Werte gesendet, keine Rohdaten.`),
      text("llm_base_url", "Endpunkt", "https://litellm.example.de"), text("llm_model", "Modell", "z. B. gpt-4o-mini"), secret("llm_api_key", "API-Key"),
      h("div", { class: "row" }, saveBtn(AI), h("button", { class: "btn tonal", type: "button", onclick: async () => {
        aiOut.textContent = "Teste …";
        const r = await api("/api/admin/ai/test", { json: {} }).catch((e) => ({ ok: false, error: e.message }));
        aiOut.textContent = r.ok ? `✓ Antwort „${r.answer}“ in ${r.ms} ms` : `✗ ${r.error}`; aiOut.style.color = css(r.ok ? "--good" : "--brand");
      } }, "Testen")), aiOut);
    const signup = h("select", {}, h("option", { value: "first", selected: val("signup") === "first" }, "Nur der Admin legt Konten an"), h("option", { value: "open", selected: val("signup") === "open" }, "Jeder darf sich registrieren"));
    inputs.signup = () => signup.value;
    const GEN = ["signup", "public_url", "publish_interval"];
    const genCard = h("div", { class: "card c6 stack" }, h("h2", {}, "Allgemein"),
      h("label", { class: "field" }, h("span", {}, "Registrierung"), signup),
      text("public_url", "Öffentliche Adresse (für die App-Links)", "https://health.example.de"),
      text("publish_interval", "Sende-Intervall an Home Assistant (Sekunden)", "300", "number"), saveBtn(GEN));

    // users
    const usersBox = h("div", { class: "list small" });
    const renderUsers = (list) => {
      usersBox.textContent = "";
      list.forEach((u) => usersBox.append(h("div", { class: "row between" },
        h("span", {}, h("b", {}, u.username), u.is_admin ? h("span", { class: "pill", style: "margin-left:8px" }, "Admin") : null),
        u.id === me.id ? h("span", { class: "muted" }, "du") : h("span", { class: "row", style: "gap:6px" },
          h("button", { class: "btn outline small", onclick: async () => { const pw = prompt(`Neues Passwort für ${u.username} (min. 8 Zeichen):`); if (!pw) return; try { renderUsers(await api(`/api/admin/users/${u.id}`, { json: { password: pw } })); toast("Passwort gesetzt"); } catch (e) { toast(e.message); } } }, "Passwort"),
          h("button", { class: "btn outline small", onclick: async () => renderUsers(await api(`/api/admin/users/${u.id}`, { json: { is_admin: !u.is_admin } })) }, u.is_admin ? "Admin entziehen" : "Zum Admin"),
          h("button", { class: "btn outline small", onclick: async () => { if (!confirm(`Konto ${u.username} mit allen Daten löschen?`)) return; renderUsers(await api(`/api/admin/users/${u.id}`, { method: "DELETE" })); } }, "Löschen")))));
    };
    api("/api/admin/users").then(renderUsers);
    const nu = h("input", { type: "text", placeholder: "Benutzername" }), np = h("input", { type: "password", placeholder: "Passwort (min. 8)", autocomplete: "new-password" });
    const usersCard = h("div", { class: "card c12 stack" }, h("h2", {}, "Benutzer"), usersBox, h("form", { class: "row", onsubmit: async (e) => {
      e.preventDefault();
      try { renderUsers(await api("/api/admin/users", { json: { username: nu.value, password: np.value } })); nu.value = np.value = ""; toast("Konto angelegt"); } catch (ex) { toast(ex.message); }
    } }, h("div", { style: "flex:1;min-width:160px" }, nu), h("div", { style: "flex:1;min-width:160px" }, np), h("button", { class: "btn tonal" }, "Konto anlegen")));

    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, "Server"), h("div", { class: "sub" }, "Nur für Admins · Einstellungen hier überschreiben die Umgebungsvariablen"))),
      h("div", { class: "grid" }, mqttCard, statusCard, haCard, aiCard, genCard, usersCard));
  }

  if ("serviceWorker" in navigator) window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));

  route();
})();
