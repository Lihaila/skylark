// Skylark for Windows and Linux: purchase (Stripe Checkout, US$2.99) and license activation.
// Runs behind Caddy on lihaila.com:
//   GET  /skylark/buy               -> region check, then Stripe Checkout
//   GET  /skylark/thanks?session_id -> shows the license key + download link
//   POST /skylark/api/stripe        -> Stripe webhook (issues keys, refunds mainland China)
//   POST /skylark/api/activate      -> {key, device, name} -> signed token (max 3 PCs per key)
//   POST /skylark/api/deactivate    -> frees a PC slot
// Not sold in mainland China or France: IPs there can't start a checkout, and payments with a billing
// address or card from either are refunded automatically without a key. Activation refuses China IPs.
"use strict";
const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { URL } = require("url");
const db = require("./lib/db");
const geo = require("./lib/geo");
const mail = require("./lib/mail");
const pages = require("./lib/pages");

loadEnv(path.join(__dirname, ".env"));
const PORT = Number(process.env.PORT || 8794);
const SITE = process.env.SITE || "https://lihaila.com";
const PRICE_CENTS = 299;
const MAX_DEVICES = 3;
const TOKEN_DAYS = 45;
const stripe = process.env.STRIPE_SECRET_KEY ? require("stripe")(process.env.STRIPE_SECRET_KEY) : null;
const signingKey = crypto.createPrivateKey(fs.readFileSync(process.env.LICENSE_KEY_FILE || path.join(__dirname, "secrets/license-ed25519.pem")));

function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, "utf8").split("\n")) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2].replace(/^["']|["']$/g, "");
  }
}

