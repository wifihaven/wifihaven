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

variable "operator_email" {
  type        = string
  sensitive   = true
  description = "Email address that all alert contact points deliver to (the single household operator). Same handling as grafana_auth: never committed — supplied via terraform.tfvars / TF_VAR_operator_email locally, and the GRAFANA_OPERATOR_EMAIL secret (fed as TF_VAR_operator_email) in CD. See docs/design/alerting.md §4."
}

variable "folder_uid" {
  type        = string
  default     = ""
  description = "Optional uid of a pre-existing Grafana folder to place the dashboards in. Leave empty to use the stack's General folder. We do not manage the folder as a resource so the apply stays stateless-idempotent in CD (see main.tf)."
}

variable "prometheus_datasource_uid" {
  type        = string
  default     = "grafanacloud-prom"
  description = "UID of the Prometheus datasource the alert rules query. Unlike the dashboards (which use a templated $${datasource} resolved at view time), Grafana managed alert rules must reference a concrete datasource UID. Defaults to grafanacloud-prom, the UID of the built-in Grafana Cloud hosted Prometheus. Override (TF_VAR_prometheus_datasource_uid) only for a self-hosted stack whose datasource UID differs. Not a secret. See docs/design/alerting.md §3."
}
