// Purchase emails over SMTP (same mailbox setup as the other lihaila.com services).
"use strict";
const nodemailer = require("nodemailer");
const { DOWNLOAD, LINUX_X64, LINUX_ARM } = require("./pages");

let transport = null;
function tx() {
  if (transport || !process.env.SMTP_HOST) return transport;
  transport = nodemailer.createTransport({
    host: process.env.SMTP_HOST,
    port: Number(process.env.SMTP_PORT || 465),
    secure: Number(process.env.SMTP_PORT || 465) === 465,
    auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS },
  });
  return transport;
}
// The SMTP account may only send as contact@lihaila.com.
const FROM = () => process.env.MAIL_FROM || "Skylark <contact@lihaila.com>";

async function send(to, subject, text) {
  const t = tx();
  if (!t || !to) { console.log("mail (not sent):", to, subject); return; }
  await t.sendMail({ from: FROM(), to, replyTo: "support@lihaila.com", subject, text });
}

module.exports = {
  licenseEmail(lic) {
    return send(lic.email, "Your Skylark license key", `Thank you for buying Skylark.

Your license key:

    ${lic.key}

Windows
1. Download: ${DOWNLOAD}
2. Unzip it and open Skylark.exe (allow the administrator prompt), then paste your key.

Linux (needs WebKitGTK 4.1)
1. Download for x64 (most PCs): ${LINUX_X64}
   or for ARM64: ${LINUX_ARM}
2. Run: tar xzf Skylark-Linux-*.tar.gz && cd skylark && ./install.sh
3. Open Skylark from your app launcher, then paste your key.

You can use it on up to 3 PCs, Windows or Linux in any mix. To move it to another PC, open Settings and choose "Remove from this PC".

Skylark is a client app and includes no servers: you need your own server or subscription.
Skylark for Windows and Linux is not sold in mainland China or France.

Questions: just reply to this email.
Lihai La LLC · https://lihaila.com/en/skylark`);
  },
  refundNotice(email) {
    return send(email, "Your Skylark order was refunded", `Skylark for Windows and Linux is not sold in your region, so your payment has been refunded in full.
It can take 5–10 days to show on your statement.

Questions: just reply to this email.
Lihai La LLC`);
  },
};
