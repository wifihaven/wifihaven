package wifihaven.api

import zio.*

/**
 * #2266 (no-dark-by-default, doc rule 3): make the disabled state of every genuinely-optional,
 * config-gated feature EXPLICIT and OBSERVABLE instead of a silent absence-of-secret no-op.
 *
 * These features are self-hosted-optional by design (a paid/cloud seam a single-install never
 * uses), so the fix is NOT "fail boot when the key is missing" (rule 1) — it is rule 3: the
 * off-state must be NAMED, deliberate, and inspectable. This report is the observability leg:
 *
 *   - logged at startup (`log`) so an operator reading boot logs sees "feature X: DISABLED —
 *     <why>",
 *   - surfaced on the loopback `GET /api/debug/config` endpoint (`DebugRoutes`) so the running
 *     state is inspectable without grepping logs.
 *
 * The `detail` string always NAMES the config keys behind the decision, so a feature that is off
 * because nobody set the secret (a possible bug / config drift) is distinguishable from one that is
 * genuinely unused — "disabled because nobody set the key" vs "disabled by choice" per the doc.
 *
 * NOTE: this is deliberately NOT a promotion of any key to *required*. Behavior is byte-identical
 * to before; only the silence is removed. Whether any of these should become an explicit standalone
 * `enabled: Boolean` flag (rule 3's first leg) is a per-feature follow-up gated on
 * config-before-code coordination (the key must already be set in staging + prod first) — tracked
 * on #2266.
 *
 * `states` is pure over [[AppConfig]] (+ an injected Loki-configured flag) so it is unit-testable
 * without a live environment — see `StartupConfigSpec`.
 */
object StartupFeatureReport {

  /** One config-gated feature's resolved state. `detail` names the keys behind the decision. */
  final case class FeatureState(name: String, enabled: Boolean, detail: String)

