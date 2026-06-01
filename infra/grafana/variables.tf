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
