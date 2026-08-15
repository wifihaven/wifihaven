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

// Every binding the Worker cannot function without. Both are set at DEPLOY time — `PRESS_API_URL`
// from `[vars]` in wrangler.toml, `PRESS_WEBHOOK_SECRET` by `wrangler secret put` in
// master-press-worker.yml — so their presence is a fixed property of the deployed script, identical
// for every message it handles. There is no per-message or transient path to them being absent,
// which is what makes rejecting on their absence safe (see `email()` below).
// Written as an exhaustive record rather than a plain array so the COMPILER enforces that every
// `Env` key is listed: `satisfies Record<keyof Env, true>` fails with TS1360 if a binding is added
// to `Env` and forgotten here. (A bare `as const satisfies readonly (keyof Env)[]` does NOT do this
// — it is an upper bound, so it rejects a typo but accepts an omission, which is the failure that
// matters: an unlisted binding is an unguarded one.)
const REQUIRED_BINDINGS = Object.keys({
  PRESS_WEBHOOK_SECRET: true,
  PRESS_API_URL: true,
} satisfies Record<keyof Env, true>) as (keyof Env)[];

/**
 * Which required bindings are missing on THIS deployment, by name.
 *
 * Named individually rather than collapsed into one boolean: #2673 was a PARTIAL provisioning —
 * `PRESS_API_URL` was set, `PRESS_WEBHOOK_SECRET` was not — and the old `||` guard reported
 * both-missing, one-missing and working as the same (silent) outcome. This is the Worker's
 * equivalent of the API's `Config.missingRequiredKeys`: report every missing key at once, so the
 * operator fixes the whole gap in one pass instead of discovering the second one after redeploying.
 *
 * Exported for test/missing-bindings.test.ts — the guard it drives decides whether press mail is
 * accepted at all, so its per-binding reporting is asserted rather than only exercised by hand.
 */
export function missingBindings(env: Partial<Env>): string[] {
  return REQUIRED_BINDINGS.filter((k) => {
    const v = env[k];
    // A whitespace-only value is as unusable as an absent one, and `wrangler secret put` will
    // happily store one.
    return !v || v.trim() === '';
  });
}

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
    // #2673 — an unconfigured Worker REJECTS the message; it must never accept-and-discard.
    //
    // This branch used to `console.warn` and return, and the comment justified the silent discard by
    // claiming press@ was also forwarded elsewhere so mail was not lost. There is no such forwarding
    // rule: in infra/cloudflare/main.tf, `cloudflare_email_routing_rule.press_to_worker` is the only
    // rule for press@wifihaven.net and it sends here (`…press_staging_to_worker` routes press@staging
    // to the staging Worker; neither address has a `forward` rule). Named rather than line-numbered
    // for the same reason wrangler.toml's comment is. Mail was lost. For weeks, with the sender
    // told nothing — the exact silent-no-op `docs/process/no-dark-by-default.md` forbids ("absence
    // of a secret is a bug, not a disable switch").
    //
    // A Worker cannot refuse to boot the way the API can, so rejection is the loudest runtime signal
    // available, and it is the only one that reaches a HUMAN by construction: the sender gets a
    // delivery failure and knows to try again elsewhere, and the rejection is counted in Cloudflare
    // Email Routing's activity. Logging alone is not a substitute — the old `console.warn` fired on
    // every inbound press email for weeks and nobody saw it. For a press inbox, a bounce is strictly
    // better than silence: a journalist who bounces re-sends, a journalist who is ignored writes us
    // off.
    //
    // Safe to reject because the condition cannot be transient — see REQUIRED_BINDINGS above. This
    // is deploy-time misconfiguration only, never a flaky dependency.
    const missing = missingBindings(env);
    if (missing.length > 0) {
      console.error(
        `press-worker: refusing mail — required binding(s) unset on this deployment: ${missing.join(', ')}. ` +
          'Set them (wrangler secret put PRESS_WEBHOOK_SECRET / [vars] PRESS_API_URL) and redeploy; ' +
          'see deploy/press-worker/README.md and issue #2673.',
      );
      // Sender-facing text: actionable and professional, and deliberately does NOT name the missing
      // bindings — the operator reads those in the log line above, an external journalist should get
      // a working alternative, not our config keys.
      // `setReject` is a PERMANENT SMTP failure — the sending MTA will not retry once we fix the
      // binding, so the wording must tell the sender the message did not arrive and needs re-sending,
      // not imply it will be delivered later.
      message.setReject(
        'press@wifihaven.net is not accepting mail right now and this message was not delivered. ' +
          'Please re-send it to support@wifihaven.net.',
      );
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
