// #2442 — the auto-reply / DSN loop guard's classifier.
//
// The loop this pins: #2439 set BOTH the prod press From and Reply-To to press@wifihaven.net, which
// is the exact address Cloudflare Email Routing binds to this Worker (infra/cloudflare/main.tf:381).
// So anything that auto-replies to our reply — an out-of-office, a newsroom ticketing ack, a
// bounce/DSN — lands back here, and (pre-#2442) dispatched another agent session, which emailed
// another reply. #2537 turned the prod responder ON, so this is live risk, not a precaution.
//
// The classifier is deliberately a PURE function of the inbound headers + envelope sender so the
// whole marker matrix is testable without a live Email Worker.

import { describe, expect, it } from 'vitest';
import { classifyLoopMarker } from '../src/loop-guard';

// The `message.headers` shape the classifier reads (a subset of the standard Headers interface).
const headers = (h: Record<string, string>): Headers => new Headers(h);

// A message that looks like an actual journalist: no auto-reply markers anywhere.
const HUMAN = {
  From: 'reporter@example-paper.test',
  Subject: 'Comment for a story on home network filtering',
  'Return-Path': '<reporter@example-paper.test>',
};

describe('classifyLoopMarker', () => {
  it('passes an ordinary human press inquiry through untouched', () => {
    expect(classifyLoopMarker(headers(HUMAN), 'reporter@example-paper.test')).toBeNull();
  });

  it('skips Auto-Submitted with any value other than no (RFC 3834)', () => {
    for (const v of ['auto-replied', 'auto-generated', 'AUTO-REPLIED', 'auto-notified; owner']) {
      expect(classifyLoopMarker(headers({ ...HUMAN, 'Auto-Submitted': v }), 'x@y.test')).toBe(
        'auto_submitted',
      );
    }
  });

  it('does NOT skip Auto-Submitted: no — RFC 3834 says that IS a human message', () => {
    for (const v of ['no', ' No ', 'no; whatever']) {
      expect(classifyLoopMarker(headers({ ...HUMAN, 'Auto-Submitted': v }), 'x@y.test')).toBeNull();
    }
  });

  it('skips Precedence bulk / auto_reply / junk', () => {
    for (const v of ['bulk', 'auto_reply', 'junk', 'Bulk']) {
      expect(classifyLoopMarker(headers({ ...HUMAN, Precedence: v }), 'x@y.test')).toBe(
        'precedence',
      );
    }
  });

  it('does NOT skip other Precedence values a human message may legitimately carry', () => {
    // `list`, `first-class`, `normal` are not in the issue's skip set — a reporter posting from a
    // newsroom relay that stamps Precedence must still reach us.
    for (const v of ['normal', 'first-class', 'list', '']) {
      expect(classifyLoopMarker(headers({ ...HUMAN, Precedence: v }), 'x@y.test')).toBeNull();
    }
  });

  it('skips X-Auto-Response-Suppress', () => {
    expect(
      classifyLoopMarker(headers({ ...HUMAN, 'X-Auto-Response-Suppress': 'OOF, AutoReply' }), 'x@y.test'),
    ).toBe('x_auto_response_suppress');
  });

  it('skips List-Id', () => {
    expect(
      classifyLoopMarker(headers({ ...HUMAN, 'List-Id': 'Press releases <pr.example.test>' }), 'x@y.test'),
    ).toBe('list_id');
  });

  it('skips a DSN: null return path', () => {
    expect(classifyLoopMarker(headers({ ...HUMAN, 'Return-Path': '<>' }), 'x@y.test')).toBe(
      'null_return_path',
    );
    expect(classifyLoopMarker(headers({ ...HUMAN, 'Return-Path': '  ' }), 'x@y.test')).toBe(
      'null_return_path',
    );
  });

  it('skips a DSN: empty envelope MAIL FROM', () => {
    expect(classifyLoopMarker(headers(HUMAN), '')).toBe('null_return_path');
    expect(classifyLoopMarker(headers(HUMAN), undefined)).toBe('null_return_path');
  });

  it('treats an empty header value as absent, not as a marker', () => {
    // A relay that stamps the header name with no value must not cost a journalist their reply.
    const benign = {
      ...HUMAN,
      'Auto-Submitted': '',
      Precedence: '',
      'X-Auto-Response-Suppress': '   ',
      'List-Id': '',
    };
    expect(classifyLoopMarker(headers(benign), 'reporter@example-paper.test')).toBeNull();
  });

  it('is case-insensitive on header names', () => {
    expect(classifyLoopMarker(headers({ 'auto-submitted': 'auto-replied' }), 'x@y.test')).toBe(
      'auto_submitted',
    );
  });
});
