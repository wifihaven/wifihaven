package wifihaven.api.metrics

import com.zaxxer.hikari.HikariDataSource
import wifihaven.api.db.RouterRepo
import zio.*
import zio.metrics.*
import zio.metrics.connectors
import zio.metrics.connectors.MetricsConfig as ConnectorsConfig
import zio.metrics.connectors.prometheus.PrometheusPublisher

/**
 * #1204 §4.1: server-side cardinality firewall. Every self-metric emission goes through here, so a
 * metric can only be written under an allowlisted name with an allowlisted (and never-forbidden)
 * set of label keys. Anything else is dropped and counted in `metrics_rejected_total{reason}`
 * rather than polluting the registry with an unbounded series. For the current call sites the
 * names/keys are all static, so the reject path is a defensive backstop — but it's the same gate
 * the future `/api/router/metrics` ingest (router-pushed series) will reuse.
 */
object MetricGuard {

  /**
   * §4.3 — keys whose value-space grows with users/devices/domains/flows. Never allowed anywhere.
   */
  val ForbiddenKeys: Set[String] =
    Set(
      "mac",
      "device_id",
      "domain",
      "host",
      "hostname",
      "ip",
      "dst_ip",
      "user_id",
      "profile_id",
      "path",
      "query",
    )

  /**
   * #1210 — the small, known label-key vocabulary. Every key in any [[Allowed]] entry MUST be
   * listed here, and nothing here may also be a [[ForbiddenKeys]] (both invariants are enforced by
   * `MetricCardinalityGuardSpec`). This is the standing cardinality gate: introducing *any* new
   * label key forces a deliberate edit to this set, which is the review checkpoint #1210 makes
   * permanent. Each key is bounded and known at code-write time (§4.2): `route` (~40 templated
   * paths), `method` (~5), `status` (HTTP codes / bounded ingest enum), `op` (~30 hand-named DB
   * ops), `reason`/`result` (fixed per-metric enums), `version` (slow-moving agent versions),
   * `rollup_job` (handful of rollup job names), and `router_id` / `installation_id` (bounded
   * fleet/install dimensions).
   */
  val KnownLabelKeys: Set[String] =
    Set(
      "route",
      "method",
      "status",
      "op",
      "reason",
      "result",
      "version",
      "rollup_job",
      "router_id",
      "installation_id",
      // #1718 — bounded per-router dimensions for native OS-level metrics. `iface` is the
      // network-interface name (handful per router: br-lan, wan, phyN-apM, …). `ssid` is the
      // wireless SSID (a handful per router). Both are bounded by router hardware/config, not
      // by user/device/flow growth, so they're firewall-safe.
      "iface",
      "ssid",
      // #1785 — blocklist id for blocklist_render_skipped_total. Bounded by the
      // bundled blocklist set (api/resources/blocklists/_index.yml — currently
      // 9 ids); not user/device/flow-growth driven, so firewall-safe. Use the
      // domain-prefixed key (`blocklist_id`, not bare `id`) so the vocabulary
      // stays specific — `id` would be a generic catch-all that future series
      // might mistakenly co-opt for unbounded entities.
      "blocklist_id",
      // #1846 — websocket frame direction for `router_ws_frames_total`. A fixed
      // 2-value enum (`in` agent→server / `out` server→agent); bounded, so it
      // satisfies the §4 cardinality firewall.
      "direction",
      // #1898 — shared-host reporting attribution outcome for
      // `usage_shared_host_attribution_total`. A fixed 3-value enum (attributed /
      // split / other); bounded, so it satisfies the §4 cardinality firewall.
      "outcome",
      // #1968 — SPA-websocket transport (server side). `role` is the connection's
      // resolved UserRole (admin | adult | child — a fixed 3-value enum), `topic`
      // is the subscribable SpaTopic wire name (trafficUsage | now |
      // connectionEvents | timeStatus | appUsage | stale — a fixed 6-value enum).
      // Both bounded by the protocol vocabulary, NOT by user/profile/session growth
      // (params such as profileId are deliberately kept OUT of labels, design §7),
      // so they satisfy the §4 cardinality firewall.
      "role",
      "topic",
      // #808 — partitioned-table name for `partition_weeks_ahead`. A fixed 2-value enum
      // (traffic_reports | connection_events — PartitionRepo.PartitionedTables); bounded by
      // the schema, not by user/device/flow growth, so it satisfies the §4 cardinality firewall.
      "table",
      // #2132 — beta-pipeline stage for `wifihaven_beta_pipeline_total`. A fixed 4-value enum
      // (request | approve | reject | accept — the pipeline surfaces in BetaRoutes); bounded by
      // the code, not by user/household growth, so it satisfies the §4 cardinality firewall.
      // (`outcome` is already a known key.)
      "stage",
    )

