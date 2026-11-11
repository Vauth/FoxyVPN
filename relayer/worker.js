const ALLOWED_HOSTS = new Set([
  "vpn.mozilla.org",
  "api.accounts.firefox.com",
  "oauth.accounts.firefox.com",
  "accounts.firefox.com",
  "firefox.settings.services.mozilla.com",
  "product-details.mozilla.org",
  "github.com",
  "githubusercontent.com",
  "raw.githubusercontent.com",
  "www.gstatic.com",
]);

const STRIP_REQUEST_HEADERS = new Set([
  "host",
  "connection",
  "content-length",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "via",
  "forwarded",
  "x-forwarded-for",
  "x-forwarded-proto",
  "x-forwarded-host",
  "x-forwarded-client-cert",
  "x-real-ip",
  "true-client-ip",
]);

const NULL_BODY_STATUSES = new Set([101, 204, 205, 304]);

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS, HEAD",
          "Access-Control-Allow-Headers": "*",
          "Access-Control-Max-Age": "86400",
        },
      });
    }

    try {
      const incomingUrl = new URL(request.url);
      const rawUrlParam = incomingUrl.searchParams.get("url");

      if (!rawUrlParam) {
        return new Response(
          JSON.stringify({ status: "ok", relay: "foxyvpn" }),
          { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } }
        );
      }

      const targetUrlStr = parseUrl(rawUrlParam);
      let targetUrl;
      try {
        targetUrl = new URL(targetUrlStr);
      } catch {
        return new Response(
          JSON.stringify({ error: "Malformed decoded URL: " + targetUrlStr }),
          { status: 400, headers: { "Content-Type": "application/json" } }
        );
      }

      if (targetUrl.protocol !== "https:") {
        return new Response(
          JSON.stringify({ error: "Target URL must use HTTPS" }),
          { status: 400, headers: { "Content-Type": "application/json" } }
        );
      }

      const host = targetUrl.hostname.toLowerCase();
      const isAllowed =
        ALLOWED_HOSTS.has(host) ||
        host === "firefox.com" ||
        host.endsWith(".firefox.com") ||
        host === "mozilla.org" ||
        host.endsWith(".mozilla.org") ||
        host === "mozilla.com" ||
        host.endsWith(".mozilla.com") ||
        host === "github.com" ||
        host.endsWith(".github.com") ||
        host.endsWith(".githubusercontent.com");

      if (!isAllowed) {
        return new Response(
          JSON.stringify({ error: `Forbidden: Host '${host}' is not permitted` }),
          { status: 403, headers: { "Content-Type": "application/json" } }
        );
      }

      const forwardHeaders = new Headers();
      for (const [key, value] of request.headers.entries()) {
        const lowerKey = key.toLowerCase();
        if (!STRIP_REQUEST_HEADERS.has(lowerKey) && !lowerKey.startsWith("cf-")) {
          forwardHeaders.set(key, value);
        }
      }
      forwardHeaders.set("Host", targetUrl.host);

      const hasBody = !["GET", "HEAD"].includes(request.method);
      const bodyData = hasBody ? await request.arrayBuffer() : null;

      const upstreamRequest = new Request(targetUrl.toString(), {
        method: request.method,
        headers: forwardHeaders,
        body: bodyData,
        redirect: "follow",
      });

      const upstreamResponse = await fetch(upstreamRequest);

      const responseHeaders = new Headers(upstreamResponse.headers);
      responseHeaders.delete("content-encoding");
      responseHeaders.delete("content-length");

      if (typeof responseHeaders.getSetCookie === "function") {
        const cookies = responseHeaders.getSetCookie();
        if (cookies.length > 0) {
          responseHeaders.delete("set-cookie");
          for (const c of cookies) {
            responseHeaders.append("set-cookie", c.replace(/;\s*domain=[^;]+/gi, ""));
          }
        }
      } else if (responseHeaders.has("set-cookie")) {
        const rawCookies = responseHeaders.get("set-cookie");
        responseHeaders.set("set-cookie", rawCookies.replace(/;\s*domain=[^;]+/gi, ""));
      }

      responseHeaders.set("Access-Control-Allow-Origin", "*");
      responseHeaders.set(
        "Access-Control-Expose-Headers",
        "X-Quota-Remaining, X-Quota-Limit, X-Quota-Reset, Quota-Remaining, Retry-After"
      );

      const finalBody = NULL_BODY_STATUSES.has(upstreamResponse.status)
        ? null
        : upstreamResponse.body;

      return new Response(finalBody, {
        status: upstreamResponse.status,
        statusText: upstreamResponse.statusText,
        headers: responseHeaders,
      });
    } catch (err) {
      return new Response(
        JSON.stringify({
          error: "Relayer Gateway Error",
          details: err.message || String(err),
        }),
        { status: 502, headers: { "Content-Type": "application/json" } }
      );
    }
  },
};

function parseUrl(input) {
  let str = (input || "").trim();
  if (str.startsWith("https://") || str.startsWith("http://")) return str;
  str = str.replace(/ /g, "+").replace(/-/g, "+").replace(/_/g, "/");
  while (str.length % 4 !== 0) str += "=";
  return atob(str);
}
