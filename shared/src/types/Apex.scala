package wifihaven.shared.types

import com.google.common.net.InternetDomainName

/**
 * PSL-aware "registered domain" lookup, scoped to the ICANN section only. `foo.bar.youtube.com` and
 * `m.youtube.com` both collapse to `youtube.com`; `something.co.uk` collapses to itself (the ICANN
 * PSL knows `co.uk` is a public suffix). Hostnames that have no recognised ICANN suffix (e.g.
 * single-label `localhost`, internal `.lan` names) return None — callers decide whether to fall
 * back to the raw hostname.
 *
 * We deliberately use `topDomainUnderRegistrySuffix()` (ICANN only) rather than the more strict
 * `topPrivateDomain()` (ICANN + PRIVATE), because the PRIVATE section pushes each S3 bucket /
 * CloudFront distribution / appspot tenant into its own "apex." That's correct PSL semantics but
 * useless for the apps picker, where the operator wants all `*.amazonaws.com` traffic under one
 * row. Trade-off: `myname.github.io` rolls up to `github.io` instead of staying per-user — fine for
 * the picker's "block as a unit" use case (#766).
 *
 * Backed by Guava's bundled snapshot of Mozilla's public_suffix_list.dat.
 */
object Apex {

  def of(h: Hostname): Option[Hostname] =
    try {
      val idn = InternetDomainName.from(h.value)
      if (idn.hasRegistrySuffix && idn.topDomainUnderRegistrySuffix() != null)
        Hostname.parse(idn.topDomainUnderRegistrySuffix().toString).toOption
      else None
    } catch {
      case _: IllegalArgumentException => None
      case _: IllegalStateException    => None
    }

  /** Apex if PSL recognises the suffix, otherwise the hostname itself. */
  def orSelf(h: Hostname): Hostname = of(h).getOrElse(h)
}