  /**
   * §5.1/§5.2 — metric name → its permitted label keys. The only (name, keys) pairs that may be
   * emitted. `router_id` (and `installation_id`, once that concept lands) are bounded fleet-size
   * dimensions the server attaches to every router-pushed series (§4.2) — they are deliberately NOT
   * in [[ForbiddenKeys]].
   */
  val Allowed: Map[String, Set[String]] = Map(
    // §5.2 API self-metrics.
    "http_requests_total"                       -> Set("route", "method", "status"),
    "http_request_duration_seconds"             -> Set("route", "method"),
    // #1570 — error responses metered at the server boundary (ErrorBoundary). A dedicated,
    // operator-facing error series sliced by templated route + status code, so the error-rate
    // panel queries one obvious counter rather than filtering the all-requests counter. Bounded
    // labels only (route ~40 templated paths, status the HTTP code).
    "api_errors_total"                          -> Set("route", "status"),
    "db_query_duration_seconds"                 -> Set("op"),
    "db_queries_total"                          -> Set("op", "status"),
    "auth_failures_total"                       -> Set("reason"),
    "agent_connected_routers"                   -> Set.empty[String],
    "traffic_reports_filtered_zero_bytes_total" -> Set.empty[String],
    // #1569 — usage-ingest records dropped at decode. A single malformed record
    // (e.g. a host that fails Hostname validation — a CDN CNAME target with
    // underscores, see #1572) is skipped + metered here instead of 400-ing the
    // whole batch. `reason` is a small fixed enum (currently just `decode_error`).
    "usage_records_rejected_total"              -> Set("reason"),
    // #1757 — events-ingest records dropped at decode. Same shape as
    // usage_records_rejected_total: a single malformed RouterEvent (bad host /
    // mac / ts / unknown enum value) is skipped + metered instead of 400-ing
    // the whole batch (which would drop every valid connection_attempt /
    // dhcp_lease / first_seen_mac with it). `reason` is a small fixed enum
    // (currently just `decode_error`).
    "events_records_rejected_total"             -> Set("reason"),
    // #1585 — write-time FQDN backfill for traffic_reports. Counts each
    // UsageRecord at ingest by what the backfill decided: `filled` (race-loser
    // ipv4/ipv6 rewritten to a fqdn from a recent connection_event),
    // `miss` (candidate had no matching fqdn in the window), or `skip` (record
    // was already fqdn, or carried no dest_ip — not a candidate). `result` is
    // a small fixed enum; bounded. Reuses the existing `result` label key
    // rather than introducing `outcome` (#1210 keeps the vocabulary small).
    "traffic_reports_backfill_total"            -> Set("result"),
    // #1318/#1775 — global-policy-layer visibility. `global_allow_hosts` is the size of the
    // fleet-wide always-reachable set (a host here bypasses every block). #1775 removed the
    // DB-backed authoring path, so the set is now sourced entirely from compile-time config
    // (`uiAllowedHosts` + `infraAllowHosts`); the gauge stays as a static sanity check that
    // the deployment's allow set was loaded. `default_deny_profiles` counts profiles running the
    // block-all baseline. Both are unlabelled household-scoped gauges, set each time the policy
    // snapshot is assembled.
    "wifihaven_global_allow_hosts"              -> Set.empty[String],
    "wifihaven_default_deny_profiles"           -> Set.empty[String],
    // #1676 — per-(mac, app) sessions dropped by the #1666 phantom-suppression
    // guard inside Presence.appSpansForProfile. Unlabelled counter: the guard
    // is unconditional, so a reason enum would only ever carry one value, and
    // any per-mac / per-app label would blow the cardinality firewall. Operators
    // rate-alert on threshold drift — a sustained rise means the threshold is
    // too aggressive (real sessions vanishing), a flat zero while phantom
    // inflation returns means it is too lax.
    "presence_app_sessions_dropped_total"       -> Set.empty[String],
    // #2077 ambient anchor-gate observability (unlabelled).
    "presence_ambient_spans_dropped_total"      -> Set.empty[String],
    "presence_ambient_hosts"                    -> Set.empty[String],
    // #1898 — outcome of attributing a SHARED host's stitched span in usage-by-app
    // by temporal co-presence with apps' distinctive sessions. `outcome` is a
    // bounded 3-value enum (attributed / split / other); no per-mac/host/app label.
    "usage_shared_host_attribution_total"       -> Set("outcome"),
    // #1885 — log events the loki4j appender shed because its bounded send queue
    // (sendQueueMaxBytes) was full while Loki was slow/unreachable. The appender
    // is async/drop-on-backpressure by construction (fail-open: the request path
    // is never blocked), so a sustained outage silently loses logs — this counter
    // makes that loss alertable. Unlabelled: the appender exposes a single
    // appender-wide cumulative count, and any per-mac/route label would breach the
    // §4 cardinality firewall (service/env/level already ride the Loki stream
    // labels, not this Prometheus series).
    "loki_logs_dropped_total"                   -> Set.empty[String],
    // #1972 — log batches the loki4j appender SENT to Grafana Cloud Loki that came
    // back as an error (HTTP 401/403 wrong-scope token, 4xx/5xx, connection/timeout).
    // Distinct from loki_logs_dropped_total, which only counts queue-full drops on
    // the way IN: a misscoped access-policy token (logs:write missing) sheds every
    // line at the SEND boundary, leaving drops flat at 0 — that auth failure was the
    // invisible second half of #1972. loki4j is fail-open and routes these to the
    // logback StatusManager only, so without this counter the loss is silent.
    // Unlabelled (same cardinality firewall as the drop series).
    "loki_logs_send_errors_total"               -> Set.empty[String],
    // §5.1 router-sourced, pushed via POST /api/router/metrics (#1205). Every one carries the
    // server-attached `router_id` + `installation_id` plus its own bounded enum label.
    "dnsmasq_restarts_total"                    -> Set("reason", "router_id", "installation_id"),
    "policy_apply_total"                        -> Set("result", "router_id", "installation_id"),
    "policy_apply_duration_seconds"             -> Set("router_id", "installation_id"),
    "snapshot_poll_total"                       -> Set("result", "router_id", "installation_id"),
    "snapshot_poll_duration_seconds"            -> Set("router_id", "installation_id"),
    // #2037 — policy-poll dormancy. When the ws transport is enabled AND its
    // health sentinel is fresh (within ws_fallback_after), the agent's 5 s HTTP
    // `GET /api/router/policy` poll goes dormant — the push channel is the live
    // transport, so the snapshot is no longer recomputed per poll (#1512). This
    // counter increments once per suppressed poll so the server-side load win of
    // enabling ws is directly visible (a climbing rate on a ws router with a flat
    // snapshot_poll_total is the dormancy working). `reason` is a fixed 1-value
    // enum today (`ws_healthy`) — bounded, no per-mac/host dimension.
    "policy_poll_skipped_total"                 -> Set("reason", "router_id", "installation_id"),
    "agent_uptime_seconds"                      -> Set("router_id", "installation_id"),
    "agent_version"                             -> Set("version", "router_id", "installation_id"),
    "dns_queries_total"                         -> Set("result", "router_id", "installation_id"),
    // #573 / #1650 / #1653 — TLS ClientHello SNI capture outcomes from the wifihaven-sni-tail
    // sidecar. `result` ∈ {parsed, reassembled, incomplete, dropped_byte_cap, not_handshake,
    // truncated, no_sni, not_ip, not_tcp, malformed, ech} plus QUIC buckets (quic_*, including
    // quic_ech) — a small bounded enum that lets an operator see the fleet-wide SNI capture /
    // truncation / reassembly / ECH rate. #1653 added `reassembled` (multi-segment CH stitched +
    // parsed) so the lift from per-flow buffering is visible directly; `incomplete` /
    // `dropped_byte_cap` / `not_handshake` are the corresponding buffer-state buckets. #1650
    // added `ech` / `quic_ech` — the share of ClientHellos using Encrypted ClientHello (real
    // server_name is encrypted; attribution falls back to the outer/public SNI when present).
    // (Pre-#1652 agents also emit `ipv6_skipped`; the bucket ages out as the fleet rolls forward.)
    "sni_clienthellos_total"                    -> Set("result", "router_id", "installation_id"),
    "blocklist_fetch_failures_total"            -> Set("status", "router_id", "installation_id"),
    // #1785 — per-id counter incremented when the agent's render_shards hits the
    // #1434 defensive byte cap and drops a list on the floor (cap_hit ⊆ skipped;
    // absent-cache skips are covered by blocklist_fetch_failures_total above).
    // `id` is bounded by the bundled blocklist set (currently 9 ids in
    // api/resources/blocklists/_index.yml), so it satisfies the §4 cardinality
    // firewall. Parent design: #1435 (the cap stays as defense-in-depth even
    // after Option 2c streams large lists, so fleet-wide visibility of any
    // future cap hit must keep working).
    "blocklist_render_skipped_total"            -> Set(
      "blocklist_id",
      "router_id",
      "installation_id",
    ),
    "enforcement_drops_total"                   -> Set("reason", "router_id", "installation_id"),
    // #1658 — eb_/bl_ ipset re-resolve heartbeat. Each fire of the agent's
    // eb_refresh timer re-resolves the inventory of (extraBlocked, blocklist)
    // hosts against the local dnsmasq and adds answered IPs back into the
    // per-host eb_<host> / per-blocklist bl_<id> nftables sets ahead of their
    // 1h `flags dynamic,timeout` aging out — closing the iOS-DNS-cache leak
    // confirmed in prod for play.google.com (#1649). `result` is a small fixed
    // enum: `ok` (resolver answered, IPs were added back to the set; counts
    // resolved hosts not adds, so the rate is meaningful regardless of CDN
    // fan-out) and `resolve_failed` (dig couldn't reach the local resolver, or
    // the host failed the hermetic allow-list).
    "eb_refresh_total"                          -> Set("result", "router_id", "installation_id"),
    // #2095 — extraAllowed carve re-seed heartbeat. policy.apply's `nft -f`
    // delete+recreates `table inet wifihaven`, emptying every per-(mac,host)
    // ea_/ea6_ carve set; the agent immediately backfills them from the
    // persisted dns ip->host cache so a long-cached carved host (e.g.
    // cdn.jsdelivr.net over IPv6) stays reachable under a whole-MAC block
    // rather than transiently dropping (#2094 residual). This counts the
    // carve ELEMENTS re-seeded per apply (unlabeled total — no per-mac/host
    // cardinality). A healthy fleet shows a steady rate tracking applies; a
    // flatline while blocks-with-extraAllowed are active means the backfill
    // isn't firing and the transient v6 drop can recur.
    "ea_carve_backfill_total"                   -> Set("router_id", "installation_id"),
    // #1033 — usage-POST retry-queue health. Depth = buckets currently waiting for a backoff to
    // elapse; `usage_post_total{result}` tracks the immediate-post outcome (`ok` | `queued`) and
    // drain outcome (`drained` | `drain_failed`). Gives operators a first-class view of "are
    // usage reports flowing or are they stacking up?" without grepping the ring-buffer syslog.
    "usage_queue_depth"                         -> Set("router_id", "installation_id"),
    "usage_post_total"                          -> Set("result", "router_id", "installation_id"),
    // #2024 — idle-heartbeat stall indicator. The agent now drives its
    // cooperative timers (usage flush, activity sampler, …) on a wall-clock
    // heartbeat so on_tick fires even when conntrack is silent. This counter
    // increments when a reported usage window still ran past 2× the configured
    // usage_report_interval — i.e. on_tick stalled anyway (heartbeat wedged /
    // clock jump) and the bucket spanned un-monitored time, the #2016
    // over-count condition. A healthy fleet holds this flat at 0; a climbing
    // rate is the leading signal of the over-count regressing.
    "usage_window_stall_total"                  -> Set("router_id", "installation_id"),
    // Server-side ingest health for POST /api/router/metrics (#1205). Concrete, emitted now.
    "router_metrics_batches_total"              -> Set("status"),
    // #1846 — websocket router transport (server side). `router_ws_connections_active` is the live
    // count of open channels (a household-scoped gauge, refreshed on every register/deregister so it
    // ages out cleanly on disconnect). `router_ws_frames_total` counts every frame demuxed/sent:
    // `op` ∈ {hello, usage, events, metrics, ping, pong, ack, unknown} (the fixed envelope
    // vocabulary), `direction` ∈ {in, out}, `result` ∈ {ok, reject, unknown_op}. All bounded enums —
    // no per-mac / per-host dimension ever rides a ws metric.
    "router_ws_connections_active"              -> Set.empty[String],
    "router_ws_frames_total"                    -> Set("op", "direction", "result"),
    // #2168 — per-ws-message-process latency, the ws analog of http_request_duration_seconds.
    // Over the persistent socket (#1023) every logical operation is a frame on ONE connection, so
    // the per-HTTP-route duration histogram no longer observes it; without this we go blind to
    // slow-op regressions (the class that caught /api/usage/traffic) after the poll is removed
    // (#1850). Times decode → handle → ack (`direction=in`) and the outbound policy push
    // (`direction=out`). `op` is the SAME bounded envelope enum as router_ws_frames_total, so no
    // per-mac / per-router / per-household dimension ever rides it — the §4 cardinality firewall.
    "router_ws_message_duration_seconds"        -> Set("op", "direction"),
    // #1849 — computed-snapshot cache + push-on-change. `policy_snapshot_build_total` splits policy
    // snapshot accesses into `result` ∈ {computed, cache_hit} (proves the cache works);
    // `router_ws_policy_push_total` is the push fan-out, `result` ∈ {ok, channel_closed}. Both are
    // fixed 2-value enums — bounded, no per-mac / per-host dimension. (Without these entries the
    // firewall would reject both names as unknown_name and the series would never emit.)
    "policy_snapshot_build_total"               -> Set("result"),
    "router_ws_policy_push_total"               -> Set("result"),
    // #1968 — SPA-websocket transport (server side, design `docs/design/spa-websocket.md` §7).
    // `spa_ws_connections_active` is the live count of open /api/ws channels per `role`
    // (admin|adult|child), refreshed on every register/deregister so a role's series ages out to 0
    // on disconnect. `spa_ws_frames_total` counts every frame demuxed/sent: `op` ∈ {hello, ready,
    // subscribe, unsubscribe, ack, ping, pong, reauth, unknown} (the fixed SPA envelope vocabulary;
    // `reauth` added by #1969 — the forward-compat reject seam),
    // `direction` ∈ {in, out}, `result` ∈ {ok, reject, unknown_op}. `spa_ws_subscriptions_active`
    // is the live count of subscriptions per `topic` (the SpaTopic enum). All bounded enums — no
    // per-mac / per-profile / per-session / param dimension ever rides a ws metric. (#1970 added
    // spa_ws_push_total{op,result} below for the S3 change-source fan-out.)
    "spa_ws_connections_active"                 -> Set("role"),
    "spa_ws_frames_total"                       -> Set("op", "direction", "result"),
    // #2168 — SPA per-ws-message-process latency, the browser-side twin of
    // router_ws_message_duration_seconds. Times decode → handle → ack for inbound frames
    // (`direction=in`) and the change-source push send (`direction=out`), labeled by the SAME
    // bounded op enum as spa_ws_frames_total / spa_ws_push_total. No per-mac / per-profile /
    // per-session / param dimension — the §4 cardinality firewall.
    "spa_ws_message_duration_seconds"           -> Set("op", "direction"),
    "spa_ws_subscriptions_active"               -> Set("topic"),
    // #1969 (S2) — upgrade-auth outcomes for `GET /api/ws` (design §4/§7). `result` ∈
    // {ok, no_cookie, invalid_jwt, expired_jwt, bad_origin, jwt_expired_midconn} — the
    // cookie-verify + Origin-allowlist outcomes plus the mid-connection expiry close. A
    // fixed 6-value enum; bounded, no per-user/session dimension. A spike in
    // bad_origin/invalid_jwt is the CSWSH/forgery-probe tripwire (alert in spa-ws.json).
    "spa_ws_auth_total"                         -> Set("result"),
    // #1970 (S3) / #1974 (S6a) — per-topic fan-out health for the change-source push path (design
    // §6.3/§7). `op` ∈ {now, connectionEvents, stale} (S3), trafficUsage (S4), timeStatus, appUsage
    // (S6a — the per-recipient time-usage pushes via SpaWsRegistry.deliver); `result` ∈ {ok,
    // coalesced, dropped, channel_closed}. Both bounded enums — no per-mac / per-profile /
    // per-session / param dimension. A rising channel_closed/dropped is the slow-client tripwire
    // surfaced by spa-ws.json's push-health panel.
    "spa_ws_push_total"                         -> Set("op", "result"),
    // #1848 — AGENT-side websocket transport metrics. The wifihaven-ws sidecar has no metrics
    // registry of its own, so it writes a cumulative tally that the agent folds into its
    // /api/router/metrics push (the server attaches router_id / installation_id, as for every
    // agent-pushed series). All bounded enums — never a per-mac / per-host dimension:
    //   ws_connect_total{result}   result ∈ {ok, upgrade_fail, auth_fail, timeout}  (reconnect health)
    //   ws_state                   1 connected / 0 disconnected (link gauge)
    //   ws_fallback_total{result}  result ∈ {to_http, back_to_ws}  (how often it fell back, §3.1)
    //   ws_frames_{sent,recv}_total{op}  op ∈ the fixed envelope vocabulary (usage/events/metrics/
    //                                    policy/ping/pong/ack/unknown)  (agent-side throughput)
    "ws_connect_total"                          -> Set("result", "router_id", "installation_id"),
    "ws_state"                                  -> Set("router_id", "installation_id"),
    "ws_fallback_total"                         -> Set("result", "router_id", "installation_id"),
    "ws_frames_sent_total"                      -> Set("op", "router_id", "installation_id"),
    "ws_frames_recv_total"                      -> Set("op", "router_id", "installation_id"),
    // #1718 — native OpenWRT OS-level metrics the agent collects from /proc/* and `iw dev`
    // and pushes through the existing #1205 batch transport. Gives operators the LuCI-style
    // view of router health (load / cpu / mem / bandwidth / conntrack / wifi clients) in
    // Grafana, so incidents like #1716 (prod router CPU pegged) show as a visible climb
    // rather than requiring an SSH session. Per the §4 cardinality firewall the per-MAC
    // wireless client list is NEVER a Prometheus dimension — only the aggregate
    // count-per-(iface,ssid) ships as a labeled series. Per-client drill-in would need a
    // separate DB-backed surface.
    "router_host_load_m1"                       -> Set("router_id", "installation_id"),
    "router_host_load_m5"                       -> Set("router_id", "installation_id"),
    "router_host_load_m15"                      -> Set("router_id", "installation_id"),
    "router_host_cpu_pct"                       -> Set("router_id", "installation_id"),
    "router_host_mem_total_kb"                  -> Set("router_id", "installation_id"),
    "router_host_mem_free_kb"                   -> Set("router_id", "installation_id"),
    "router_host_mem_buffers_kb"                -> Set("router_id", "installation_id"),
    "router_host_mem_cached_kb"                 -> Set("router_id", "installation_id"),
    "router_host_conntrack_count"               -> Set("router_id", "installation_id"),
    "router_host_iface_rx_bytes_total"          -> Set("iface", "router_id", "installation_id"),
    "router_host_iface_tx_bytes_total"          -> Set("iface", "router_id", "installation_id"),
    "router_host_wifi_clients"             -> Set("iface", "ssid", "router_id", "installation_id"),
    // #1243 rollup health — `rollup_job` is a handful of hand-named jobs (traffic_hourly,
    // traffic_daily, time_used_daily), `status` ∈ {ok, error}. Bounded; routed through the guard.
    "wifihaven_rollup_runs_total"          -> Set("rollup_job", "status"),
    "wifihaven_rollup_duration_seconds"    -> Set("rollup_job"),
    "wifihaven_rollup_rows_upserted"       -> Set("rollup_job"),
    // #1069 named-schedule CRUD — `op` ∈ {create, update, delete}, a fixed enum. Lets an operator
    // see schedule edits land (and rate-alert on a runaway delete loop) without grepping logs.
    "wifihaven_schedule_mutations_total"   -> Set("op"),
    // #578 — outbound admin-notification email funnel. `outcome` is a fixed 5-value enum
    // {sent, failed, skipped_disabled, skipped_no_recipient, skipped_no_household}; never a
    // per-recipient / per-household label.
    "notify_email_total"                   -> Set("outcome"),
    // #808 — partition runway gauge. `partition_weeks_ahead{table}` is the count of consecutive
    // weekly partitions present from the current ISO week for each RANGE-partitioned ingest table
    // (set each run by PartitionMaintenanceJob). `table` is the bounded 2-value enum above. The
    // runway alert pages when this drops below 2 — the "never silently exhaust again" guard for the
    // 2026-06-29 P0 (#2053). Without this entry the firewall would reject the name as unknown_name
    // and the series would never emit.
    "partition_weeks_ahead"                -> Set("table"),
    // #812 — partition-drop retention. `partitions_dropped_total{table}` counts weekly
    // partitions DETACHed+DROPped per run (metadata-only retention, replacing row-DELETE
    // for the two RANGE-partitioned tables). `table` is the same bounded 2-value enum.
    "partitions_dropped_total"             -> Set("table"),
    // #1243/#1221 HikariCP pool gauges — no labels.
    "wifihaven_db_pool_active_connections" -> Set.empty[String],
    "wifihaven_db_pool_idle_connections"   -> Set.empty[String],
    "wifihaven_db_pool_total_connections"  -> Set.empty[String],
    "wifihaven_db_pool_threads_awaiting_connection" -> Set.empty[String],
    "wifihaven_db_pool_max_size"                    -> Set.empty[String],
    // #2132 — beta request → provisioning pipeline outcomes. `stage` ∈ {request, approve, reject,
    // accept} and `outcome` a small fixed enum per stage (see AppMetrics.recordBetaPipeline);
    // both bounded, never a per-email / per-household value. Without this entry the firewall would
    // reject the name as unknown_name and the series would never emit (the #808 lesson).
    "wifihaven_beta_pipeline_total"                 -> Set("stage", "outcome"),
  )

