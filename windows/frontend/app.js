// Skylark for Windows UI. Calls the Go side through the Wails bindings (window.go.main.App).
const api = () => window.go.main.App;
let state = null, info = null, status = { connected: false }, busy = false, lat = {};

// ---------- i18n
function lang() {
  const pref = state?.settings?.language;
  if (pref) return pref;
  const l = (navigator.language || "en").toLowerCase();
  if (l.startsWith("zh")) return /tw|hk|mo|hant/.test(l) ? "zh-Hant" : "zh-Hans";
  return "en";
}
function t(key, vars = {}) {
  let s = (STRINGS[lang()] || STRINGS.en)[key] ?? STRINGS.en[key] ?? key;
  for (const [k, v] of Object.entries(vars)) s = s.replace(`{${k}}`, v);
  return s;
}
function countryName(code) {
  if (!code) return "?";
  try { return new Intl.DisplayNames([lang()], { type: "region" }).of(code) || code; } catch { return code; }
}
function applyStrings() {
  document.documentElement.lang = lang();
  document.querySelectorAll("[data-t]").forEach(el => el.textContent = t(el.dataset.t));
  document.querySelectorAll("[data-ph]").forEach(el => el.placeholder = t(el.dataset.ph));
  document.getElementById("pingBtn").title = t("test");
}
const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function toast(msg) {
  const el = $("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.remove("show"), 2600);
}
function errText(e) {
  const m = String(e?.message ?? e ?? "");
  const k = "err" + m;
  return STRINGS.en[k] ? t(k) : (STRINGS.en[m] ? t(m) : m);
}

// ---------- tabs
document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => showTab(b.dataset.tab));
function showTab(name) {
  document.querySelectorAll(".tabs button").forEach(b => b.classList.toggle("active", b.dataset.tab === name));
  document.querySelectorAll(".tab").forEach(s => s.classList.toggle("hidden", s.id !== "tab-" + name));
}

// ---------- rendering
function fmtBytes(n) {
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return (i ? n.toFixed(1) : n) + " " + u[i];
}
function selected() {
  return state.nodes.find(n => n.id === state.selectedId) || state.nodes[0];
}
function nodeMeta(n) {
  try { const o = JSON.parse(n.outbound); return `${o.type} · ${o.server}:${o.server_port}`; } catch { return ""; }
}
function latHtml(id) {
  const v = lat[id];
  if (v === undefined) return "";
  if (v === -2) return `<span class="lat muted">…</span>`;
  if (v < 0) return `<span class="lat bad">${t("timeout")}</span>`;
  return `<span class="lat ${v < 200 ? "good" : v < 500 ? "mid" : "bad"}">${v} ms</span>`;
}

function renderHome() {
  const p = $("power");
  p.classList.toggle("on", status.connected);
  p.classList.toggle("busy", busy);
  $("stateText").textContent = busy ? t("connecting") : status.connected ? t("connected") : t("notConnected");
  $("up").textContent = fmtBytes(status.up || 0) + "/s";
  $("down").textContent = fmtBytes(status.down || 0) + "/s";
  const n = selected();
  $("currentServer").innerHTML = n
    ? `<div class="grow"><div class="server-name">${esc(n.name)}</div><div class="server-meta">${esc(nodeMeta(n))}</div></div><span>›</span>`
    : `<div><div class="server-name">${t("noServer")}</div><div class="server-meta">${t("addServerHint")}</div></div>`;
  document.querySelectorAll("#modes button").forEach(b => b.classList.toggle("active", b.dataset.mode === state.mode));
  $("modeHint").textContent = state.mode === "RULE" ? t("modeRuleHint", { country: countryName(info.country) })
    : state.mode === "GLOBAL" ? t("modeGlobalHint") : t("modeDirectHint");
}
function tickTimer() {
  if (!status.connected || !status.since) { $("timer").textContent = ""; return; }
  const s = Math.floor((Date.now() - status.since) / 1000);
  const p = x => String(x).padStart(2, "0");
  $("timer").textContent = `${p(Math.floor(s / 3600))}:${p(Math.floor(s / 60) % 60)}:${p(s % 60)}`;
}
setInterval(tickTimer, 1000);

function renderServers() {
  const groups = [{ id: "", name: t("manual"), nodes: state.nodes.filter(n => !n.subId) }];
  for (const s of state.subs) groups.push({ id: s.id, name: s.name, sub: s, nodes: state.nodes.filter(n => n.subId === s.id) });
  const sel = selected()?.id;
  $("serverList").innerHTML = groups.filter(g => g.nodes.length || g.sub).map(g => `
    <div class="group-title"><span>${esc(g.name)} · ${g.nodes.length}</span>
      ${g.sub ? `<span><button class="link" data-upd="${g.id}">${t("update")}</button><button class="link danger" data-delsub="${g.id}">${t("delete")}</button></span>` : ""}
    </div>
    ${g.nodes.map(n => `
      <div class="node ${n.id === sel ? "selected" : ""}" data-sel="${n.id}">
        <span class="dot"></span>
        <div class="grow"><div class="server-name">${esc(n.name)}</div><div class="server-meta">${esc(nodeMeta(n))}</div></div>
        ${latHtml(n.id)}
        ${n.subId ? "" : `<button class="del" data-del="${n.id}" title="${t("delete")}">✕</button>`}
      </div>`).join("")}`).join("");
}

