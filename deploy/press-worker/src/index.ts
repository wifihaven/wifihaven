// #2203 — the WifiHaven PRESS/PR Cloudflare Email Worker.
//
// Cloudflare Email Routing catches press@wifihaven.net and runs this Worker on each inbound message
// (docs/process/declarative-config.md — the routing rule + this Worker are the in-repo config; the
// API server holds all the AI/secrets). The Worker is deliberately THIN: it parses the email, builds
// a small JSON envelope, HMAC-SHA256-signs the raw body under the secret it SHARES with the API
// (`press.webhookSecret`), and POSTs it to `${PRESS_API_URL}/api/press/inbound`. It holds no
// Anthropic key and makes no AI call itself — the API dispatches the Managed Agents press session and
// emails the reply back (destination locked into the session token).
//
// SECURITY: the signature is the authentication (the endpoint is otherwise public). The Worker signs
// the EXACT bytes it POSTs; the API recomputes the HMAC over the raw body it receives. Any mismatch
// (forged/replayed/unsigned) is rejected 400 server-side. The message body is untrusted downstream —
// this Worker never interprets it, only forwards it.

import PostalMime from 'postal-mime';
import { classifyLoopMarker } from './loop-guard';

export interface Env {
  // The shared HMAC secret (== the API's `press.webhookSecret` / WIFIHAVEN_PRESS_WEBHOOK_SECRET).
  // Set with: wrangler secret put PRESS_WEBHOOK_SECRET
  PRESS_WEBHOOK_SECRET: string;
  // The public base URL of the WifiHaven API (no trailing slash), e.g. https://api.wifihaven.net.
  // A plain var in wrangler.toml (per environment).
  PRESS_API_URL: string;
}

// Cap what we forward so a huge inbound email can't be relayed unbounded to the API (which also
// caps at 256 KiB). Press inquiries are short; 128 KiB of text is generous.
const MAX_TEXT_BYTES = 128 * 1024;

// #2467 — cap on the inbound `References` header we forward. The header is attacker-controlled in
// both content and length, and the API bounds it again (it normalises to a msg-id list that fits
// one 998-char RFC 5322 header line) before persisting it or putting it on the signed session
// token — this cap just keeps a pathological header out of the envelope in the first place. Sized
// well above any real thread: 100-char ids fill one header line in ~10 entries.
const MAX_REFERENCES_CHARS = 8 * 1024;

function hex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function hmacSha256Hex(secret: string, body: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  return hex(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(body)));
}

export default {
  // Cloudflare Email Routing entrypoint. `message` is the inbound email; `message.from` is the parsed
  // sender address (the reply target), `message.raw` the full MIME stream.
  async email(message: ForwardableEmailMessage, env: Env): Promise<void> {
    if (!env.PRESS_WEBHOOK_SECRET || !env.PRESS_API_URL) {
      // Ships dark: with no secret/URL configured the Worker does nothing (and does NOT bounce, so
      // mail is not lost — the operator can still read press@ if routing also forwards a copy).
      console.warn('press-worker: PRESS_WEBHOOK_SECRET / PRESS_API_URL unset — skipping');
      return;
    }

    const parsed = await PostalMime.parse(message.raw);
    const text = (parsed.text || parsed.html?.replace(/<[^>]+>/g, ' ') || '').slice(0, MAX_TEXT_BYTES);
    const subject = parsed.subject || message.headers.get('subject') || '';
    const messageId = parsed.messageId || message.headers.get('message-id') || '';
    // #2467 — the accumulated thread chain, so a reply to a journalist's human FOLLOW-UP can emit
    // References = the parent's References + the parent's Message-ID (RFC 5322 §3.6.4) rather than
    // the immediate parent alone. Forwarded RAW apart from the length cap: the API is the single
    // sanitiser (a msg-id whitelist), and stripping here would only give two places to disagree.
    // Empty for every first-contact email, which is what the API treats as "no chain".
    const references = (parsed.references || message.headers.get('references') || '').slice(
      0,
      MAX_REFERENCES_CHARS,
    );

    // #2442 — the auto-reply / DSN loop guard. Only this Worker sees the raw MIME headers, so the
    // classification happens here; the API refuses to dispatch on the verdict and meters the skip
    // (`press_loop_guard_total{reason}`), because dispatch and the metric pipeline live there. The
    // log line below is the sender-attributable half — it names the address the counter deliberately
    // does not, so a journalist wrongly classified as an autoresponder is recoverable from Workers
    // Logs (#2673 turned those on).
    const loopGuard = classifyLoopMarker(message.headers, message.from);
    if (loopGuard) {
      console.warn(
        `press-worker: loop guard — skipping auto-submitted message (marker=${loopGuard}, from=${message.from}, message-id=${messageId})`,
      );
    }

    // The envelope the API's PressInbound expects. `from` is message.from (the routed sender), the
    // reply target the API locks into the session token. `loopGuard` is ADDITIVE (#376 wire rule):
    // an API that predates #2442 ignores the field and behaves exactly as before.
    const body = JSON.stringify({
      from: message.from,
      subject,
      text,
      messageId,
      loopGuard: loopGuard ?? '',
      references,
    });

    const signature = await hmacSha256Hex(env.PRESS_WEBHOOK_SECRET, body);

    const resp = await fetch(`${env.PRESS_API_URL}/api/press/inbound`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-WifiHaven-Signature': signature,
      },
      body,
    });

    // The API returns 200 for every non-signature outcome (dispatched / dark / rate-limited) so we do
    // not retry-storm; a 4xx/5xx is logged for the CF dashboard. We do NOT bounce the sender.
    if (!resp.ok) {
      console.error(`press-worker: API POST failed HTTP ${resp.status}`);
    }
  },
};
