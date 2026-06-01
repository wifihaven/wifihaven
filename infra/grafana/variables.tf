variable "grafana_url" {
  type        = string
  sensitive   = true
  description = "Base URL of the Grafana Cloud stack (e.g. https://<stack>.grafana.net). Find in Grafana Cloud → Stack details. Marked sensitive because the stack subdomain is account-identifying."
}

variable "grafana_auth" {
  type        = string
  sensitive   = true
  description = "Grafana service-account token with dashboard + folder write scope. Create in Grafana Cloud → Administration → Service accounts. Supplied via GRAFANA_AUTH; never committed."
}
