package wifihaven.api.presence

/**
 * #2077: runtime input to the engagement-anchor gate — the master switch plus the isolation-learned
 * ambient host set (exact `HostId.value` strings, as learned into `ambient_host_days` by the daily
 * learner and thresholded by `household_settings.ambient_min_isolated_days` /
 * `ambient_learning_window_days`). See docs/design/idle-traffic-discrimination.md.
 */
final case class AmbientGate(enabled: Boolean, hosts: Set[String])

object AmbientGate {

  /** Gate off — the identity everywhere it is threaded. */
  val Off: AmbientGate = AmbientGate(enabled = false, Set.empty)
}
