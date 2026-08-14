// #2673 — what the Worker DOES when it is unconfigured, as opposed to what it detects.
//
// `missing-bindings.test.ts` pins the predicate. This pins the two properties that are the actual
// fix, and that a predicate test cannot see:
//
//   1. an unconfigured deployment REJECTS the message. The bug was `console.warn` + a bare `return`,
//      which accepted the mail and dropped it — no bounce, no error, no API call. Cloudflare
//      reported outcome "ok". Reinstating that bare `return` must turn this suite red, otherwise the
//      suite cannot see the original bug coming back.
//   2. the guard runs BEFORE the outbound POST. A guard that fires after the fetch would still be
//      signing and shipping envelopes with an empty secret.
//
// No miniflare and no new dependency: the default export is importable and the handler only touches
// the few `ForwardableEmailMessage` members stubbed here, so the real `email()` runs.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';

const CONFIGURED = {
  PRESS_WEBHOOK_SECRET: 'shared-hmac-secret',
  PRESS_API_URL: 'https://api.wifihaven.test',
};

/** The slice of ForwardableEmailMessage the handler reaches before it would POST. */
function inboundMessage() {
  return {
    from: 'reporter@example-paper.test',
    headers: new Headers({ subject: 'Interview request' }),
    raw: new Response('Subject: Interview request\r\n\r\nCan we talk?\r\n').body,
    setReject: vi.fn(),
  };
}

describe('email() when the Worker is unconfigured', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('rejects the message instead of accepting and discarding it', async () => {
    const message = inboundMessage();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    await worker.email(message as never, {} as never);

    expect(message.setReject).toHaveBeenCalledOnce();
    // The sender must be told the mail did not arrive — setReject is a PERMANENT SMTP failure, so
    // "we'll get to it" wording would be a lie.
    expect(message.setReject.mock.calls[0][0]).toMatch(/was not delivered/);
    // ...and must not leak which binding is missing; that goes to the operator's log line.
    expect(message.setReject.mock.calls[0][0]).not.toMatch(/PRESS_/);
    // The guard is upstream of the POST: nothing is signed or shipped with an absent secret.
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('names the missing binding to the operator, not to the sender', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    // Partial provisioning — #2673's exact prod state.
    await worker.email(inboundMessage() as never, { PRESS_API_URL: CONFIGURED.PRESS_API_URL } as never);

    expect(errorSpy).toHaveBeenCalledOnce();
    const logged = String(errorSpy.mock.calls[0][0]);
    // Anchored to the REPORTED list, not the whole line: the remediation text that follows names
    // both bindings, so a bare `not.toContain('PRESS_API_URL')` would be unfalsifiable.
    expect(logged).toMatch(/binding\(s\) unset on this deployment: PRESS_WEBHOOK_SECRET\./);
    expect(logged).not.toMatch(/unset on this deployment:[^.]*PRESS_API_URL/);
  });

  it('does not reject when both bindings are set', async () => {
    const message = inboundMessage();
    // Stop at the POST — this test is about the guard not firing, not about the request body.
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response('', { status: 200 }));

    await worker.email(message as never, CONFIGURED as never);

    expect(message.setReject).not.toHaveBeenCalled();
    expect(fetchSpy).toHaveBeenCalledOnce();
    expect(fetchSpy.mock.calls[0][0]).toBe(`${CONFIGURED.PRESS_API_URL}/api/press/inbound`);
  });
});
