// Purchase emails over SMTP (same mailbox setup as the other lihaila.com services).
"use strict";
const nodemailer = require("nodemailer");
const { DOWNLOAD } = require("./pages");

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
    return send(lic.email, "Your Skylark license key", `Thank you for buying Skylark for Windows.

Your license key:

    ${lic.key}

1. Download Skylark: ${DOWNLOAD}
2. Open it (allow the administrator prompt) and paste your key.

You can use it on up to 3 PCs. To move it to another PC, open Settings and choose "Remove from this PC".

Skylark is a client app and includes no servers: you need your own server or subscription.
Skylark is not offered in mainland China.

Questions: just reply to this email.
Lihai La LLC · https://lihaila.com/en/skylark`);
  },
  refundNotice(email) {
    return send(email, "Your Skylark order was refunded", `Skylark is not offered or supported in mainland China, so your payment has been refunded in full.
It can take 5–10 days to show on your statement.

Questions: just reply to this email.
Lihai La LLC`);
  },
};