  private val rejected = Metric.counter("metrics_rejected_total")

  private def reject(reason: String): UIO[Unit] =
    rejected.tagged("reason", reason).update(1L)

  /** None ⇒ rejected (already counted); Some(labels) ⇒ cleared to emit. */
  private def check(name: String, labels: Map[String, String]): UIO[Option[Map[String, String]]] =
    Allowed.get(name) match {
      case None          => reject("unknown_name").as(None)
      case Some(allowed) =>
        val keys = labels.keySet
        if keys.exists(ForbiddenKeys.contains) || !keys.subsetOf(allowed) then
          reject("forbidden_label").as(None)
        else ZIO.some(labels)
    }

  def counter(name: String, labels: Map[String, String], by: Long = 1L): UIO[Unit] =
    check(name, labels).flatMap {
      case None     => ZIO.unit
      case Some(ls) =>
        ls.foldLeft(Metric.counter(name))((m, kv) => m.tagged(kv._1, kv._2)).update(by)
    }

  def histogram(
      name: String,
      labels: Map[String, String],
      value: Double,
      boundaries: MetricKeyType.Histogram.Boundaries,
  ): UIO[Unit] =
    check(name, labels).flatMap {
      case None     => ZIO.unit
      case Some(ls) =>
        ls.foldLeft(Metric.histogram(name, boundaries))((m, kv) => m.tagged(kv._1, kv._2))
          .update(value)
    }

