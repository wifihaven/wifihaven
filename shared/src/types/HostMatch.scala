package wifihaven.shared.types

/**
 * Suffix-based host matching, used everywhere the service needs to ask "does this FQDN belong to
 * that apex/app/pattern?" (#1085). Consolidates three previously-duplicated implementations
 * (PolicyService.matchesDomainPattern, Presence.matchesPattern, the old strict-equality
 * UsageTraffic.appsByHost lookup) so the rules are identical across policy enforcement, presence
 * filtering, and usage attribution.
 *
 * Inputs are expected lowercased — `Hostname.parse` already does this; raw operator-typed strings
 * should be normalized at the boundary. All matching is case-sensitive on the assumption that
 * normalization has already happened.
 */
object HostMatch {

  /**
   * Apex suffix match: `host` equals `apex`, OR `host` ends with `"." + apex`. This is the
   * canonical "FQDN belongs to apex" predicate — `www.youtube.com` matches `youtube.com`,
   * `youtube.com` matches `youtube.com`, `notyoutube.com` does NOT.
   */
  def matchesApex(host: String, apex: String): Boolean =
    host == apex || host.endsWith("." + apex)

  /**
   * Pattern match with optional `*.` prefix. `*.foo.com` is equivalent to apex `foo.com` — both
   * match `foo.com` itself plus any subdomain. Bare `foo.com` patterns behave identically. This is
   * the form stored in `site_time_limits.domain_pattern` (operator may type either spelling).
   */
  def matchesPattern(host: String, pattern: String): Boolean = {
    val apex = if (pattern.startsWith("*.")) pattern.drop(2) else pattern
    matchesApex(host, apex)
  }

  /**
   * Walk the dot-separated tails of `host` and return the first one present in `apexes` (or its
   * full-host exact match). Bounded by `maxHops` since FQDNs deeper than ~5 labels are rare and
   * each step is a hash lookup. Returns None for hosts that match no apex (caller decides whether
   * to bucket to "Other", deny, etc.).
   *
   * `host` is expected to be a bare FQDN (no `*.`). IP-literal callers should filter before calling
   * — `192.168.1.1` will walk tails harmlessly but the result is never useful.
   */
  def lookupApex[T](host: String, apexes: Map[String, T], maxHops: Int = 5): Option[T] = {
    @annotation.tailrec
    def loop(h: String, hops: Int): Option[T] =
      apexes.get(h) match {
        case some @ Some(_) => some
        case None           =>
          val dot = h.indexOf('.')
          if (dot < 0 || hops <= 0) None
          else loop(h.substring(dot + 1), hops - 1)
      }
    loop(host, maxHops)
  }

  /** Boolean variant of [[lookupApex]] over a set of apexes. */
  def hasApexMatch(host: String, apexes: Set[String], maxHops: Int = 5): Boolean = {
    @annotation.tailrec
    def loop(h: String, hops: Int): Boolean =
      if (apexes.contains(h)) true
      else {
        val dot = h.indexOf('.')
        if (dot < 0 || hops <= 0) false
        else loop(h.substring(dot + 1), hops - 1)
      }
    loop(host, maxHops)
  }
}
