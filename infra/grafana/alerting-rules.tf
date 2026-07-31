# Shared infrastructure for the Grafana managed alert rule groups
# (docs/design/alerting.md §7). The rule sets themselves live in sibling files
# so the two parallel work streams stay separable:
#   - alerting-rules-warning.tf  → the `warning` group  (#1405, W1–W5; #2416, W6–W7; #2488, W8)
#   - alerting-rules-critical.tf → the `critical` group (#1404, C1–C7)
#
# Both rule groups attach to the single folder below and query the Prometheus
# datasource named by var.prometheus_datasource_uid.
#
# Why a managed folder here (the dashboards deliberately do NOT manage one):
# a Grafana managed alert rule group requires a concrete, non-empty folder_uid
# — there is no "General folder" fallback for rules the way there is for
# dashboards. The dashboards stayed folder-less to keep their apply
# stateless-idempotent, but that argument is moot now: the alerting resources
# are stateful by nature and the HCP remote backend (#1406) already owns their
# state, so a managed folder reconciles idempotently alongside them. Keeping it
# in-repo (rather than a hand-created folder + a uid variable) keeps the apply
# fully reproducible per the repo's declarative-config preference.

resource "grafana_folder" "alerts" {
  title = "WifiHaven Alerts"
}
