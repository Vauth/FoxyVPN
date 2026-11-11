# Edge Relayer

A lightweight edge proxy designed to provide reliable connectivity and network resilience for FoxyVPN control-plane communications in restricted or high-latency network environments.

> [!NOTE]
> This proxy handles only standard control-plane API calls (account sign-in, token refresh, and server catalog fetching). It **never** carries VPN tunnel data traffic.

<br>

## ⚙️ Deploy

### 1. Cloudflare Setup
- Create a [Cloudflare](https://www.cloudflare.com/) **account**.
- Navigate to `Workers & Pages > Create > Create Worker`.
- Deploy the worker by clicking **Deploy**.
- Edit the code by clicking **Edit Code**.
- Paste `worker.js` code into **Cloudflare**.
- Finally, click **Deploy** and copy the URL.

**Sample URL:**
```text
https://<your-worker-name>.<your-subdomain>.workers.dev
```

### 2. App Configuration
- Open **FoxyVPN**.
- Go to **Settings > Relayer provider**.
- Select **Custom Relayer** and enter your relayer URL.
- Finally, click **Save**.

> [!CAUTION]
> **Do not share your relayer URL with anyone** to protect your daily quota.

<br>

## 🌐 Deploy on Other Platforms

You can use the provided code to create and deploy the relayer on any platform of your choice (VPS, Vercel, Netlify, Google Apps Script, etc.).

Simply copy the contents of [`worker.js`](worker.js) and provide it to an AI (such as ChatGPT, Claude, or Gemini) with the following prompt:

```text
Use this Cloudflare Worker code as reference and adapt it into a production-ready relayer for [VPS/Vercel/Netlify/Google Apps Script]. Keep the exact same host whitelist, header stripping, cookie rewriting, and Base64 URL handling logic.
```

<br>

## 🧪 Verification

Test that your relayer is live by checking the root endpoint:

```bash
curl -s https://<your-worker-name>.<your-subdomain>.workers.dev
```

Expected response:
```json
{
  "status": "ok",
  "relay": "foxyvpn"
}
```
