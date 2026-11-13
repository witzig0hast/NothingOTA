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
    user: "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
  };
  const icon = (name) => { const s = document.createElementNS("http://www.w3.org/2000/svg", "svg"); s.setAttribute("viewBox", "0 0 24 24"); const p = document.createElementNS("http://www.w3.org/2000/svg", "path"); p.setAttribute("d", ICONS[name]); p.setAttribute("fill", "currentColor"); s.append(p); return s; };
  const NAV = [["", "Übersicht", "home"], ["schlaf", "Schlaf", "sleep"], ["herz", "Herz & Atmung", "heart"], ["aktivitaet", "Aktivität", "run"], ["coach", "KI-Coach", "ai"], ["konto", "Konto", "user"]];

  // ------------------------------------------------------------------ shell & router
  function shell(content, active) {
    const side = h("aside", { class: "side" },
      h("div", { class: "brand" }, h("img", { src: "/static/icon.svg", alt: "" }), "CMF Health"),
      h("nav", { class: "nav" }, NAV.map(([p, label, ic]) => h("a", { href: "#/" + p, class: p === active ? "active" : "" }, icon(ic), label))),
      h("div", { class: "who" }, "Angemeldet als ", h("b", {}, me.username)));
    const tabbar = h("nav", { class: "tabbar" }, NAV.filter((n) => n[0] !== "konto" || true).map(([p, label, ic]) =>
      h("a", { href: "#/" + p, class: p === active ? "active" : "" }, icon(ic), label.split(" ")[0])));
    const app = document.getElementById("app");
    app.textContent = "";
    app.append(h("div", { class: "shell" }, side, h("main", {}, content)), tabbar);
  }

  async function route() {
    C.hideTip();
    const path = location.hash.replace(/^#\/?/, "");
    if (!me) {
      try { me = await api("/api/me"); } catch { me = null; }
      if (!me) return authPage();
    }
    const [page, arg] = path.split("/");
    const pages = { "": overview, schlaf: sleepPage, herz: heartPage, aktivitaet: activityPage, training: workoutPage, coach: coachPage, konto: accountPage };
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
    else aiCard.append(h("p", { class: "muted small" }, "KI ist noch nicht eingerichtet (LLM_BASE_URL/LLM_MODEL am Server setzen). Die Hinweise links funktionieren auch ohne KI."));

    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, `Hallo ${me.username}`),
        h("div", { class: "sub" }, s.last_upload ? `Zuletzt synchronisiert ${ago(s.last_upload)}` : "Noch keine Daten empfangen"))),
      h("div", { class: "grid" },
        h("div", { class: "card c4 row", style: "gap:18px;align-items:center" }, ring,
          h("div", {}, h("div", { class: "label" }, "Erholung"), h("div", { style: `font-weight:600;color:${css(rcolor)}` }, rtext),
            h("div", { class: "small muted" }, "aus Schlaf, Ruhepuls, HRV und Sauerstoff"))),
        nightCard,
        tile("Schritte heute", s.steps_today != null ? nf.format(s.steps_today) : null, "", s.steps_yesterday != null ? `gestern ${nf.format(s.steps_yesterday)}` : ""),
        tile("Puls", s.heart_rate, "bpm", s.resting_heart_rate ? `Ruhepuls ${Math.round(s.resting_heart_rate)} bpm` : "", css("--brand")),
        tile("Sauerstoff (SpO₂)", s.spo2 != null ? fmt1(s.spo2) : null, "%", spo2Night ? `Nacht: Ø ${fmt1(spo2Night.avg)} %, min ${spo2Night.min} %` : ""),
        tile(s.respiratory_rate != null ? "Atemfrequenz" : "Stress", s.respiratory_rate != null ? fmt1(s.respiratory_rate) : s.stress, s.respiratory_rate != null ? "/min" : "",
          s.respiratory_rate != null && s.stress != null ? `Stress ${s.stress}` : s.watch_battery != null ? `Uhr-Akku ${s.watch_battery} %` : ""),
        h("div", { class: "card c7 stack" }, h("h2", {}, "Hinweise"), flagList(ins.flags), h("p", { class: "small muted", style: "margin:4px 0 0" }, ins.disclaimer)),
        aiCard,
        h("div", { class: "card c6" }, h("h2", {}, "Puls · letzte 24 h"),
          chartBox(170, (b) => C.line(b, [{ points: d.heart_today, color: css("--brand"), area: true }], { gap: 3600, height: 170, yFmt: (v) => Math.round(v) }))),
        h("div", { class: "card c6" }, h("h2", {}, "Schlaf · 30 Nächte"),
          chartBox(170, (b) => C.stacked(b, lastNights.map((x) => ({
            label: dayLabel(x.night_of), tip: `${dayLabel(x.night_of)}: ${hm(x.asleep_min)}`,
            parts: [{ v: x.deep_min, color: css("--deep") }, { v: x.light_min, color: css("--light") }, { v: x.rem_min, color: css("--rem") }],
          })), { height: 170, goal: 420 }))),
        h("div", { class: "card c12" }, h("h2", {}, "Schritte · 30 Tage"),
          chartBox(150, (b) => C.bars(b, daily.map((x) => ({ label: dayLabel(x.date), value: x.steps, tip: `${dayLabel(x.date)}: ${x.steps != null ? nf.format(x.steps) : "–"} Schritte`, color: css("--good") })), { height: 150, goal: 10000 }))),
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
      if (!w.track.length || !window.L) { mapBox.replaceWith(h("div", { class: "empty" }, "Keine GPS-Strecke zu diesem Training")); return; }
      const dark = matchMedia("(prefers-color-scheme: dark)").matches;
      const map = L.map(mapBox, { zoomControl: true, attributionControl: true });
      L.tileLayer(`https://{s}.basemaps.cartocdn.com/rastertiles/${dark ? "dark_all" : "voyager"}/{z}/{x}/{y}{r}.png`, {
        maxZoom: 20, subdomains: "abcd", attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> · © <a href="https://carto.com/attributions">CARTO</a>',
      }).addTo(map);
      const pts = w.track.map((p) => [p[1], p[2]]);
      L.polyline(pts, { color: "#ffffff", weight: 9, opacity: 0.9 }).addTo(map);
      const line = L.polyline(pts, { color: css("--brand"), weight: 5 }).addTo(map);
      L.circleMarker(pts[0], { radius: 8, color: "#fff", weight: 3, fillColor: "#2EBD59", fillOpacity: 1 }).addTo(map).bindTooltip("Start");
      L.circleMarker(pts[pts.length - 1], { radius: 8, color: "#fff", weight: 3, fillColor: "#141414", fillOpacity: 1 }).addTo(map).bindTooltip("Ziel");
      map.fitBounds(line.getBounds(), { padding: [24, 24] });
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
    const chips = ["Wie war meine Atmung nachts?", "Bin ich heute erholt genug für ein Training?", "Was kann ich für besseren Schlaf tun?"];
    return h("div", {},
      h("div", { class: "top" }, h("div", {}, h("h1", {}, "KI-Coach"), h("div", { class: "sub" }, me.ai ? "Analysiert deine zusammengefassten Daten über deinen KI-Endpunkt" : "Am Server LLM_BASE_URL und LLM_MODEL setzen, um die KI zu aktivieren"))),
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
    const usersBox = h("div");
    let adminCard = null;
    if (me.is_admin) {
      const renderUsers = (list) => { usersBox.textContent = ""; usersBox.append(h("div", { class: "list small" }, list.map((u) => h("div", { class: "row between" }, h("span", {}, u.username), u.is_admin ? h("span", { class: "pill" }, "Admin") : "")))); };
      api("/api/admin/users").then(renderUsers);
      const nu = h("input", { type: "text", placeholder: "Benutzername" }), np = h("input", { type: "password", placeholder: "Passwort (min. 8)" });
      adminCard = card("Benutzer (Admin)", usersBox, h("form", { class: "stack", onsubmit: async (e) => {
        e.preventDefault();
        try { renderUsers(await api("/api/admin/users", { json: { username: nu.value, password: np.value } })); nu.value = np.value = ""; toast("Konto angelegt"); } catch (ex) { toast(ex.message); }
      } }, nu, np, h("button", { class: "btn tonal" }, "Konto anlegen")));
    }
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
        card("Home Assistant",
          h("label", { class: "switch" }, h("span", {}, "Meine Werte an Home Assistant senden", h("div", { class: "small muted" }, me.mqtt ? "über MQTT – Gerät „CMF Watch“" : "MQTT ist am Server nicht eingerichtet")),
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
        me.is_admin ? adminCard : null));
  }

  route();
})();