function renderRules() {
  const s = state.settings;
  $("bypassHome").checked = s.bypassHome;
  $("bypassChina").checked = s.bypassChina;
  const sel = $("countrySel");
  if (!sel.options.length || sel.dataset.lang !== lang()) {
    const codes = "AE AR AT AU BD BE BR CA CH CL CO CZ DE DK EG ES FI FR GB GR HK HU ID IE IL IN IT JP KE KR KZ MA MO MX MY NG NL NO NZ PE PH PK PL PT QA RO RU SA SE SG TH TR TW UA US VN ZA".split(" ");
    const opts = codes.map(c => [c, countryName(c)]).sort((a, b) => a[1].localeCompare(b[1], lang()));
    sel.innerHTML = `<option value="">${esc(t("auto", { country: countryName(info.detectedCountry) }))}</option>` +
      opts.map(([c, n]) => `<option value="${c}">${esc(n)}</option>`).join("");
    sel.dataset.lang = lang();
  }
  sel.value = s.homeCountry || "";
  $("ruleList").innerHTML = state.rules.length ? state.rules.map(r => `
    <div class="rule"><span class="tag">${r.type.replace("_", "-")}</span><span class="grow">${esc(r.value)}</span>
      <span class="tag">${t("action" + r.action)}</span><button class="del" data-delrule="${r.id}">✕</button></div>`).join("")
    : `<p class="muted small">${t("noRules")}</p>`;
}

function renderSettings() {
  const s = state.settings;
  $("langSel").value = s.language || "";
  $("remoteDns").value = s.remoteDns;
  $("directDns").value = s.directDns;
  $("ipv6").checked = s.ipv6;
  $("ver").textContent = info.version;
  $("deactivateBtn").classList.toggle("hidden", info.licenseKey === "DEV"); // test builds have no real license
  $("lic").textContent = info.licensed ? `${info.licenseEmail} · ${info.licenseKey}` : "—";
}

function render() {
  applyStrings();
  $("activation").classList.toggle("hidden", info.licensed);
  $("main").classList.toggle("hidden", !info.licensed);
  renderHome(); renderServers(); renderRules(); renderSettings();
  enhanceSelects();
}

// ---------- dropdowns
// GTK draws the opened list of a native <select> itself, ignoring the app's dark theme, so each
// select gets our own list. The hidden <select> stays the source of truth and fires "change".
function closeDropdowns() { document.querySelectorAll(".dd-list").forEach(l => l.remove()); }
function enhanceSelects() {
  document.querySelectorAll("select").forEach(sel => {
    let btn = sel.nextElementSibling;
    if (!btn || !btn.classList.contains("dd-btn")) {
      btn = document.createElement("button");
      btn.type = "button";
      btn.className = "dd-btn";
      sel.after(btn);
      sel.classList.add("dd-native");
      btn.onclick = e => {
        e.stopPropagation();
        const wasOpen = btn.dataset.open === "1";
        closeDropdowns();
        document.querySelectorAll(".dd-btn").forEach(b => b.dataset.open = "");
        if (wasOpen) return;
        btn.dataset.open = "1";
        const list = document.createElement("div");
        list.className = "dd-list";
        [...sel.options].forEach(o => {
          const item = document.createElement("button");
          item.type = "button";
          item.className = "dd-item" + (o.value === sel.value ? " selected" : "");
          item.textContent = o.textContent;
          item.onclick = ev => {
            ev.stopPropagation();
            closeDropdowns();
            btn.dataset.open = "";
            sel.value = o.value;
            btn.textContent = o.textContent;
            sel.dispatchEvent(new Event("change", { bubbles: true }));
          };
          list.appendChild(item);
        });
        document.body.appendChild(list);
        // Fixed to the viewport, flipped upward when there is more room above the button.
        const r = btn.getBoundingClientRect();
        const below = innerHeight - r.bottom - 12, above = r.top - 12;
        const up = below < 180 && above > below;
        list.style.left = r.left + "px";
        list.style.minWidth = r.width + "px";
        list.style.maxHeight = Math.max(up ? above : below, 120) + "px";
        if (up) list.style.bottom = innerHeight - r.top + 4 + "px"; else list.style.top = r.bottom + 4 + "px";
        // Keep it on screen when it is wider than the button (right-hand dropdowns in a narrow window).
        const over = list.getBoundingClientRect().right - (innerWidth - 12);
        if (over > 0) list.style.left = Math.max(12, r.left - over) + "px";
        list.querySelector(".selected")?.scrollIntoView({ block: "nearest" });
      };
    }
    btn.textContent = sel.selectedOptions[0]?.textContent || "";
  });
}
document.addEventListener("click", () => {
  closeDropdowns();
  document.querySelectorAll(".dd-btn").forEach(b => b.dataset.open = "");
});
window.addEventListener("resize", closeDropdowns);
document.addEventListener("keydown", e => { if (e.key === "Escape") document.body.click(); });

