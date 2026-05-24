package wifihaven.shared.types

import com.google.common.net.InternetDomainName

/**
 * Public-Suffix-List-aware "registered domain" lookup. `foo.bar.youtube.com` and `m.youtube.com`
 * both collapse to `youtube.com`; `something.co.uk` collapses to itself (the PSL knows `co.uk` is a
 * public suffix). Hostnames that have no recognized public suffix (e.g. single-label `localhost`,
 * internal `.lan` names) return None — callers decide whether to fall back to the raw hostname.
 *
 * Backed by Guava's `InternetDomainName.topPrivateDomain()`, which ships a bundled snapshot of
 * Mozilla's public_suffix_list.dat.
 */
object Apex {

  def of(h: Hostname): Option[Hostname] =
    try {
      val idn = InternetDomainName.from(h.value)
      if (idn.hasPublicSuffix && idn.topPrivateDomain() != null)
        Hostname.parse(idn.topPrivateDomain().toString).toOption
      else None
    } catch {
      case _: IllegalArgumentException => None
      case _: IllegalStateException    => None
    }

  /** Apex if PSL recognises the suffix, otherwise the hostname itself. */
  def orSelf(h: Hostname): Hostname = of(h).getOrElse(h)
}
