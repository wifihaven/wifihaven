"""Tests for batching and retry logic in opnsense/wifihaven/agent.py

Run with: pytest opnsense/test/test_agent.py
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from wifihaven.agent import Batcher, post_with_retry

MAX_BATCH = 50
FLUSH_INTERVAL = 10  # seconds

# ---------------------------------------------------------------------------
# 1. Batcher
# ---------------------------------------------------------------------------

class TestBatcher:
    def _make_event(self, n):
        return {"type": "connection_attempt", "ts": str(n)}

    def test_flushes_when_batch_reaches_max_size(self):
        flushed = []
        b = Batcher(MAX_BATCH, FLUSH_INTERVAL, lambda evs: flushed.append(evs))
        for i in range(MAX_BATCH):
            b.add(self._make_event(i))
        assert len(flushed) == 1
        assert len(flushed[0]) == MAX_BATCH

    def test_no_flush_before_max_size(self):
        flushed = []
        b = Batcher(MAX_BATCH, FLUSH_INTERVAL, lambda evs: flushed.append(evs))
        for i in range(MAX_BATCH - 1):
            b.add(self._make_event(i))
        assert len(flushed) == 0

    def test_flush_drains_partial_buffer(self):
        flushed = []
        b = Batcher(MAX_BATCH, FLUSH_INTERVAL, lambda evs: flushed.append(evs))
        b.add(self._make_event(0))
        b.add(self._make_event(1))
        b.flush()
        assert len(flushed) == 1
        assert len(flushed[0]) == 2

    def test_flush_is_noop_when_buffer_empty(self):
        flushed = []
        b = Batcher(MAX_BATCH, FLUSH_INTERVAL, lambda evs: flushed.append(evs))
        b.flush()
        assert len(flushed) == 0

    def test_buffer_resets_after_flush(self):
        flushed = []
        b = Batcher(MAX_BATCH, FLUSH_INTERVAL, lambda evs: flushed.append(evs))
        for i in range(MAX_BATCH):
            b.add(self._make_event(i))
        # One auto-flush; add one more and manually flush.
        b.add(self._make_event(MAX_BATCH))
        b.flush()
        assert len(flushed) == 2
        assert len(flushed[1]) == 1

    def test_tick_flushes_when_interval_elapsed(self):
        flushed = []
        b = Batcher(MAX_BATCH, FLUSH_INTERVAL, lambda evs: flushed.append(evs))
        b.add(self._make_event(0))
        # Simulate time advancing past the flush interval
        b.tick(now=FLUSH_INTERVAL + 1)
        assert len(flushed) == 1

    def test_tick_does_not_flush_before_interval(self):
        flushed = []
        b = Batcher(MAX_BATCH, FLUSH_INTERVAL, lambda evs: flushed.append(evs))
        b.add(self._make_event(0))
        b.tick(now=FLUSH_INTERVAL - 1)
        assert len(flushed) == 0

    def test_second_full_batch_triggers_second_flush(self):
        flushed = []
        b = Batcher(MAX_BATCH, FLUSH_INTERVAL, lambda evs: flushed.append(evs))
        for i in range(MAX_BATCH * 2):
            b.add(self._make_event(i))
        assert len(flushed) == 2
        assert len(flushed[0]) == MAX_BATCH
        assert len(flushed[1]) == MAX_BATCH


# ---------------------------------------------------------------------------
# 2. post_with_retry
#    post_with_retry(url, body, max_retries, base_delay, post_fn, sleep_fn)
#      -> bool
#
#    post_fn(url, body) -> (status_code, body_str)
#    sleep_fn(seconds)  — injectable so tests don't actually sleep
# ---------------------------------------------------------------------------

class TestPostWithRetry:
    URL = "http://api/router/events"
    BODY = "{}"
    MAX_RETRIES = 3
    BASE_DELAY = 1  # override to 0 in most tests to avoid sleeping

    def test_success_on_first_attempt_returns_true(self):
        calls = []
        def post_fn(url, body):
            calls.append(1)
            return 200, ""
        ok = post_with_retry(self.URL, self.BODY, self.MAX_RETRIES, 0, post_fn)
        assert ok is True
        assert len(calls) == 1

    def test_retries_on_5xx_and_succeeds_on_second_attempt(self):
        calls = []
        def post_fn(url, body):
            calls.append(1)
            return (500, "err") if len(calls) < 2 else (200, "")
        ok = post_with_retry(self.URL, self.BODY, self.MAX_RETRIES, 0, post_fn)
        assert ok is True
        assert len(calls) == 2

    def test_exhausts_retries_and_returns_false(self):
        calls = []
        def post_fn(url, body):
            calls.append(1)
            return 503, "unavailable"
        ok = post_with_retry(self.URL, self.BODY, self.MAX_RETRIES, 0, post_fn)
        assert ok is False
        assert len(calls) == self.MAX_RETRIES

    def test_no_retry_on_4xx(self):
        """Client errors (4xx) are not transient — stop immediately."""
        calls = []
        def post_fn(url, body):
            calls.append(1)
            return 401, "unauthorized"
        ok = post_with_retry(self.URL, self.BODY, self.MAX_RETRIES, 0, post_fn)
        assert ok is False
        assert len(calls) == 1

    def test_no_retry_on_403(self):
        calls = []
        def post_fn(url, body):
            calls.append(1)
            return 403, "forbidden"
        ok = post_with_retry(self.URL, self.BODY, self.MAX_RETRIES, 0, post_fn)
        assert ok is False
        assert len(calls) == 1

    def test_exponential_backoff_delays(self):
        """Delay doubles on each failed attempt."""
        delays = []
        calls = []
        def post_fn(url, body):
            calls.append(1)
            return 500, "err"
        def sleep_fn(secs):
            delays.append(secs)

        post_with_retry(self.URL, self.BODY, 4, self.BASE_DELAY, post_fn, sleep_fn)
        # 4 attempts → 3 sleeps (no sleep after the last attempt)
        assert delays == [1, 2, 4]

    def test_no_sleep_after_last_attempt(self):
        """The final failed attempt should not trigger a sleep."""
        delays = []
        def post_fn(url, body):
            return 500, "err"
        def sleep_fn(secs):
            delays.append(secs)

        post_with_retry(self.URL, self.BODY, self.MAX_RETRIES, 1, post_fn, sleep_fn)
        # MAX_RETRIES=3 attempts → 2 sleeps
        assert len(delays) == self.MAX_RETRIES - 1

    def test_connection_error_treated_as_retryable(self):
        """post_fn returning (None, '') simulates a connection failure."""
        calls = []
        def post_fn(url, body):
            calls.append(1)
            return None, ""  # connection error
        ok = post_with_retry(self.URL, self.BODY, self.MAX_RETRIES, 0, post_fn)
        assert ok is False
        assert len(calls) == self.MAX_RETRIES