// ---------- actions
async function refresh() {
  [state, info, status] = await Promise.all([api().GetState(), api().GetInfo(), api().GetStatus()]);
  render(); tickTimer();
}
const setState = s => { state = s; render(); };

$("power").onclick = async () => {
  if (busy) return;
  $("connError").textContent = "";
  busy = true; renderHome();
  try {
    if (status.connected) await api().Disconnect();
    else await api().Connect();
  } catch (e) { $("connError").textContent = errText(e); }
  busy = false;
  status = await api().GetStatus();
  renderHome(); tickTimer();
};
$("currentServer").onclick = () => showTab("servers");
document.querySelectorAll("#modes button").forEach(b => b.onclick = async () => setState(await api().SetMode(b.dataset.mode)));

async function imported(p) {
  try { const n = await p; toast(t("imported", { n })); state = await api().GetState(); render(); return true; }
  catch (e) { toast(errText(e)); return false; }
}
$("pasteBtn").onclick = () => imported(api().PasteFromClipboard());
$("linksAdd").onclick = async () => { if (await imported(api().ImportText($("linksText").value))) $("linksText").value = ""; };
$("subAdd").onclick = async () => {
  const url = $("subUrl").value.trim();
  if (!url) return;
  $("subAdd").disabled = true;
  if (await imported(api().AddSubscription($("subName").value.trim(), url))) { $("subUrl").value = ""; $("subName").value = ""; }
  $("subAdd").disabled = false;
};
$("pingBtn").onclick = async () => {
  const ids = state.nodes.map(n => n.id);
  ids.forEach(id => lat[id] = -2); renderServers();
  Object.assign(lat, await api().Ping(ids)); renderServers();
};
$("serverList").onclick = async e => {
  const b = e.target.closest("[data-del],[data-upd],[data-delsub],[data-sel]");
  if (!b) return;
  if (b.dataset.del) setState(await api().DeleteNode(b.dataset.del));
  else if (b.dataset.delsub) setState(await api().DeleteSubscription(b.dataset.delsub));
  else if (b.dataset.upd) imported(api().UpdateSubscription(b.dataset.upd));
  else if (b.dataset.sel) setState(await api().SelectNode(b.dataset.sel));
};

async function saveSettings(change) {
  const s = { ...state.settings };
  change(s);
  setState(await api().SaveSettings(s));
  info = await api().GetInfo(); render();
}
$("bypassHome").onchange = e => saveSettings(s => s.bypassHome = e.target.checked);
$("bypassChina").onchange = e => saveSettings(s => s.bypassChina = e.target.checked);
$("countrySel").onchange = e => saveSettings(s => s.homeCountry = e.target.value);
$("langSel").onchange = e => saveSettings(s => s.language = e.target.value);
$("ipv6").onchange = e => saveSettings(s => s.ipv6 = e.target.checked);
$("dnsSave").onclick = () => saveSettings(s => {
  s.remoteDns = $("remoteDns").value.trim() || "1.1.1.1";
  s.directDns = $("directDns").value.trim() || "1.1.1.1";
}).then(() => toast(t("saved")));
$("ruleAdd").onclick = async () => {
  const v = $("ruleValue").value.trim();
  if (!v) return;
  setState(await api().AddRule($("ruleType").value, v, $("ruleAction").value));
  $("ruleValue").value = "";
};
$("ruleValue").onkeydown = e => { if (e.key === "Enter") $("ruleAdd").click(); };
$("ruleList").onclick = async e => {
  const b = e.target.closest("[data-delrule]");
  if (b) setState(await api().DeleteRule(b.dataset.delrule));
};
$("quitBtn").onclick = () => api().Quit();
$("sourceBtn").onclick = () => api().OpenURL("https://github.com/Lihaila/skylark");
$("deactivateBtn").onclick = async () => {
  const btn = $("deactivateBtn");
  btn.disabled = true;
  try { info = await api().Deactivate(); status = { connected: false }; render(); }
  catch (e) { toast(errText(e)); }
  btn.disabled = false;
};

// activation
$("activateBtn").onclick = async () => {
  const btn = $("activateBtn");
  btn.disabled = true; btn.textContent = t("activating"); $("actError").textContent = "";
  try { info = await api().Activate($("keyInput").value); render(); }
  catch (e) { $("actError").textContent = errText(e); }
  btn.disabled = false; btn.textContent = t("activate");
};
$("keyInput").onkeydown = e => { if (e.key === "Enter") $("activateBtn").click(); };
$("buyBtn").onclick = () => api().OpenURL(info.buyUrl);

// live status from Go
window.addEventListener("load", () => {
  window.runtime.EventsOn("status", s => {
    status = { ...status, ...s, connected: s.connected };
    if (s.error) $("connError").textContent = s.error;
    renderHome(); tickTimer();
  });
  refresh();
});
