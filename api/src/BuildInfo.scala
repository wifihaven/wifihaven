package wifihaven.api

/**
 * Build identity of the running image (#1140). Lets CD prove the freshly-built image is the one
 * actually serving — a green `/api/health` alone can't, since the old container keeps answering 200
 * during a Render rollover.
 *
 * `version` is the derived semver (see scripts/ci/derive-version.sh); `sha` is the git short SHA of
 * the commit the image was built from. Both are baked into the image at build time via Docker ENV
 * (see docker/Dockerfile + .github/workflows/master-api-ui.yml) and read here from the environment.
 * Local/dev runs where nothing is injected fall back to `dev` / `unknown`.
 */
final case class BuildInfo(version: String, sha: String)

object BuildInfo {
  val DevVersion = "dev"
  val UnknownSha = "unknown"

  def fromEnv: BuildInfo =
    BuildInfo(
      version = resolve(sys.env.get("WIFIHAVEN_VERSION"), DevVersion),
      sha = resolve(sys.env.get("WIFIHAVEN_GIT_SHA"), UnknownSha),
    )

  private[api] def resolve(raw: Option[String], fallback: String): String =
    raw.map(_.trim).filter(_.nonEmpty).getOrElse(fallback)
}
