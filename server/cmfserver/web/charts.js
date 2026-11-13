/* Tiny SVG charts for CMF Health (no dependencies). All text goes through textContent. */
(function () {
  const NS = "http://www.w3.org/2000/svg";
  const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
  let tip;

  function el(tag, attrs, parent) {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs || {}) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }

  function showTip(evt, text) {
    if (!tip) { tip = document.createElement("div"); tip.className = "tip"; document.body.appendChild(tip); }
    tip.textContent = text;
    tip.style.display = "block";
    const x = (evt.touches ? evt.touches[0].clientX : evt.clientX), y = (evt.touches ? evt.touches[0].clientY : evt.clientY);
    const w = tip.offsetWidth;
    tip.style.left = Math.min(window.innerWidth - w - 8, Math.max(8, x - w / 2)) + "px";
    tip.style.top = (y - 44) + "px";
  }
  function hideTip() { if (tip) tip.style.display = "none"; }

  function svgFor(container, height) {
    container.textContent = "";
    const w = Math.max(container.clientWidth || 300, 120);
    const s = el("svg", { class: "chart", viewBox: `0 0 ${w} ${height}`, height: height, preserveAspectRatio: "none" }, container);
    return { s, w, h: height };
  }

  /** Line / area chart. series: [{points:[[x,y]], color, area, label, fmt}] */
  function line(container, series, opts = {}) {
    const H = opts.height || 160, padL = 34, padB = 20, padT = 8;
    const { s, w } = svgFor(container, H);
    const all = series.flatMap((x) => x.points).filter((p) => p[1] != null);
    if (all.length < 2) { container.innerHTML = '<div class="empty small">Noch keine Daten</div>'; return; }
    let x0 = Math.min(...all.map((p) => p[0])), x1 = Math.max(...all.map((p) => p[0]));
    if (opts.xMin != null) x0 = opts.xMin; if (opts.xMax != null) x1 = opts.xMax;
    let y0 = opts.yMin ?? Math.min(...all.map((p) => p[1])), y1 = opts.yMax ?? Math.max(...all.map((p) => p[1]));
    if (y1 - y0 < 1e-9) { y0 -= 1; y1 += 1; }
    const pad = (y1 - y0) * 0.08; if (opts.yMin == null) y0 -= pad; if (opts.yMax == null) y1 += pad;
    const X = (v) => padL + ((v - x0) / (x1 - x0 || 1)) * (w - padL - 4);
    const Y = (v) => padT + (1 - (v - y0) / (y1 - y0)) * (H - padT - padB);
    // grid & y labels
    for (let i = 0; i <= 3; i++) {
      const v = y0 + ((y1 - y0) * i) / 3, y = Y(v);
      el("line", { x1: padL, x2: w, y1: y, y2: y, stroke: css("--line"), "stroke-width": 1 }, s);
      el("text", { x: 0, y: y + 4 }, s).textContent = (opts.yFmt || ((v) => Math.round(v)))(v);
    }
    if (opts.band) {
      const [lo, hi] = opts.band;
      el("rect", { x: padL, y: Y(Math.min(hi, y1)), width: w - padL, height: Math.max(0, Y(Math.max(lo, y0)) - Y(Math.min(hi, y1))), fill: css("--good"), opacity: 0.08 }, s);
    }
    if (opts.ref != null && opts.ref > y0 && opts.ref < y1) {
      el("line", { x1: padL, x2: w, y1: Y(opts.ref), y2: Y(opts.ref), stroke: css("--brand"), "stroke-dasharray": "5 4", "stroke-width": 1.5 }, s);
    }
    // x labels
    const xf = opts.xFmt || ((v) => new Date(v * 1000).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" }));
    for (let i = 0; i <= 4; i++) {
      const v = x0 + ((x1 - x0) * i) / 4;
      el("text", { x: X(v), y: H - 4, "text-anchor": i === 0 ? "start" : i === 4 ? "end" : "middle" }, s).textContent = xf(v);
    }
    for (const se of series) {
      const pts = se.points.filter((p) => p[1] != null);
      if (pts.length < 2) continue;
      // break the line on gaps (e.g. no measurements for hours)
      const gap = opts.gap || Infinity;
      // split into segments at gaps; each segment gets its own line and area
      const segs = [[]];
      pts.forEach((p, i) => { if (i > 0 && p[0] - pts[i - 1][0] > gap) segs.push([]); segs[segs.length - 1].push(p); });
      for (const seg of segs) {
        const d = seg.map((p, i) => (i === 0 ? "M" : "L") + X(p[0]).toFixed(1) + " " + Y(p[1]).toFixed(1)).join("");
        if (se.area && seg.length > 1) {
          const a = d + `L${X(seg[seg.length - 1][0])} ${H - padB}L${X(seg[0][0])} ${H - padB}Z`;
          el("path", { d: a, fill: se.color, opacity: 0.14, stroke: "none" }, s);
        }
        el("path", { d, fill: "none", stroke: se.color, "stroke-width": se.width || 2, "stroke-linejoin": "round", "stroke-linecap": "round" }, s);
      }
      if (se.dots) pts.forEach((p) => el("circle", { cx: X(p[0]), cy: Y(p[1]), r: 3, fill: se.color }, s));
    }
    // hover
    const cursor = el("line", { y1: padT, y2: H - padB, stroke: css("--muted"), "stroke-width": 1, opacity: 0 }, s);
    const hit = el("rect", { x: padL, y: 0, width: w - padL, height: H, fill: "transparent" }, s);
    const move = (evt) => {
      const r = s.getBoundingClientRect();
      const px = ((evt.touches ? evt.touches[0].clientX : evt.clientX) - r.left) * (w / r.width);
      const xv = x0 + ((px - padL) / (w - padL - 4)) * (x1 - x0);
      const parts = [];
      for (const se of series) {
        let best = null;
        for (const p of se.points) if (p[1] != null && (!best || Math.abs(p[0] - xv) < Math.abs(best[0] - xv))) best = p;
        if (best) parts.push((se.label ? se.label + " " : "") + (se.fmt || opts.yFmt || ((v) => Math.round(v)))(best[1]));
      }
      cursor.setAttribute("x1", px); cursor.setAttribute("x2", px); cursor.setAttribute("opacity", 0.5);
      showTip(evt, xf(xv) + " · " + parts.join(" · "));
    };
    hit.addEventListener("mousemove", move); hit.addEventListener("touchmove", move, { passive: true });
    hit.addEventListener("mouseleave", () => { hideTip(); cursor.setAttribute("opacity", 0); });
    hit.addEventListener("touchend", () => setTimeout(hideTip, 1200));
  }

  /** Bars. items: [{label, value, color, tip}] ; opts.goal draws a dashed line, opts.avg a dotted average */
  function bars(container, items, opts = {}) {
    const H = opts.height || 140, padB = 18;
    const { s, w } = svgFor(container, H);
    if (!items.some((i) => i.value)) { container.innerHTML = '<div class="empty small">Noch keine Daten</div>'; return; }
    const vals = items.map((i) => i.value || 0);
    const base = opts.fromZero === false ? Math.min(...vals.filter((v) => v)) * 0.95 : 0;
    const max = Math.max(...vals, opts.goal || 0) - base || 1;
    const slot = w / items.length, bw = Math.max(3, Math.min(28, slot * 0.62));
    items.forEach((it, i) => {
      const x = i * slot + (slot - bw) / 2;
      const hgt = it.value ? Math.max(3, ((it.value - base) / max) * (H - padB - 6)) : 3;
      const r = el("rect", { x, y: H - padB - hgt, width: bw, height: hgt, rx: Math.min(6, bw / 2), fill: it.value ? (it.color || css("--light")) : css("--surface2") }, s);
      const t = it.tip || `${it.label}: ${it.value ?? "–"}`;
      r.addEventListener("mousemove", (e) => showTip(e, t)); r.addEventListener("mouseleave", hideTip);
      r.addEventListener("touchstart", (e) => { showTip(e, t); setTimeout(hideTip, 1500); }, { passive: true });
      const every = Math.ceil(items.length / (opts.labels || 7));
      if ((items.length - 1 - i) % every === 0) el("text", { x: x + bw / 2, y: H - 3, "text-anchor": "middle" }, s).textContent = it.label;
    });
    const line = (v, dash, color) => {
      const y = H - padB - ((v - base) / max) * (H - padB - 6);
      el("line", { x1: 0, x2: w, y1: y, y2: y, stroke: color, "stroke-dasharray": dash, "stroke-width": 1.5 }, s);
    };
    if (opts.goal) line(opts.goal, "6 5", css("--brand"));
    if (opts.avg) line(opts.avg, "2 4", css("--muted"));
  }

  /** Stacked bars (sleep stages per night). items: [{label, parts:[{v,color,name}], tip}] */
  function stacked(container, items, opts = {}) {
    const H = opts.height || 170, padB = 18;
    const { s, w } = svgFor(container, H);
    if (!items.length) { container.innerHTML = '<div class="empty small">Noch keine Daten</div>'; return; }
    const max = Math.max(...items.map((i) => i.parts.reduce((a, p) => a + (p.v || 0), 0)), opts.goal || 0) || 1;
    const slot = w / items.length, bw = Math.max(4, Math.min(30, slot * 0.62));
    items.forEach((it, i) => {
      const x = i * slot + (slot - bw) / 2;
      let y = H - padB;
      const g = el("g", {}, s);
      it.parts.forEach((p) => {
        const hgt = ((p.v || 0) / max) * (H - padB - 6);
        if (hgt <= 0) return;
        y -= hgt;
        el("rect", { x, y, width: bw, height: hgt, fill: p.color }, g);
      });
      g.addEventListener("mousemove", (e) => showTip(e, it.tip)); g.addEventListener("mouseleave", hideTip);
      g.addEventListener("touchstart", (e) => { showTip(e, it.tip); setTimeout(hideTip, 1800); }, { passive: true });
      const every = Math.ceil(items.length / (opts.labels || 7));
      if ((items.length - 1 - i) % every === 0) el("text", { x: x + bw / 2, y: H - 3, "text-anchor": "middle" }, s).textContent = it.label;
    });
    if (opts.goal) {
      const y = H - padB - (opts.goal / max) * (H - padB - 6);
      el("line", { x1: 0, x2: w, y1: y, y2: y, stroke: css("--brand"), "stroke-dasharray": "6 5", "stroke-width": 1.5 }, s);
    }
  }

  /** Hypnogram: one row per stage (awake on top), blocks over time. */
  function hypnogram(container, stages, start, end, opts = {}) {
    const H = opts.height || 120;
    const rows = ["awake", "rem", "light", "deep"], names = { awake: "Wach", rem: "REM", light: "Leicht", deep: "Tief" };
    const colors = { awake: css("--awake"), rem: css("--rem"), light: css("--light"), deep: css("--deep") };
    const { s, w } = svgFor(container, H);
    if (!stages.length) { container.innerHTML = '<div class="empty small">Keine Schlafphasen für diese Nacht</div>'; return; }
    const padL = 44, padB = 18, rh = (H - padB) / rows.length;
    const X = (t) => padL + ((t - start) / (end - start || 1)) * (w - padL);
    rows.forEach((r, i) => {
      el("text", { x: 0, y: i * rh + rh / 2 + 4 }, s).textContent = names[r];
      el("line", { x1: padL, x2: w, y1: (i + 1) * rh, y2: (i + 1) * rh, stroke: css("--line") }, s);
    });
    for (const st of stages) {
      const i = rows.indexOf(st.stage); if (i < 0) continue;
      const x = X(st.start_ts), x2 = X(st.end_ts);
      const r = el("rect", { x, y: i * rh + 3, width: Math.max(1, x2 - x), height: rh - 6, rx: 3, fill: colors[st.stage] }, s);
      const t = `${names[st.stage]} ${fmtTime(st.start_ts)}–${fmtTime(st.end_ts)} (${Math.round((st.end_ts - st.start_ts) / 60)} min)`;
      r.addEventListener("mousemove", (e) => showTip(e, t)); r.addEventListener("mouseleave", hideTip);
    }
    for (let k = 0; k <= 4; k++) {
      const t = start + ((end - start) * k) / 4;
      el("text", { x: X(t), y: H - 3, "text-anchor": k === 0 ? "start" : k === 4 ? "end" : "middle" }, s).textContent = fmtTime(t);
    }
  }

  /** Ring 0..100 */
  function ring(container, value, color) {
    const size = 132, r = 54, c = 2 * Math.PI * r;
    container.textContent = "";
    const s = el("svg", { width: size, height: size, viewBox: `0 0 ${size} ${size}` }, container);
    el("circle", { cx: 66, cy: 66, r, fill: "none", stroke: css("--surface2"), "stroke-width": 12 }, s);
    el("circle", { cx: 66, cy: 66, r, fill: "none", stroke: color, "stroke-width": 12, "stroke-linecap": "round",
                   "stroke-dasharray": `${(c * (value || 0)) / 100} ${c}` }, s);
  }

  function fmtTime(ts) { return new Date(ts * 1000).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" }); }

  window.Charts = { line, bars, stacked, hypnogram, ring, css, hideTip };
})();
