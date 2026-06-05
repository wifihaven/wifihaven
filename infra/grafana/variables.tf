variable "grafana_url" {
  type        = string
  sensitive   = true
  description = "Base URL of the Grafana Cloud stack (e.g. https://<stack>.grafana.net). Find in Grafana Cloud → Stack details. Marked sensitive because the stack subdomain is account-identifying."
}

variable "grafana_auth" {
  type        = string
  sensitive   = true
  description = "Grafana service-account token with dashboard write scope. Create in Grafana Cloud → Administration → Service accounts. In CD this is the GRAFANA_AUTH secret; never committed."
}

variable "folder_uid" {
  type        = string
  default     = ""
  description = "Optional uid of a pre-existing Grafana folder to place the dashboards in. Leave empty to use the stack's General folder. We do not manage the folder as a resource so the apply stays stateless-idempotent in CD (see main.tf)."
}

# ── Alerting (#1368 / docs/design/alerting.md) ───────────────────────────────
# Required only once the alerting resources (alerting.tf) are applied, which is
# gated on the #1406 remote-state migration. No default: alerting must not ship
# with a placeholder recipient. In CD this is fed from an OPERATOR_EMAIL secret
# (wired alongside the #1406 backend), the same handling as grafana_auth.
variable "operator_email" {
  type        = string
  sensitive   = true
  description = "Email address that alert notifications are sent to (the single household operator). Used by the wifihaven-critical / -warning / -staging contact points. Not a secret per se, but marked sensitive to keep PII out of plan/apply logs. Never committed."
}

variable "prometheus_datasource_uid" {
  type        = string
  default     = "grafanacloud-prom"
  description = "UID of the Prometheus datasource the alert rules evaluate against. Unlike dashboards (which resolve a templated datasource variable at view time), managed alert rules evaluate server-side and need a concrete datasource uid. Default is Grafana Cloud's built-in Prometheus uid; override for a self-hosted stack. Find it under Connections -> Data sources -> Prometheus -> the uid in the URL."
}
