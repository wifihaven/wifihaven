// #2673 — the missing-config guard's binding check.
//
// What this pins: the prod Worker ran for weeks with `PRESS_WEBHOOK_SECRET` unset, accepting
// press@wifihaven.net mail and discarding it silently. The old guard was
// `if (!env.PRESS_WEBHOOK_SECRET || !env.PRESS_API_URL) { console.warn(...); return }` — one
// boolean, so both-missing, one-missing and working were indistinguishable, and the Worker told
// neither the sender nor us. The failure was a PARTIAL provisioning (`PRESS_API_URL` was set), so
// naming the specific missing binding is the behaviour that matters, not just detecting absence.
//
// `missingBindings` is a pure function of the env bindings, so the whole matrix is testable without
// a live Email Worker. The reject/log behaviour it drives is exercised end-to-end against
// `wrangler dev`'s email handler (see README.md).

import { describe, expect, it } from 'vitest';
import { missingBindings } from '../src/index';

const SECRET = 'shared-hmac-secret';
const URL = 'https://api.wifihaven.net';

describe('missingBindings', () => {
  it('reports nothing when both bindings are set', () => {
    expect(missingBindings({ PRESS_WEBHOOK_SECRET: SECRET, PRESS_API_URL: URL })).toEqual([]);
  });

  // The exact prod shape of #2673: the URL was configured, the secret never was.
  it('names only the missing one on a partial provisioning', () => {
    expect(missingBindings({ PRESS_API_URL: URL })).toEqual(['PRESS_WEBHOOK_SECRET']);
    expect(missingBindings({ PRESS_WEBHOOK_SECRET: SECRET })).toEqual(['PRESS_API_URL']);
  });

  it('names both when neither is set', () => {
    expect(missingBindings({})).toEqual(['PRESS_WEBHOOK_SECRET', 'PRESS_API_URL']);
  });

  // `wrangler secret put` will store a whitespace-only value, and an unusable secret must not read
  // as a configured one just because the string is non-empty.
  it('treats empty and whitespace-only values as missing', () => {
    expect(missingBindings({ PRESS_WEBHOOK_SECRET: '', PRESS_API_URL: URL })).toEqual([
      'PRESS_WEBHOOK_SECRET',
    ]);
    expect(missingBindings({ PRESS_WEBHOOK_SECRET: '   ', PRESS_API_URL: '\t\n' })).toEqual([
      'PRESS_WEBHOOK_SECRET',
      'PRESS_API_URL',
    ]);
  });

  // The guard's log line joins this list, so a stable order keeps the operator-facing message
  // deterministic rather than dependent on which binding happens to be enumerated first.
  it('reports in a stable declaration order', () => {
    expect(missingBindings({})).toEqual(missingBindings({}));
    expect(missingBindings({})[0]).toBe('PRESS_WEBHOOK_SECRET');
  });
});