  def gauge(name: String, labels: Map[String, String], value: Double): UIO[Unit] =
    check(name, labels).flatMap {
      case None     => ZIO.unit
      case Some(ls) =>
        ls.foldLeft(Metric.gauge(name))((m, kv) => m.tagged(kv._1, kv._2)).update(value)
    }
}

/**
 * #1242 / #1243: all WifiHaven application metrics, published through the Prometheus registry
 * exposed at `GET /metrics`. Every series is a plain `zio.metrics.Metric`, so the connector's
 * periodic snapshot picks them up alongside the JVM/runtime metrics.
 */
object AppMetrics {

  // ── HTTP server (#1204) ─────────────────────────────────────────────────────
  // Emitted from HttpMetrics.instrument, which wraps every real route. `route` is
  // the *templated* path (e.g. /api/devices/:mac), never a concrete id — see §4.

  /** §5.2 latency SLO buckets: 5ms → 5s. */
  val HttpDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
    )

  def recordHttp(route: String, method: String, status: Int, durationSeconds: Double): UIO[Unit] =
    MetricGuard.counter(
      "http_requests_total",
      Map("route" -> route, "method" -> method, "status" -> status.toString),
    ) *>
      MetricGuard.histogram(
        "http_request_duration_seconds",
        Map("route" -> route, "method" -> method),
        math.max(0.0, durationSeconds),
        HttpDurationBoundaries,
      )

  // ── HTTP error responses (#1570) ─────────────────────────────────────────────
  // Emitted from ErrorBoundary for every response with status >= 400, alongside a
  // WARN (4xx) / ERROR (5xx) log. `route` is the templated path (same source as
  // recordHttp above) and `status` the HTTP code — both bounded; never a per-mac /
  // host / ip / user value.

  def recordHttpError(route: String, status: Int): UIO[Unit] =
    MetricGuard.counter(
      "api_errors_total",
      Map("route" -> route, "status" -> status.toString),
    )

  // ── DB query timing (#1204) ──────────────────────────────────────────────────
  // Emitted from DbMetrics.timed around the Doobie transact of hot repo methods.
  // `op` is a hand-named constant per method, never the SQL text. Two series per
  // op: the duration histogram (rate via _count, latency via the buckets) and a
  // db_queries_total{op,status} counter that splits ok vs error so the dashboard
  // can show a per-op success rate — a slow query and a *failing* query are
  // different incidents and an operator needs to tell them apart.

  /** Sub-millisecond → multi-second DB latency. */
  val DbDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
    )

  def recordDbQuery(op: String, durationSeconds: Double, status: String): UIO[Unit] =
    MetricGuard.histogram(
      "db_query_duration_seconds",
      Map("op" -> op),
      math.max(0.0, durationSeconds),
      DbDurationBoundaries,
    ) *>
      MetricGuard.counter("db_queries_total", Map("op" -> op, "status" -> status))

  // ── Auth failures (#1204) ────────────────────────────────────────────────────
  // `reason` ∈ {bad_password, expired_token, bad_router_token, forbidden_role}.

  def recordAuthFailure(reason: String): UIO[Unit] =
    MetricGuard.counter("auth_failures_total", Map("reason" -> reason))

  // #1069 — a named-schedule create / update / delete landed. `op` is a fixed enum.
  def scheduleMutation(op: String): UIO[Unit] =
    MetricGuard.counter("wifihaven_schedule_mutations_total", Map("op" -> op))

  // #578 — outbound admin-notification email attempts (the deferred email half of
  // the block-page kid→parent request flow). Emitted once per raised access-request
  // alert from Notifier.alertCreated. `outcome` is a small fixed enum, never a
  // per-recipient / per-household label:
  //   sent | failed                      — the transport was invoked (EmailOutcome)
  //   skipped_disabled                   — email transport unconfigured (no Resend secrets)
  //   skipped_no_recipient               — household has no notify_email set
  //   skipped_no_household               — alert's MAC has no device/household to resolve
  def notifyEmail(outcome: String): UIO[Unit] =
    MetricGuard.counter("notify_email_total", Map("outcome" -> outcome))

  // ── #2132: beta request → provisioning pipeline outcomes ─────────────────────
  // One counter for every terminal event in the beta pipeline (design §3), so the
  // operator can watch intake volume, approval/rejection rate, and accept success.
  // `stage` ∈ {request, approve, reject, accept}; `outcome` is a small fixed enum
  // per stage — both bounded, never a per-email / per-household label.
  //   request: created | duplicate | rate_limited
  //   approve: ok | not_pending | error
  //   reject:  ok | not_pending
  //   accept:  ok | invalid_token | expired | error
  def recordBetaPipeline(stage: String, outcome: String): UIO[Unit] =
    MetricGuard.counter(
      "wifihaven_beta_pipeline_total",
      Map("stage" -> stage, "outcome" -> outcome),
    )

  // ── #864: traffic_reports rows dropped as zero-bytes-zero-seconds ────────────
  // Replaces the per-request warn-log + TODO marker. A rising rate means the
  // #858 agent regression (emitting empty rows) has returned.

  def recordZeroByteFiltered(rows: Int): UIO[Unit] =
    ZIO
      .when(rows > 0)(
        MetricGuard.counter(
          "traffic_reports_filtered_zero_bytes_total",
          Map.empty,
          rows.toLong,
        ),
      )
      .unit

  // ── #1569: usage records dropped at decode ───────────────────────────────────
  // A malformed record (host failing Hostname validation, etc.) is skipped at
  // ingest instead of failing the whole batch. A rising rate flags an agent/DNS
  // source emitting host values the API rejects (the #1572 CNAME-target case).
  // `reason` is a small fixed enum — only `decode_error` today.

  // ── #1585: traffic_reports write-time FQDN backfill outcomes ───────────────
  // Per-UsageRecord ingest decision: `filled` (race-loser ipv4/ipv6 rewritten
  // to a fqdn from a recent connection_event), `miss` (candidate but no fqdn
  // in the window — operator can rate-alert on a rising miss share to spot
  // unattributable destinations), or `skip` (already fqdn, or no dest_ip).
  // `result` is the small fixed enum.

  def recordTrafficBackfill(result: String): UIO[Unit] =
    MetricGuard.counter("traffic_reports_backfill_total", Map("result" -> result))

  def recordUsageRecordsRejected(count: Int, reason: String = "decode_error"): UIO[Unit] =
    ZIO
      .when(count > 0)(
        MetricGuard.counter("usage_records_rejected_total", Map("reason" -> reason), count.toLong),
      )
      .unit

  // #1757 — events-ingest per-record skip count. Same wire shape as the usage
  // counter above; emitted from RouterIngestRoutes when one or more events in
  // a batch fail individual decode but the envelope itself parses.
  def recordEventsRecordsRejected(count: Int, reason: String = "decode_error"): UIO[Unit] =
    ZIO
      .when(count > 0)(
        MetricGuard.counter("events_records_rejected_total", Map("reason" -> reason), count.toLong),
      )
      .unit

  // ── #1676: phantom-suppression drop count ───────────────────────────────────
  // Emitted from TimeStatusService.appSecondsByAppWithDropCount, which threads
  // the count out of Presence.appSpansForProfileWithDropCount (#1666 anchor-row
  // guard). Unlabelled — the guard is unconditional, and per-mac / per-app
  // would breach the cardinality firewall.

  def recordAppSessionsDropped(count: Int): UIO[Unit] =
    ZIO
      .when(count > 0)(
        MetricGuard.counter(
          "presence_app_sessions_dropped_total",
          Map.empty,
          count.toLong,
        ),
      )
      .unit

  // ── #2077: ambient anchor-gate observability ─────────────────────────────────
  // `presence_ambient_spans_dropped_total` is emitted from the TimeUsedRollupJob
  // tick (same single-cadence rationale as the #1676 counter above): device
  // presence spans dropped because they contained no engagement anchor. A
  // sustained rise with flat screen-time means the learner is eating real
  // sessions (over-suppression); a flat zero while phantom idle-time returns
  // means the thresholds are too lax. `presence_ambient_hosts` is the size of
  // the learned ambient set, set on each AmbientLearnJob run. Both unlabelled —
  // per-host/mac labels would breach the cardinality firewall.

  def recordAmbientSpansDropped(count: Int): UIO[Unit] =
    ZIO
      .when(count > 0)(
        MetricGuard.counter(
          "presence_ambient_spans_dropped_total",
          Map.empty,
          count.toLong,
        ),
      )
      .unit

  def setAmbientHosts(count: Int): UIO[Unit] =
    MetricGuard.gauge("presence_ambient_hosts", Map.empty, count.toDouble)

  // ── #1898: shared-host reporting attribution outcome ─────────────────────────
  // Emitted from UsageRoutes.buildUsageByApp, counted per shared-host stitched span:
  // `attributed` (one co-present qualifier), `split` (≥2 qualifiers, bytes split
  // equally), `other` (no qualifier → "Other"/orphan bucket). Bounded `outcome`
  // label (exactly three values) — no per-mac/host/app cardinality. A sustained
  // rise in `other` means shared backends are firing with no app demonstrably in
  // use; a rise in `split` means several apps' distinctive sessions co-occur.

  def recordSharedHostAttribution(outcome: String, count: Long): UIO[Unit] =
    ZIO
      .when(count > 0L)(
        MetricGuard.counter(
          "usage_shared_host_attribution_total",
          Map("outcome" -> outcome),
          count,
        ),
      )
      .unit

  // ── Fleet liveness (#1204) ───────────────────────────────────────────────────
  // Set by RouterPresenceMetrics: routers seen (last_seen_at) within the window.

  def setConnectedRouters(count: Int): UIO[Unit] =
    MetricGuard.gauge("agent_connected_routers", Map.empty, count.toDouble)

  // ── Global policy layer (#1318/#1775) ───────────────────────────────────────
  // Emitted from PolicyService.snapshot each time the snapshot is assembled.
  // `global_allow_hosts` tracks the size of the fleet-wide always-reachable set
  // (`global.extraAllowed`); post-#1775 this is sourced entirely from compile-time
  // config (`uiAllowedHosts` + `infraAllowHosts`), so the gauge is a static
  // sanity check the deployment loaded its allow set rather than a live-growth
  // signal. `default_deny_profiles` counts profiles running the block-all
  // baseline. Both unlabelled, gated by the cardinality firewall on the name.

  def setGlobalPolicy(globalAllowHosts: Int, defaultDenyProfiles: Int): UIO[Unit] =
    MetricGuard.gauge("wifihaven_global_allow_hosts", Map.empty, globalAllowHosts.toDouble) *>
      MetricGuard.gauge(
        "wifihaven_default_deny_profiles",
        Map.empty,
        defaultDenyProfiles.toDouble,
      )

  // ── Router metrics ingest (#1205) ────────────────────────────────────────────
  // One increment per POST /api/router/metrics. `status` ∈ {ok, malformed,
  // router_mismatch}. The concrete server-side health signal for the push path.

  def recordRouterMetricsBatch(status: String): UIO[Unit] =
    MetricGuard.counter("router_metrics_batches_total", Map("status" -> status))

  // ── Loki appender fail-open drops (#1885) ────────────────────────────────────
  // Emitted from LokiDropMetrics, which polls the MeteredLoki4jAppender's
  // cumulative drop counter and feeds the per-tick positive delta here. A drop
  // means the async appender shed a log line because its bounded send queue was
  // full (Loki slow/unreachable) — the fail-open path that keeps the request
  // thread unblocked. A sustained rate is the alert that logs are being lost.
  // Unlabelled; only emits when `delta > 0` so a healthy appender never touches
  // the series.

  def recordLokiDropped(delta: Long): UIO[Unit] =
    ZIO
      .when(delta > 0)(
        MetricGuard.counter("loki_logs_dropped_total", Map.empty, delta),
      )
      .unit

  // #1972 — loki4j send-side errors (401/403/4xx/5xx/timeout) the appender reported
  // to the logback StatusManager. Unlabelled; only emits when `delta > 0`.
  def recordLokiSendErrors(delta: Long): UIO[Unit] =
    ZIO
      .when(delta > 0)(
        MetricGuard.counter("loki_logs_send_errors_total", Map.empty, delta),
      )
      .unit

  // ── Websocket router transport (#1846) ───────────────────────────────────────
  // Set by RouterWsRegistry on every register/deregister: the live count of open
  // ws channels. Refreshed to the recomputed total each time so a disconnect drives
  // it back down (no stale high-water value). Unlabelled household-scoped gauge.

  def setWsConnectionsActive(count: Int): UIO[Unit] =
    MetricGuard.gauge("router_ws_connections_active", Map.empty, count.toDouble)

  // Emitted from RouterWsRoutes for every frame demuxed (`direction=in`) or sent
  // (`direction=out`). `op` is the envelope discriminator (a fixed enum), `direction`
  // ∈ {in, out}, `result` ∈ {ok, reject, unknown_op}. `unknown_op` is the
  // forward-compat counter (design §1.3 — an unrecognized op is ignored + metered).
  def recordWsFrame(op: String, direction: String, result: String): UIO[Unit] =
    MetricGuard.counter(
      "router_ws_frames_total",
      Map("op" -> op, "direction" -> direction, "result" -> result),
    )

  // #2168: per-ws-message-process latency — the ws analog of http_request_duration_seconds. Emitted
  // from RouterWsRoutes (inbound: decode → handle → ack, `direction=in`) and RouterWsRegistry
  // (outbound policy push send, `direction=out`). `op` is the same bounded envelope enum as
  // recordWsFrame (unknown ops collapse to `unknown` upstream). Reuses the HTTP latency buckets so a
  // ws op's latency is directly comparable to the REST endpoint it replaces across the #1023 cutover.
  def recordWsMessageDuration(op: String, direction: String, durationSeconds: Double): UIO[Unit] =
    MetricGuard.histogram(
      "router_ws_message_duration_seconds",
      Map("op" -> op, "direction" -> direction),
      math.max(0.0, durationSeconds),
      HttpDurationBoundaries,
    )

  // #1849: counts policy-snapshot accesses split by whether they hit the computed-snapshot cache.
  // `computed` = an actual PolicyService rebuild (the ~500ms #1512 work); `cache_hit` = served from
  // the cache without rebuilding. The ratio proves the cache is working — once the reconcile ticker
  // keeps the cache warm, REST polls and ws fan-outs should be overwhelmingly `cache_hit`, with
  // `computed` tracking the change/tick rate rather than the poll rate. `result` is a fixed 2-value
  // enum (bounded label, per docs/process/instrumentation.md).
  def recordSnapshotBuild(result: String): UIO[Unit] =
    MetricGuard.counter("policy_snapshot_build_total", Map("result" -> result))

  // #1849: emitted by RouterWsRegistry.publishPolicy for each fan-out attempt of a changed snapshot.
  // `result` ∈ {ok, channel_closed} — `ok` = the frame was handed to the channel, `channel_closed`
  // = the send failed (a racing disconnect) and the channel is dropped. Bounded enum.
  def recordWsPolicyPush(result: String): UIO[Unit] =
    MetricGuard.counter("router_ws_policy_push_total", Map("result" -> result))

  // ── SPA websocket transport (#1968) ──────────────────────────────────────────
  // The browser-facing /api/ws endpoint (design `docs/design/spa-websocket.md` §7).
  // Set by SpaWsRegistry on every register/deregister/subscribe/unsubscribe: the
  // live count of open channels per `role` and live subscriptions per `topic`,
  // recomputed and re-published across the bounded role/topic enums so a label whose
  // count fell to zero is set 0 rather than left stale. recordSpaWsFrame is emitted
  // from SpaWsRoutes for every frame demuxed (`direction=in`) or sent (`out`); `op`
  // is the SPA envelope discriminator, `result` ∈ {ok, reject, unknown_op}. All
  // bounded enums — never a per-mac / per-profile / per-session / param dimension.

  def setSpaWsConnectionsActive(role: String, count: Int): UIO[Unit] =
    MetricGuard.gauge("spa_ws_connections_active", Map("role" -> role), count.toDouble)

  def setSpaWsSubscriptionsActive(topic: String, count: Int): UIO[Unit] =
    MetricGuard.gauge("spa_ws_subscriptions_active", Map("topic" -> topic), count.toDouble)

  def recordSpaWsFrame(op: String, direction: String, result: String): UIO[Unit] =
    MetricGuard.counter(
      "spa_ws_frames_total",
      Map("op" -> op, "direction" -> direction, "result" -> result),
    )

  // #2168: SPA per-ws-message-process latency (browser-side twin of recordWsMessageDuration).
  // Emitted from SpaWsRoutes (inbound: decode → handle → ack, `direction=in`) and SpaWsRegistry
  // (outbound change-source push send, `direction=out`). `op` is the same bounded enum as
  // recordSpaWsFrame / recordSpaWsPush. Same HTTP latency buckets for cross-transport comparison.
  def recordSpaWsMessageDuration(
      op: String,
      direction: String,
      durationSeconds: Double,
  ): UIO[Unit] =
    MetricGuard.histogram(
      "spa_ws_message_duration_seconds",
      Map("op" -> op, "direction" -> direction),
      math.max(0.0, durationSeconds),
      HttpDurationBoundaries,
    )

  // #1969 (S2) — emitted from SpaWsRoutes at upgrade time (cookie verify + Origin check)
  // and from the per-connection expiry watcher. `result` ∈ {ok, no_cookie, invalid_jwt,
  // expired_jwt, bad_origin, jwt_expired_midconn} — a fixed bounded enum, never a per-user
  // dimension. The verify itself reuses AuthService (SSOT); this only labels the outcome.
  def recordSpaWsAuth(result: String): UIO[Unit] =
    MetricGuard.counter("spa_ws_auth_total", Map("result" -> result))

  // #1970 (S3) — emitted from SpaWsRegistry on every change-source fan-out (design §5.2/§6.3).
  // `op` is the pushed topic (now|connectionEvents|stale today), `result` ∈
  // {ok, coalesced, dropped, channel_closed} — a fixed bounded enum, never a per-entity dimension.
  def recordSpaWsPush(op: String, result: String): UIO[Unit] =
    MetricGuard.counter("spa_ws_push_total", Map("op" -> op, "result" -> result))

  // §5.1 — server-side histogram boundaries for the router-pushed duration histograms. The agent
  // (#1206) reports cumulative bucket counts on these same boundaries; RouterMetricsService folds
  // the per-batch bucket-count deltas back into these registry histograms.
  val PolicyApplyDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(Chunk(0.01, 0.05, 0.1, 0.5, 1.0, 5.0))

  val SnapshotPollDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(Chunk(0.01, 0.05, 0.1, 0.5, 1.0, 5.0))

  /**
   * Boundaries keyed by router-pushed histogram name; the fold falls back to this when unmatched.
   */
  val RouterHistogramBoundaries: Map[String, MetricKeyType.Histogram.Boundaries] = Map(
    "policy_apply_duration_seconds"  -> PolicyApplyDurationBoundaries,
    "snapshot_poll_duration_seconds" -> SnapshotPollDurationBoundaries,
  )

  // ── Rollup health (#1243) ──────────────────────────────────────────────────
  // Emitted from RollupRepoLive.recordRun — the single completion point both the
  // hourly/daily byte-rollup fibers and the time_used_daily fiber funnel through.
  // Routed through MetricGuard (#1210) so the cardinality firewall covers these
  // series too, not just the HTTP/router-pushed ones.

  // Sub-second to multi-minute coverage: a prod rollup over a growth table can
  // run for minutes (#1197), so the boundaries span 0.05s → ~200s.
  val RollupDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.exponential(0.05, 2.0, 13)

  /** Record a completed rollup run. `status` is "ok" or "error". */
  def recordRollup(job: String, status: String, durationSeconds: Double, rows: Int): UIO[Unit] =
    MetricGuard.counter(
      "wifihaven_rollup_runs_total",
      Map("rollup_job" -> job, "status" -> status),
    ) *>
      MetricGuard.histogram(
        "wifihaven_rollup_duration_seconds",
        Map("rollup_job" -> job),
        math.max(0.0, durationSeconds),
        RollupDurationBoundaries,
      ) *>
      MetricGuard.gauge("wifihaven_rollup_rows_upserted", Map("rollup_job" -> job), rows.toDouble)

  // ── Partition runway (#808) ──────────────────────────────────────────────────
  // Set by PartitionMaintenanceJob each run: per partitioned table, the number of consecutive
  // weekly partitions present starting at the current ISO week (the write runway). A value of 0
  // means even the current week has no partition — the 2026-06-29 P0 (#2053) state. The runway
  // alert pages when the worst table drops below 2, so the fixed runway can never silently exhaust
  // again. `table` is a bounded 2-value enum (the PartitionRepo.PartitionedTables names).

  def setPartitionWeeksAhead(table: String, weeksAhead: Int): UIO[Unit] =
    MetricGuard.gauge("partition_weeks_ahead", Map("table" -> table), weeksAhead.toDouble)

  // ── Partition-drop retention (#812) ─────────────────────────────────────────
  // Incremented by PartitionMaintenanceJob each run: per partitioned table, the count of weekly
  // partitions DETACHed+DROPped in that pass (0 most runs — only nonzero when a partition just
  // aged out of its retention window). `table` is the same bounded 2-value enum as above.
  def incrementPartitionsDropped(table: String, count: Int): UIO[Unit] =
    MetricGuard
      .counter("partitions_dropped_total", Map("table" -> table), count.toLong)
      .when(count > 0)
      .unit

  // ── DB connection pool (#1243, #1221) ───────────────────────────────────────
  // Set from the polling fiber in DbPoolMetrics. threads_awaiting was the
  // leading indicator of the 2026-05-31 pool-exhaustion crash loop. Routed
  // through MetricGuard (#1210) — unlabelled, but the firewall still gates the name.

  def setDbPool(stats: DbPoolStats): UIO[Unit] =
    MetricGuard.gauge("wifihaven_db_pool_active_connections", Map.empty, stats.active.toDouble) *>
      MetricGuard.gauge("wifihaven_db_pool_idle_connections", Map.empty, stats.idle.toDouble) *>
      MetricGuard.gauge("wifihaven_db_pool_total_connections", Map.empty, stats.total.toDouble) *>
      MetricGuard.gauge(
        "wifihaven_db_pool_threads_awaiting_connection",
        Map.empty,
        stats.threadsAwaiting.toDouble,
      ) *>
      MetricGuard.gauge("wifihaven_db_pool_max_size", Map.empty, stats.maxSize.toDouble)
}

