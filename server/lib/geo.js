// Mainland China and France IP checks from the public ipdeny.com aggregated lists (IPv4 + IPv6), refreshed daily.
// Hong Kong, Macau and Taiwan are separate entries in these lists and are not blocked.
"use strict";
const fs = require("fs");
const path = require("path");
const net = require("net");

const CACHE = path.join(__dirname, "..", "data");
const zones = code => ({
  v4: `https://www.ipdeny.com/ipblocks/data/aggregated/${code}-aggregated.zone`,
  v6: `https://www.ipdeny.com/ipv6/ipaddresses/aggregated/${code}-aggregated.zone`,
});
// cn: not offered at all. fr: not sold (France's encryption import declaration), so checkout only.
const lists = { cn: { list: new net.BlockList(), n: 0 }, fr: { list: new net.BlockList(), n: 0 } };

function build(texts) {
  const bl = new net.BlockList();
  let count = 0;
  for (const [family, text] of Object.entries(texts)) {
    for (const line of text.split("\n")) {
      const [addr, prefix] = line.trim().split("/");
      if (!addr || !prefix) continue;
      try { bl.addSubnet(addr, Number(prefix), family === "v4" ? "ipv4" : "ipv6"); count++; } catch { /* skip bad line */ }
    }
  }
  return [bl, count];
}

async function fetchText(url) {
  const r = await fetch(url, { signal: AbortSignal.timeout(20000) });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.text();
}

async function loadCountry(code) {
  const texts = {};
  for (const [k, url] of Object.entries(zones(code))) {
    const file = path.join(CACHE, `${code}-${k}.zone`);
    try {
      texts[k] = await fetchText(url);
      fs.writeFileSync(file, texts[k]);
    } catch (e) {
      console.error("geo: download failed, using cache", k, e.message);
      try { texts[k] = fs.readFileSync(file, "utf8"); } catch { texts[k] = ""; }
    }
  }
  const [bl, count] = build(texts);
  if (count > 1000) lists[code] = { list: bl, n: count };
  console.log(`geo: ${lists[code].n} ${code} ranges`);
}

async function load() {
  fs.mkdirSync(CACHE, { recursive: true });
  for (const code of Object.keys(lists)) await loadCountry(code);
}

function inList(code, ip) {
  ip = String(ip).replace(/^::ffff:/, "");
  const family = net.isIP(ip);
  return family ? lists[code].list.check(ip, family === 4 ? "ipv4" : "ipv6") : false;
}

setInterval(() => load().catch(() => {}), 24 * 3600 * 1000).unref();

module.exports = {
  load,
  count: () => lists.cn.n,
  countFrance: () => lists.fr.n,
  isChina: ip => inList("cn", ip),
  isFrance: ip => inList("fr", ip),
};
