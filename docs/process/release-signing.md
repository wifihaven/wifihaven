# Release package signing (#2078)

Every router release package (`wifihaven_*.ipk/.apk`, `luci-app-wifihaven_*.ipk/.apk`)
is signed with [usign](https://github.com/openwrt/usign) (OpenWrt's own ed25519
signing tool — it's how sysupgrade images are verified). Confirmed present at
`/usr/bin/usign` on a live apk-based router (OpenWrt 25.12.3, `openwrt.lan`)
without the `wifihaven` package installed; not separately checked on the
ipk/23.05 target, so it's shipped as an explicit `wifihaven` package `DEPENDS`
(`openwrt/Makefile`) rather than assumed present on every target.
`wifihaven-update` fetches the `.sig` sidecar published
alongside each asset and verifies it against the baked-in public key
(`/etc/wifihaven/keys/release.pub`, shipped in the `wifihaven` package) before
calling `apk add` / `opkg install`. Any failure — `usign` missing, the `.sig`
failing to download, or a bad signature — refuses the install (fail closed).

`apk add` keeps `--allow-untrusted`: apk's own trust store doesn't know this
key (the packages aren't built with apk's native ADB signing format), so the
flag is still required for apk to accept a loose file. The usign check ahead
of it is what actually gates provenance now — closing the #2078 gap (a
compromised release channel could previously install anything as root on
every router within the hourly auto-update cycle, with zero verification).

## Key material

- **Private key**: stored as the GitHub Actions repository secret
  `WIFIHAVEN_RELEASE_SIGNING_KEY` (raw usign secret-key file contents; usign
  secret keys are unencrypted — there is no passphrase to manage). Only
  `build-release-artifacts.yml`'s signing step reads it, and `master-router.yml`
  passes it through via `secrets: inherit` on the `build-release-artifacts` job.
- **Public key**: committed at
  [`openwrt/files/etc/wifihaven/keys/release.pub`](../../openwrt/files/etc/wifihaven/keys/release.pub),
  which every ipk/apk build copies into the package's `files/` tree (both
  `build-ipk.sh` and `build-apk.sh` stage the whole `openwrt/files/` directory
  verbatim). Fingerprint: `7882602646b7ae65`.

## Rotating the key

1. Generate a new keypair (on any machine with `usign`, e.g. the openwrt.lan
   test router, which ships it):
   ```sh
   usign -G -c "wifihaven release signing key" -s release.sec -p release.pub
   ```
2. Commit the new `release.pub` to `openwrt/files/etc/wifihaven/keys/release.pub`.
   Routers only trust a NEW key once they've upgraded to a package that ships
   it — so a rotation needs one transition release signed by the OLD key that
   ships the NEW pubkey, before the signing secret is swapped.
3. `gh secret set WIFIHAVEN_RELEASE_SIGNING_KEY --repo wifihaven/wifihaven < release.sec`,
   then delete the local `.sec` file.
4. Delete the old pubkey file only after the fleet has had time to pick up
   the transition release (the hourly auto-update cadence + the #1414 jitter
   window means "at least a few days" for a comfortable margin).

## Bootstrap gap (known, one-time)

The router version that FIRST ships `wifihaven-update`'s verification logic
is, by definition, installed by the OLD (pre-#2078) unverified update script —
there's no verifier on the router yet to check it. Every update AFTER that one
is verified. This is the standard secure-boot bootstrapping trade-off and is
not otherwise closable without a separate out-of-band trust bootstrap (e.g.
manual reflash), which is out of scope here.

## Sequencing note for operators

CI signing (`build-release-artifacts.yml`) soft-fails (warns, does not block
the build) if `WIFIHAVEN_RELEASE_SIGNING_KEY` isn't set, so Router CD isn't
bricked while the secret is being provisioned. But once a router build ships
with signature verification, it refuses to install ANY unsigned package — so
the secret must be set, and at least one signed release published, before
relying on auto-update to roll out that router build itself.
