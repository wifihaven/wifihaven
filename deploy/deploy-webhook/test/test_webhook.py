"""Tests for deploy/deploy-webhook/app.py — the standalone Render→Grafana
deploy-annotation receiver (#1245).

Run with: pytest deploy/deploy-webhook/test/test_webhook.py
"""

import base64
import hashlib
import hmac
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import (  # noqa: E402
    build_annotation,
    classify,
    env_for,
    parse_event,
    verify_signature,
)

LIVE_BODY = json.dumps(
    {
        "type": "deploy_ended",
        "timestamp": "2026-05-31T01:33:00Z",
        "data": {
            "id": "evt-abc",
            "serviceId": "srv-prod",
            "serviceName": "wifihaven-api-prod",
            "status": "succeeded",
        },
    }
).encode()

FAILED_BODY = json.dumps(
    {
        "type": "deploy_ended",
        "timestamp": "2026-05-31T01:40:00Z",
        "data": {"serviceName": "wifihaven-api-staging", "status": "failed"},
    }
).encode()

STARTED_BODY = json.dumps(
    {"type": "deploy_started", "data": {"serviceName": "wifihaven-api-prod"}}
).encode()


def _sign(key_bytes, webhook_id, ts, body_bytes):
    """Reproduce Standard-Webhooks signing so verify_signature can be checked."""
    signed = webhook_id.encode() + b"." + ts.encode() + b"." + body_bytes
    sig = hmac.new(key_bytes, signed, hashlib.sha256).digest()
    return "v1," + base64.b64encode(sig).decode()


class TestParseAndClassify:
    def test_parse_extracts_fields(self):
        ev = parse_event(LIVE_BODY)
        assert ev["type"] == "deploy_ended"
        assert ev["timestamp"] == "2026-05-31T01:33:00Z"
        assert ev["data"]["serviceName"] == "wifihaven-api-prod"
        assert ev["data"]["status"] == "succeeded"

    def test_parse_tolerates_unknown_fields(self):
        body = json.dumps(
            {"type": "deploy_ended", "futureField": 42, "data": {"status": "succeeded"}}
        ).encode()
        assert parse_event(body)["type"] == "deploy_ended"

    def test_parse_invalid_json_returns_none(self):
        assert parse_event(b"not json{{") is None

    def test_classify_lifecycles(self):
        assert classify(parse_event(LIVE_BODY)) == "live"
        assert classify(parse_event(FAILED_BODY)) == "failed"
        assert classify(parse_event(STARTED_BODY)) == "started"

    def test_classify_canceled_and_unknown_are_skipped(self):
        canceled = parse_event(
            json.dumps({"type": "deploy_ended", "data": {"status": "canceled"}}).encode()
        )
        other = parse_event(json.dumps({"type": "server_restarted", "data": {}}).encode())
        assert classify(canceled) == "canceled"
        assert classify(other) == "ignored"


class TestSignature:
    SECRET = "topsecret-webhook-key"
    WID = "evt-abc"
    TS = "1717000000"

    def test_valid_plain_secret_verifies(self):
        header = _sign(self.SECRET.encode(), self.WID, self.TS, LIVE_BODY)
        assert verify_signature(self.SECRET, self.WID, self.TS, LIVE_BODY, header)

    def test_tampered_body_fails(self):
        header = _sign(self.SECRET.encode(), self.WID, self.TS, LIVE_BODY)
        assert not verify_signature(self.SECRET, self.WID, self.TS, LIVE_BODY + b"x", header)

    def test_wrong_secret_fails(self):
        header = _sign(self.SECRET.encode(), self.WID, self.TS, LIVE_BODY)
        assert not verify_signature("wrong-secret", self.WID, self.TS, LIVE_BODY, header)

    def test_whsec_prefixed_secret_is_base64_decoded(self):
        raw_key = b"0123456789abcdef0123456789abcdef"
        secret = "whsec_" + base64.b64encode(raw_key).decode()
        header = _sign(raw_key, self.WID, self.TS, LIVE_BODY)
        assert verify_signature(secret, self.WID, self.TS, LIVE_BODY, header)

    def test_empty_secret_never_verifies(self):
        assert not verify_signature("", self.WID, self.TS, LIVE_BODY, "v1,whatever")

    def test_empty_header_never_verifies(self):
        assert not verify_signature(self.SECRET, self.WID, self.TS, LIVE_BODY, "")


class TestEnvFor:
    def test_derives_env_from_service_name(self):
        assert env_for("wifihaven-api-prod") == "prod"
        assert env_for("wifihaven-api-staging") == "staging"

    def test_override_wins(self):
        assert env_for("wifihaven-api-prod", "custom") == "custom"

    def test_unknown_service_is_blank(self):
        assert env_for("some-other-service") == ""
        assert env_for(None) == ""


class TestBuildAnnotation:
    def test_live_deploy_includes_sha_and_tags(self):
        ev = parse_event(LIVE_BODY)
        ann = build_annotation(ev, "live", "abc1234", "prod", 999)
        assert ann is not None
        assert "abc1234" in ann["text"]
        assert "deploy" in ann["text"]
        assert "deploy" in ann["tags"]
        assert "prod" in ann["tags"]
        assert "wifihaven-api-prod" in ann["tags"]
        assert "live" in ann["tags"]
        # timestamp parsed from the payload, not the fallback
        assert ann["time"] == 1748655180000

    def test_falls_back_to_service_name_without_sha(self):
        ev = parse_event(FAILED_BODY)
        ann = build_annotation(ev, "failed", None, "staging", 999)
        assert "failed" in ann["tags"]
        assert "wifihaven-api-staging" in ann["text"]

    def test_uses_fallback_time_when_timestamp_missing(self):
        ev = parse_event(STARTED_BODY)
        ann = build_annotation(ev, "started", None, "prod", 12345)
        assert ann["time"] == 12345

    def test_skipped_lifecycles_produce_no_annotation(self):
        ev = parse_event(LIVE_BODY)
        assert build_annotation(ev, "ignored", None, "prod", 1) is None
        assert build_annotation(ev, "canceled", None, "prod", 1) is None