/** Point-in-time HikariCP pool snapshot. */
final case class DbPoolStats(
    active: Int,
    idle: Int,
    total: Int,
    threadsAwaiting: Int,
    maxSize: Int,
)

/** #1243: poll the HikariCP MXBean and publish the pool gauges. */
object DbPoolMetrics {

  /** Default cadence; chosen short enough to catch a saturation spike before the 30s timeout. */
  val DefaultInterval: Duration = 10.seconds

  /**
   * Read the live pool stats. The MXBean is null until the pool is initialised (first connection),
   * so we fall back to zeros for the dynamic counters while still reporting the configured max.
   */
  def read(ds: HikariDataSource, maxSize: Int): UIO[DbPoolStats] =
    ZIO.succeed {
      Option(ds.getHikariPoolMXBean) match {
        case Some(mx) =>
          DbPoolStats(
            active = mx.getActiveConnections,
            idle = mx.getIdleConnections,
            total = mx.getTotalConnections,
            threadsAwaiting = mx.getThreadsAwaitingConnection,
            maxSize = maxSize,
          )
        case None     =>
          DbPoolStats(0, 0, 0, 0, maxSize)
      }
    }

  def pollOnce(ds: HikariDataSource, maxSize: Int): UIO[Unit] =
    read(ds, maxSize).flatMap(AppMetrics.setDbPool)

