# Prefer declarative config over dashboard toggles

This was originally in AGENTS.md §"Prefer declarative config over dashboard toggles"; see AGENTS.md for the TOC.

## Prefer declarative config over dashboard toggles

Anywhere a piece of infrastructure can be configured declaratively in-repo
(Render Blueprint `render.yaml`, GitHub Actions workflows, repo settings via
`gh` API, DNS via a checked-in zone file, etc.), do it there instead of
clicking around in a vendor dashboard.

- The repo is the source of truth; dashboard state should be reproducible
  from `render blueprint apply` (or equivalent). When the two disagree,
  the in-repo file wins on the next sync.
- Render specifics: `autoDeploy`, image URLs, env vars, health check
  paths, domains — all expressible in `render.yaml`. Set them there.
  Note that some toggles exist in the dashboard UI for Static Sites but
  are hidden (or missing entirely) for image-runtime services — declarative
  config is sometimes the *only* reliable way to set them.
- GitHub Actions secrets and repo settings: `gh secret set`, `gh repo edit`.
  Branch protection: `gh api -X PUT repos/.../branches/main/protection`.
- When a user reports doing something in a dashboard, take it as a signal
  to encode that change declaratively in the same PR.
