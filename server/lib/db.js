// Licenses in one JSON file (low volume), written atomically.
"use strict";
const fs = require("fs");
const path = require("path");

const FILE = process.env.DB_FILE || path.join(__dirname, "..", "data", "licenses.json");
let data = { licenses: [], refunds: [] };
try { data = JSON.parse(fs.readFileSync(FILE, "utf8")); } catch { /* first run */ }

function save() {
  fs.mkdirSync(path.dirname(FILE), { recursive: true });
  const tmp = FILE + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(data, null, 1), { mode: 0o600 });
  fs.renameSync(tmp, FILE);
}

module.exports = {
  byKey: key => data.licenses.find(l => l.key === key),
  bySession: id => data.licenses.find(l => l.session === id),
  create(fields) {
    const lic = { ...fields, created: new Date().toISOString(), devices: [], revoked: false };
    data.licenses.push(lic);
    save();
    return lic;
  },
  touchDevice(key, id, name, version) {
    const lic = this.byKey(key);
    const now = new Date().toISOString();
    const d = lic.devices.find(x => x.id === id);
    if (d) Object.assign(d, { name, version, seen: now });
    else lic.devices.push({ id, name, version, added: now, seen: now });
    save();
  },
  removeDevice(key, id) {
    const lic = this.byKey(key);
    lic.devices = lic.devices.filter(d => d.id !== id);
    save();
  },
  revokeByPaymentIntent(pi, reason) {
    for (const l of data.licenses) if (l.paymentIntent === pi && !l.revoked) { l.revoked = true; l.revokedReason = reason; }
    save();
  },
  refunded: id => data.refunds.some(r => r.session === id),
  markRefunded(session, email) {
    data.refunds.push({ session, email, at: new Date().toISOString(), reason: "CN" });
    save();
  },
};