  /** Fiber loop: poll every `interval`. Never fails; intended to be forked as a daemon. */
  def loop(ds: HikariDataSource, maxSize: Int, interval: Duration = DefaultInterval): UIO[Unit] =
    pollOnce(ds, maxSize).repeat(Schedule.fixed(interval)).unit
}

/**
 * #1204: time the Doobie transact of a repo method into `db_query_duration_seconds{op}` and count
 * it in `db_queries_total{op,status}`. `op` is a hand-named constant supplied at the call site —
 * never derived from the SQL — so the label space stays a small, known enum. Records on every exit
 * so a slow failing query is still visible; `status` is `ok` on success and `error` otherwise (a
 * failure or interruption), which is what drives the per-op success-rate panel.
 */
object DbMetrics {
  def timed[A](op: String)(query: Task[A]): Task[A] =
    Clock.nanoTime.flatMap { start =>
      query.onExit { exit =>
        Clock.nanoTime.flatMap(end =>
          AppMetrics.recordDbQuery(
            op,
            (end - start) / 1e9d,
            if exit.isSuccess then "ok" else "error",
          ),
        )
      }
    }
}

/**
 * #1204: publish `agent_connected_routers` — the single "is the fleet alive?" gauge. Counts routers
 * whose `last_seen_at` is within `window` (touched on every policy poll + usage/event push). Pure
 * read path: a periodic `SELECT count(*)`, no migration needed.
 */
