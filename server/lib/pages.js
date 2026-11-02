// Minimal pages for the purchase flow, in the lihaila.com product-page languages.
"use strict";

const DOWNLOAD = "https://lihaila.com/downloads/skylark/Skylark-Windows.zip";
const T = {
  en: {
    thanksTitle: "Thank you!", thanksText: "Here is your Skylark license key. We've also emailed it to {email}.",
    step1: "Download Skylark for Windows", step2: "Open it (allow the administrator prompt) and paste your key.",
    download: "Download for Windows", devices: "Use it on up to 3 PCs. You can move it in Settings → Remove from this PC.",
    clientOnly: "Skylark is a client app and includes no servers: you need your own server or subscription.",
    notAvailTitle: "Not available in your region", notAvail: "Skylark for Windows is not sold in mainland China or France.",
    refundTitle: "Order refunded", refund: "Skylark for Windows is not sold in your region, so your payment has been refunded in full. It can take 5–10 days to show on your statement.",
    help: "Questions: support@lihaila.com", back: "Back to Skylark", home: "lihaila.com home",
  },
  ja: {
    thanksTitle: "ご購入ありがとうございます", thanksText: "Skylark のライセンスキーです。{email} にもメールでお送りしました。",
    step1: "Skylark for Windows をダウンロード", step2: "起動して（管理者の確認を許可）、キーを貼り付けてください。",
    download: "Windows 版をダウンロード", devices: "最大 3 台の PC で使えます。設定 →「この PC から削除」で移せます。",
    clientOnly: "Skylark はクライアントアプリでサーバーは含まれません。ご自身のサーバーまたはサブスクリプションが必要です。",
    notAvailTitle: "お住まいの地域ではご利用いただけません", notAvail: "Skylark for Windows は中国本土とフランスでは販売していません。",
    refundTitle: "ご注文を返金しました", refund: "Skylark for Windows はお住まいの地域では販売していないため、全額を返金しました。明細への反映には 5〜10 日かかる場合があります。",
    help: "お問い合わせ: support@lihaila.com", back: "Skylark に戻る", home: "lihaila.com ホーム",
  },
  ko: {
    thanksTitle: "구매해 주셔서 감사합니다", thanksText: "Skylark 라이선스 키입니다. {email}(으)로도 보내 드렸습니다.",
    step1: "Skylark for Windows 다운로드", step2: "실행한 뒤(관리자 권한 허용) 키를 붙여 넣으세요.",
    download: "Windows용 다운로드", devices: "PC 최대 3대에서 쓸 수 있습니다. 설정 → '이 PC에서 제거'로 옮길 수 있습니다.",
    clientOnly: "Skylark는 클라이언트 앱이며 서버를 포함하지 않습니다. 본인의 서버나 구독이 필요합니다.",
    notAvailTitle: "이 지역에서는 이용할 수 없습니다", notAvail: "Skylark for Windows는 중국 본토와 프랑스에서 판매하지 않습니다.",
    refundTitle: "주문이 환불되었습니다", refund: "Skylark for Windows는 이 지역에서 판매하지 않아 전액 환불해 드렸습니다. 명세서에 반영되기까지 5~10일이 걸릴 수 있습니다.",
    help: "문의: support@lihaila.com", back: "Skylark로 돌아가기", home: "lihaila.com 홈",
  },
};
const PAGE = { en: "/en/skylark", ja: "/jp/skylark", ko: "/kr/skylark" };

const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function lang(q, accept = "") {
  if (T[q]) return q;
  if (q === "jp") return "ja";
  if (q === "kr") return "ko";
  const a = String(accept).toLowerCase();
  if (a.startsWith("ja")) return "ja";
  if (a.startsWith("ko")) return "ko";
  return "en";
}

const HOME = { en: "/en/", ja: "/jp/", ko: "/kr/" };
function nav(l) {
  const t = T[l];
  return `<p class="nav"><a href="${PAGE[l]}">← ${t.back}</a> · <a href="${HOME[l]}">${t.home}</a></p>`;
}
function shell(l, title, body) {
  return `<!DOCTYPE html><html lang="${l}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)} · Skylark</title><meta name="robots" content="noindex">
<style>
body{font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","Hiragino Sans","Malgun Gothic",sans-serif;background:#12151c;color:#e6e9f0;margin:0;display:grid;place-items:center;min-height:100vh}
main{max-width:520px;padding:40px 24px;text-align:center}
img{width:88px;height:88px;border-radius:20px}
h1{font-size:28px;margin:16px 0 8px}.muted{color:#9aa3b5;font-size:14px}
.key{font:600 22px/1.4 Consolas,Menlo,monospace;letter-spacing:1px;background:#1b1f29;border:1px solid #2a2f3a;border-radius:12px;padding:16px;margin:20px 0;user-select:all;word-break:break-all}
ol{text-align:left;padding-left:22px}
.btn{display:inline-block;background:linear-gradient(135deg,#3b5bfd,#6a4bff);color:#fff;text-decoration:none;font-weight:600;padding:12px 22px;border-radius:12px;margin:12px 0}
a{color:#8ea2ff}.nav{margin-top:28px;font-size:14px}
</style></head><body><main><a href="${PAGE[l]}"><img src="/attached_assets/products/skylark-icon.png" alt="Skylark"></a>${body}${nav(l)}</main></body></html>`;
}

module.exports = {
  lang,
  thanks(l, lic) {
    const t = T[l];
    return shell(l, t.thanksTitle, `<h1>${t.thanksTitle}</h1><p>${esc(t.thanksText.replace("{email}", lic.email))}</p>
<div class="key">${esc(lic.key)}</div>
<ol><li>${t.step1}</li><li>${t.step2}</li></ol>
<a class="btn" href="${DOWNLOAD}">${t.download}</a>
<p class="muted">${t.devices}</p><p class="muted">${t.clientOnly}</p><p class="muted">${t.help}</p>`);
  },
  notAvailable(l) {
    const t = T[l];
    return shell(l, t.notAvailTitle, `<h1>${t.notAvailTitle}</h1><p>${t.notAvail}</p><p class="muted">${t.help}</p>`);
  },
  refunded(l) {
    const t = T[l];
    return shell(l, t.refundTitle, `<h1>${t.refundTitle}</h1><p>${t.refund}</p><p class="muted">${t.help}</p>`);
  },
  message(l, msg) {
    return shell(l, "Skylark", `<h1>Skylark</h1><p>${esc(msg)}</p>`);
  },
  DOWNLOAD,
};