// ---------- helpers
function clientIP(req) {
  return (req.headers["x-forwarded-for"] || "").split(",")[0].trim() || req.socket.remoteAddress || "";
}
function send(res, code, body, type = "application/json") {
  const data = typeof body === "string" ? body : JSON.stringify(body);
  res.writeHead(code, { "Content-Type": type + "; charset=utf-8", "Cache-Control": "no-store" });
  res.end(data);
}
function redirect(res, to) { res.writeHead(303, { Location: to }); res.end(); }
function readBody(req, limit = 1 << 20) {
  return new Promise((resolve, reject) => {
    const chunks = []; let n = 0;
    req.on("data", c => { n += c.length; if (n > limit) { reject(new Error("too large")); req.destroy(); } else chunks.push(c); });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}
function b64url(buf) { return Buffer.from(buf).toString("base64url"); }
function maskKey(k) { return k.slice(0, 4) + "…" + k.slice(-4); }
function newKey() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
  const part = () => Array.from(crypto.randomBytes(4), b => alphabet[b % alphabet.length]).join("");
  return `SKY-${part()}-${part()}-${part()}-${part()}`;
}
function signToken(lic, device) {
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(JSON.stringify({ key: maskKey(lic.key), email: lic.email, device, iat: now, exp: now + TOKEN_DAYS * 86400 }));
  return b64url(payload) + "." + b64url(crypto.sign(null, payload, signingKey));
}

// Small in-memory rate limit for the activation API.
const hits = new Map();
function limited(ip, max = 20, windowMs = 10 * 60 * 1000) {
  const now = Date.now();
  const h = (hits.get(ip) || []).filter(t => now - t < windowMs);
  h.push(now); hits.set(ip, h);
  return h.length > max;
}

// ---------- purchase
async function buy(req, res, url) {
  const lang = pages.lang(url.searchParams.get("lang"), req.headers["accept-language"]);
  const ip = clientIP(req);
  if (geo.isChina(ip) || geo.isFrance(ip)) return send(res, 403, pages.notAvailable(lang), "text/html");
  if (!stripe) return send(res, 503, pages.message(lang, "Payments are not set up yet."), "text/html");
  const session = await stripe.checkout.sessions.create({
    mode: "payment",
    line_items: [{ quantity: 1, price_data: { currency: "usd", unit_amount: PRICE_CENTS,
      product_data: { name: "Skylark for Windows and Linux", description: "License for up to 3 PCs. Proxy client only: no servers included." } } }],
    billing_address_collection: "required",
    customer_creation: "always",
    locale: lang === "en" ? "en" : lang === "zh-Hant" ? "zh-TW" : lang === "ja" ? "ja" : lang === "ko" ? "ko" : "auto",
    success_url: `${SITE}/skylark/thanks?session_id={CHECKOUT_SESSION_ID}&lang=${lang}`,
    cancel_url: `${SITE}/en/skylark`,
    metadata: { product: "skylark-windows", ip_country_checked: "1" },
    payment_intent_data: { metadata: { product: "skylark-windows" } },
  });
  redirect(res, session.url);
}

// Not sold in mainland China or France: a billing or card country there => refund, no key.
const NOT_SOLD = new Set(["CN", "FR"]);
// Returns the license (or null if refunded).
async function fulfil(sessionId) {
  const existing = db.bySession(sessionId);
  if (existing) return existing;
  const s = await stripe.checkout.sessions.retrieve(sessionId, { expand: ["payment_intent.latest_charge"] });
  if (s.payment_status !== "paid" || s.metadata?.product !== "skylark-windows") return null;
  const billing = s.customer_details?.address?.country || "";
  const charge = s.payment_intent?.latest_charge;
  const card = charge?.payment_method_details?.card?.country || "";
  const email = s.customer_details?.email || "";
  if (NOT_SOLD.has(billing) || NOT_SOLD.has(card)) {
    if (!db.refunded(sessionId)) {
      await stripe.refunds.create({ payment_intent: s.payment_intent.id, reason: "requested_by_customer",
        metadata: { reason: "not sold in this region", billing, card } });
      db.markRefunded(sessionId, email, billing || card);
      await mail.refundNotice(email).catch(e => console.error("mail", e.message));
    }
    return null;
  }
  const lic = db.create({ key: newKey(), email, session: sessionId, paymentIntent: s.payment_intent?.id, country: billing });
  await mail.licenseEmail(lic).catch(e => console.error("mail", e.message));
  return lic;
}

async function thanks(req, res, url) {
  const lang = pages.lang(url.searchParams.get("lang"), req.headers["accept-language"]);
  const id = url.searchParams.get("session_id") || "";
  if (!stripe || !/^cs_[A-Za-z0-9_]+$/.test(id)) return send(res, 400, pages.message(lang, "Missing order."), "text/html");
  const lic = await fulfil(id);
  if (!lic) {
    if (db.refunded(id)) return send(res, 200, pages.refunded(lang), "text/html");
    return send(res, 202, pages.message(lang, "Your payment is still being processed. Refresh this page in a minute."), "text/html");
  }
  send(res, 200, pages.thanks(lang, lic), "text/html");
}

async function webhook(req, res) {
  const raw = await readBody(req);
  let event;
  try {
    event = stripe.webhooks.constructEvent(raw, req.headers["stripe-signature"], process.env.STRIPE_WEBHOOK_SECRET);
  } catch (e) {
    return send(res, 400, { error: "bad signature" });
  }
  if (event.type === "checkout.session.completed" || event.type === "checkout.session.async_payment_succeeded") {
    await fulfil(event.data.object.id);
  } else if (event.type === "charge.refunded" || event.type === "charge.dispute.created") {
    const pi = event.data.object.payment_intent;
    if (pi) db.revokeByPaymentIntent(pi, event.type);
  }
  send(res, 200, { received: true });
}

// ---------- activation
async function activate(req, res, deactivate = false) {
  const ip = clientIP(req);
  if (limited(ip)) return send(res, 429, { error: "network" });
  let body;
  try { body = JSON.parse((await readBody(req, 4096)).toString()); } catch { return send(res, 400, { error: "invalidKey" }); }
  const key = String(body.key || "").toUpperCase().replace(/\s+/g, "");
  const device = String(body.device || "");
  if (!/^[0-9a-f]{32}$/.test(device)) return send(res, 400, { error: "invalidKey" });
  const lic = db.byKey(key);
  if (!lic) return send(res, 404, { error: "invalidKey" });
  if (lic.revoked) return send(res, 403, { error: "revoked" });
  if (deactivate) {
    db.removeDevice(key, device);
    return send(res, 200, { ok: true });
  }
  // OWNER_KEYS (comma-separated, in .env): the developer's own test keys may activate from China.
  const owner = String(process.env.OWNER_KEYS || "").split(",").map(k => k.trim()).filter(Boolean);
  if (geo.isChina(ip) && !owner.includes(key)) return send(res, 403, { error: "region" });
  const devices = lic.devices || [];
  if (!devices.some(d => d.id === device) && devices.length >= MAX_DEVICES) return send(res, 409, { error: "tooManyDevices" });
  db.touchDevice(key, device, String(body.name || "").slice(0, 64), String(body.version || "").slice(0, 16));
  send(res, 200, { token: signToken(lic, device) });
}

// ---------- server
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, SITE);
  const p = url.pathname.replace(/\/+$/, "");
  try {
    if (req.method === "GET" && p === "/skylark/buy") return await buy(req, res, url);
    if (req.method === "GET" && p === "/skylark/thanks") return await thanks(req, res, url);
    if (req.method === "POST" && p === "/skylark/api/stripe" && stripe) return await webhook(req, res);
    if (req.method === "POST" && p === "/skylark/api/activate") return await activate(req, res);
    if (req.method === "POST" && p === "/skylark/api/deactivate") return await activate(req, res, true);
    if (req.method === "GET" && p === "/skylark/api/health") return send(res, 200, { ok: true, stripe: !!stripe, geo: geo.count(), geoFR: geo.countFrance() });
    send(res, 404, { error: "not found" });
  } catch (e) {
    console.error(req.method, p, e);
    send(res, 500, { error: "server" });
  }
});

geo.load().finally(() => server.listen(PORT, "127.0.0.1", () => console.log(`skylark-license on 127.0.0.1:${PORT}`)));
module.exports = { newKey, signToken, maskKey };