object RouterPresenceMetrics {

  /** A router that hasn't been seen within this window is treated as disconnected. */
  val DefaultWindow: Duration = 10.minutes

  /** Poll cadence; well below the window so the gauge tracks fleet state promptly. */
  val DefaultInterval: Duration = 30.seconds

  def pollOnce(repo: RouterRepo, window: Duration): UIO[Unit] =
    (for {
      now   <- Clock.instant
      count <- repo.countSeenSince(now.minus(window))
      _     <- AppMetrics.setConnectedRouters(count)
    } yield ()).catchAll(e =>
      ZIO.logWarning(s"agent_connected_routers poll failed: ${e.getMessage}"),
    )

  /** Fiber loop; never fails. Intended to be forked as a daemon. */
  def loop(
      repo: RouterRepo,
      window: Duration = DefaultWindow,
      interval: Duration = DefaultInterval,
  ): UIO[Unit] =
    pollOnce(repo, window).repeat(Schedule.fixed(interval)).unit
}

/** #1242: Prometheus connector wiring — publisher + the periodic snapshot listener. */
object MetricsRuntime {

  /** Snapshot cadence for the Prometheus listener; Prometheus scrapes typically every 15–30s. */
  val DefaultInterval: Duration = 5.seconds

  /**
   * Provides the [[PrometheusPublisher]] (whose `get` renders the exposition text) plus the
   * background listener fiber that snapshots the metric registry every `interval`. The `Unit`
   * output of `prometheusLayer` and the `ConnectorsConfig` are folded in via `>+>`; the wider
   * intersection is a subtype of the declared `PrometheusPublisher` output.
   */
  def prometheus(
      interval: Duration = DefaultInterval,
  ): ZLayer[Any, Nothing, PrometheusPublisher] = {
    val cfgAndPublisher =
      ZLayer.succeed(ConnectorsConfig(interval)) ++ connectors.prometheus.publisherLayer
    cfgAndPublisher >+> connectors.prometheus.prometheusLayer
  }
}
