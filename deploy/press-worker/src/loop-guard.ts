// #2442 — the auto-reply / DSN loop guard for the press inbox.
//
// THE LOOP: #2439 set both the prod press From and Reply-To to `press@wifihaven.net`, which is the
// exact address Cloudflare Email Routing binds to this Worker (infra/cloudflare/main.tf:381-395),
// and #2537 turned the prod responder ON (`WIFIHAVEN_PRESS_RESPONDER_ENABLED=true`). So an
// out-of-office, a newsroom ticketing acknowledgement, or a bounce/DSN triggered by OUR reply lands
// straight back here — and without this guard it is signed, POSTed to `/api/press/inbound`, and
// dispatches another agent session, which emails another reply. The per-sender rate cap in
// PressResponder bounds how FAST that runs; it does not break it.
//
// This module classifies; it does not decide. The Worker stamps the verdict onto the envelope and
// the API refuses to dispatch on it and meters the skip (`press_loop_guard_total{marker}`) — the
// enforcement and the metric live where dispatch lives, so the skip is observable rather than a
// silent drop (docs/process/no-dark-by-default.md). Only the raw MIME headers can be read here, so
// only the detection lives here.
//
// The support responder's equivalent guard (#2404) has no code to share with this one: support
// arrives as a Plain webhook and is filtered on Plain's event type / actor type
// (`SupportResponder.scala`), never on SMTP headers. This is the same intent over a different
// input, not a duplicated computation.

/**
 * The bounded reason vocabulary. It is a wire enum — the API maps these onto the
 * `press_loop_guard_total{marker}` label and collapses anything it does not recognize to `unknown`,
 * so a Worker can never mint an unbounded label.
 */
export type LoopGuardMarker =
  | 'auto_submitted'
  | 'precedence'
  | 'x_auto_response_suppress'
  | 'list_id'
  | 'null_return_path';

/** The subset of `Headers` this reads — keeps the classifier testable without a live message. */
export interface HeaderReader {
  get(name: string): string | null;
}

// RFC 3834: `Auto-Submitted: no` is the explicit "a human wrote this" value. Everything else
// (auto-replied, auto-generated, auto-notified, …) is machine-originated.
const HUMAN_AUTO_SUBMITTED = 'no';

// Only these three Precedence values are treated as auto/bulk. `list`, `first-class` and `normal`
// are deliberately NOT in the set: a reporter mailing us through a newsroom relay that stamps
// Precedence must still reach a human, and a wrongly-dropped journalist is the worse failure.
const BULK_PRECEDENCE = new Set(['bulk', 'auto_reply', 'junk']);

/** Header value with parameters stripped (`auto-notified; owner@x` → `auto-notified`), lowercased. */
function headerToken(raw: string | null): string {
  return (raw ?? '').split(';')[0].trim().toLowerCase();
}

function present(raw: string | null): boolean {
  return (raw ?? '').trim().length > 0;
}

/**
 * Classify one inbound message. Returns the marker that made it auto-submitted, or `null` for
 * anything that looks like a person — including a message that carries these header NAMES with
 * empty values, which some relays stamp unconditionally.
 *
 * `envelopeFrom` is Cloudflare's `message.from` (the SMTP MAIL FROM). Empty is the DSN signature: a
 * bounce is sent with a null return path precisely so it cannot itself be bounced.
 */
export function classifyLoopMarker(
  headers: HeaderReader,
  envelopeFrom: string | null | undefined,
): LoopGuardMarker | null {
  const autoSubmitted = headerToken(headers.get('auto-submitted'));
  if (autoSubmitted !== '' && autoSubmitted !== HUMAN_AUTO_SUBMITTED) return 'auto_submitted';

  if (BULK_PRECEDENCE.has(headerToken(headers.get('precedence')))) return 'precedence';

  if (present(headers.get('x-auto-response-suppress'))) return 'x_auto_response_suppress';

  if (present(headers.get('list-id'))) return 'list_id';

  // `Return-Path: <>` is the null return path a bounce is sent with (so it cannot itself be
  // bounced). A Return-Path stamped with an EMPTY value is the same statement; a header that is
  // absent entirely says nothing — plenty of legitimate mail reaches a Worker without one.
  const returnPath = headers.get('return-path');
  if (returnPath !== null && ['', '<>'].includes(returnPath.trim())) return 'null_return_path';

  // The same null return path observed one layer down, at the SMTP envelope.
  if (!present(envelopeFrom ?? null)) return 'null_return_path';

  return null;
}
