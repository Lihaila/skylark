# Skylark – Google Play Console checklist and answers

Account: **Lihai La LLC** (organization account, so no 14-day / 12-tester closed test is required).
Upload file: `app/build/outputs/bundle/playRelease/app-play-release.aab` (build with `./gradlew bundlePlayRelease`).

## 0. Before you submit

- [ ] **Back up** `keystore/skylark-release.jks` and `keystore.properties`. This is the **upload key**; Google holds the real app-signing key (Play App Signing).
- [ ] **Make the GitHub repo public** (github.com/Lihaila/skylark) before the app goes live. GPLv3 requires buyers to have access to the source, and the app and privacy policy link to it.
- [ ] **Publish the privacy policy** at a public URL (proposed: `https://lihaila.com/skylark/privacy`, file `store/privacy.html`).
- [ ] **Create a reviewer test server.** Google's reviewers must be able to connect. Add a separate user (its own UUID) on your server for them, so you can revoke it later without affecting yourself. Put its share link in "App access" (step 4).
- [ ] **Record the VPN demo video** (script in step 9) and upload it to YouTube as **Unlisted**.

## 1. Create app

- App name: `Skylark: Smart Proxy Client`
- Default language: English (United States)
- App or game: **App**
- Free or paid: **Paid**. Choose this now: a free app can never be changed to paid.
- Accept the declarations.

## 2. Pricing and countries (Monetize › App pricing)

- Price: **USD 2.99**. Let Play convert local prices.
- Countries: **all available countries except China** (Play doesn't operate there anyway; leave it unchecked).

## 3. Store listing (Grow › Store presence › Main store listing)

Text: copy from `store/listing.md`. Add translations for **Chinese (Simplified) zh-CN** and **Chinese (Traditional) zh-TW**.

| Asset | File |
|---|---|
| App icon 512×512 | `store/icon-512.png` |
| Feature graphic 1024×500 | `store/feature-en.png` (and `-zh-CN`, `-zh-TW` for the translations) |
| Phone screenshots | `store/screenshots/en/1-4.png` (and `zh-CN/`, `zh-TW/`) |

- Category: **Tools**
- Contact email: `support@lihaila.com`
- Website: `https://lihaila.com`

## 4. App content (Policy › App content)

**Privacy policy:** `https://lihaila.com/skylark/privacy`

**App access:** *All or some functionality is restricted* → add instructions:
> Skylark is a proxy client and needs a proxy server to connect. Test server: copy this link, open Skylark, tap "Paste from clipboard", then tap the big Connect button and accept the VPN prompt.
> `vless://…` ← your reviewer link here

**Ads:** No, my app does not contain ads.

**Content rating (IARC questionnaire):**
- Category: *Utility, Productivity, Communication, or Other*
- Violence, sexuality, language, controlled substances, gambling: **No** to all
- Users can interact or exchange content: **No**
- Shares the user's location with others: **No**
- Allows purchase of digital goods: **No** (the app is paid upfront, there are no in-app purchases)
- Web browser or search engine: **No**
- Expected result: Everyone / PEGI 3

**Target audience:** **18 and over** only. This keeps the app out of the Families policy.

**News app:** No · **Government app:** No · **Financial features:** none · **Health:** none

**Advertising ID:** No, the app does not use an advertising ID.

**Data safety:**
- Does your app collect or share any of the required user data types? **No**
  (Everything stays on the device; the app sends nothing to us. Network requests to the user's own servers, subscription URLs, DNS, the country IP list and time sync contain no personal data and don't go to the developer.)
- The form then ends with "No data collected" and "No data shared".

## 5. VPN declaration (App content › VpnService)

- Does your app use VpnService? **Yes**
- Is VPN the core functionality? **Yes**. The app is a VPN/proxy client.
- Description to paste:
> Skylark is a proxy client. It uses VpnService to create a device-level tunnel that routes the user's traffic to a proxy server the user adds themselves (by share link, QR code or subscription), with rules that decide which traffic goes through the proxy and which goes direct. VpnService is the app's core function: without it the app cannot do anything. The app does not collect, log or share user traffic or personal data, does not modify or block ads, and does not redirect traffic for monetization. All configuration stays on the device.
- Demo video: your unlisted YouTube link (step 9).

## 6. Foreground service declaration (if Play asks)

- Type: **systemExempted** (Android's type for VPN apps)
- Explanation:
> The foreground service keeps the user's VPN connection running while it is active. It starts only when the user taps Connect, shows an ongoing notification with a Disconnect button, and stops when the user disconnects or revokes the VPN.

## 7. Release (Test and release › Production)

- Create a new release → upload `app-play-release.aab`.
- Play App Signing: accept the **Google-generated app signing key**. The key in `keystore/` becomes your upload key.
- Release name: `1.0.0 (1)`
- Release notes:
  - en-US: `First release of Skylark: one-tap connect, smart mode for your country, subscriptions, QR import, per-app proxy and custom rules.`
  - zh-CN: `云雀首个版本：一键连接、按所在国家智能分流、订阅、扫码导入、分应用代理与自定义规则。`
  - zh-TW: `雲雀首個版本：一鍵連線、依所在國家智慧分流、訂閱、掃碼匯入、分應用程式代理與自訂規則。`
- Send for review. Expect a few days; VPN apps often get a closer look.

## 8. After approval

- Make sure the repo is public (step 0).
- Optional: add Skylark to lihaila.com (products.py and the product counts in the four languages, see the site notes).

## 9. VPN demo video script (≤ 90 seconds, screen recording on your phone)

Use your phone's built-in screen recorder (Quick Settings › Screen record). Keep it under 90 seconds.

1. (0–5 s) Home screen → tap the Skylark icon to open the app.
2. (5–15 s) Copy the server link → in Skylark tap **Paste from clipboard** → the server appears.
3. (15–25 s) Tap the big **Connect** button → the Android **VPN connection request** dialog appears → tap **OK**.
4. (25–35 s) Show "Connected", the timer, the key icon in the status bar and the notification.
5. (35–55 s) Open Chrome → visit `ipinfo.io` → the IP shows the proxy server's location.
6. (55–70 s) Back in Skylark: show the Smart / Global / Direct modes and the Rules screen.
7. (70–85 s) Tap the button again to **disconnect**; the key icon disappears.