  /**
   * The state of every config-gated feature the #2266 audit enumerated. `lokiConfigured` is passed
   * in (defaulting to the `GRAFANA_CLOUD_LOKI_URL` env presence the logback appender + `#1972`
   * poller gate on) so the pure list stays testable.
   */
  def states(
      cfg: AppConfig,
      lokiConfigured: Boolean = sys.env.contains("GRAFANA_CLOUD_LOKI_URL"),
  ): List[FeatureState] = {
    val email   = cfg.email
    val stripe  = cfg.stripe
    val support = cfg.support
    val press   = cfg.press
    val metrics = cfg.metrics

    List(
      FeatureState(
        "email-notifications",
        email.enabled,
        if email.enabled then "wifihaven.email.resendApiKey + wifihaven.email.fromAddress set"
        else
          "wifihaven.email.resendApiKey and/or wifihaven.email.fromAddress unset — access-request " +
            "notifications fall back to a structured log line (#578/#2195)",
      ),
      FeatureState(
        "stripe-billing",
        stripe.enabled,
        if stripe.enabled then "wifihaven.stripe.secretKey set — /api/billing/* live"
        else
          "wifihaven.stripe.secretKey unset — billing off, /api/billing/* return not-configured (#2135)",
      ),
      FeatureState(
        "support-widget",
        support.widgetEnabled,
        if support.widgetEnabled then
          "wifihaven.support.plainAppId + wifihaven.support.plainIdentitySecret set"
        else
          "wifihaven.support.plainAppId and/or wifihaven.support.plainIdentitySecret unset — " +
            "identified chat widget renders nothing (#2199)",
      ),
      FeatureState(
        "support-write-api",
        support.writeEnabled,
        if support.writeEnabled then "wifihaven.support.plainApiKey set — Plain write client live"
        else "wifihaven.support.plainApiKey unset — Plain write client is a metered no-op (#2199)",
      ),
      // #2200/#2265: the responder and issue filing are EXPLICIT-flag features (rule 3's first leg —
      // a named `enabled` flag, not inferred from secrets). When a flag is ON, its whole config
      // chain is REQUIRED and validated at boot (AppConfig.validateRequired); this report makes the
      // deliberate OFF state observable so it isn't mistaken for a silent no-op.
      FeatureState(
        "support-responder",
        support.responderEnabled,
        if support.responderEnabled then
          "wifihaven.support.responderEnabled=true — signed webhook drafts a cloud-agent reply"
        else
          "wifihaven.support.responderEnabled=false — inbound support webhook no-ops, agent " +
            "endpoints 404 (#2200/#2265)",
      ),
      FeatureState(
        "support-issue-filing",
        support.issueFilingEnabled,
        if support.issueFilingEnabled then
          "wifihaven.support.issueFilingEnabled=true — support-agent files GitHub issues (bot token)"
        else
          "wifihaven.support.issueFilingEnabled=false — the agent cannot file issues (#2241/#2265)",
      ),
      // #2203/#2265: the press/PR responder is the same EXPLICIT-flag shape as the support responder
      // — a named `enabled` flag, not inferred from secrets. When ON, its whole config chain is
      // required and validated at boot (AppConfig.validateRequired); this makes the deliberate OFF
      // state observable so it isn't mistaken for a silent no-op.
      FeatureState(
        "press-responder",
        press.responderEnabled,
        if press.responderEnabled then
          "wifihaven.press.responderEnabled=true — signed press webhook drafts a cloud-agent reply"
        else
          "wifihaven.press.responderEnabled=false — inbound press webhook no-ops, agent endpoint " +
            "404 (#2203/#2265)",
      ),
      FeatureState(
        "metrics-endpoint",
        metrics.enabled,
        if metrics.enabled then "wifihaven.metrics.enabled=true — GET /metrics mounted"
        else "wifihaven.metrics.enabled=false — GET /metrics returns 404 (#1242)",
      ),
      FeatureState(
        "metrics-scrape-token",
        metrics.scrapeTokenOpt.isDefined,
        if metrics.scrapeTokenOpt.isDefined then
          s"wifihaven.metrics.scrapeToken set — /metrics requires Bearer auth (requireToken=${metrics.requireToken})"
        else if metrics.requireToken then
          // Unreachable at runtime: validateRequired fails boot on requireToken=true + empty token.
          "wifihaven.metrics.requireToken=true but scrapeToken unset — /metrics is fail-CLOSED (401) (#2266)"
        else
          "wifihaven.metrics.scrapeToken unset, requireToken=false — /metrics is UNAUTHENTICATED " +
            "(loopback-only self-hosted default; cloud sets requireToken=true) (#1242/#2266)",
      ),
      FeatureState(
        "cors",
        cfg.cors.origins.nonEmpty,
        if cfg.cors.origins.nonEmpty then
          s"wifihaven.cors.allowedOrigins set (${cfg.cors.origins.mkString(", ")})"
        else
          "wifihaven.cors.allowedOrigins empty — CORS off (single-origin self-hosted default) (#612)",
      ),
      FeatureState(
        "ws-origin-enforcement",
        cfg.ws.enforceOrigin,
        if cfg.ws.enforceOrigin then
          s"wifihaven.ws.allowedOrigins set (${cfg.ws.allowedOriginHosts.mkString(", ")})"
        else
          "wifihaven.ws.allowedOrigins empty — cross-origin ws check off; SameSite=Strict wh_ws " +
            "cookie is the CSWSH guard (self-hosted same-origin default) (#1969)",
      ),
      FeatureState(
        "ui-allowed-hosts",
        cfg.policy.uiAllowedHostsParsed.nonEmpty,
        if cfg.policy.uiAllowedHostsParsed.nonEmpty then
          s"wifihaven.policy.uiAllowedHosts set (${cfg.policy.uiAllowedHostsParsed.map(_.value).mkString(", ")})"
        else
          "wifihaven.policy.uiAllowedHosts empty — no global admin-UI allow carve-out " +
            "(self-hosted single-origin default) (#944)",
      ),
      FeatureState(
        "debug-endpoints",
        cfg.debugEnabled,
        if cfg.debugEnabled then "WIFIHAVEN_DEBUG truthy — /api/debug/* MOUNTED (loopback only)"
        else "WIFIHAVEN_DEBUG unset/false — /api/debug/* not mounted",
      ),
      FeatureState(
        "seed-test-blocklists",
        cfg.seedTestBlocklists,
        if cfg.seedTestBlocklists then
          "WIFIHAVEN_SEED_TEST_BLOCKLISTS truthy — dev test_ads/test_social seeded"
        else "WIFIHAVEN_SEED_TEST_BLOCKLISTS unset/false — no dev test blocklists (prod default)",
      ),
      FeatureState(
        "loki-log-export",
        lokiConfigured,
        if lokiConfigured then
          "GRAFANA_CLOUD_LOKI_URL set — logback Loki appender active; drop/send-error self-metrics on (#1885/#1972)"
        else
          "GRAFANA_CLOUD_LOKI_URL unset — API logs NOT shipping to Grafana Cloud Loki (local/self-hosted)",
      ),
    )
  }

  /**
   * Log every feature's resolved state at boot, one INFO line each, so the disabled state of an
   * optional feature is never silent. Never fails.
   */
  def log(
      cfg: AppConfig,
      lokiConfigured: Boolean = sys.env.contains("GRAFANA_CLOUD_LOKI_URL"),
  ): UIO[Unit] =
    ZIO.foreachDiscard(states(cfg, lokiConfigured)) { s =>
      ZIO.logInfo(
        s"config feature '${s.name}': ${if s.enabled then "ENABLED" else "DISABLED"} — ${s.detail}",
      )
    }
}
