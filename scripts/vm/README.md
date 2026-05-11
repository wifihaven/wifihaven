# `scripts/vm/` — VM e2e harness

Tooling for booting an OpenWRT router (and eventually a client VM) under
QEMU so the full FamilyDNS data-plane — agent → API → dnsmasq/nftables —
can be exercised end-to-end on developer machines and in CI.

This directory currently owns the **router image build** (issue #150).
The VM bring-up (issue #144), client VM (#146), test orchestrator (#148),
and CI runner integration (#149) are tracked separately and land here as
they ship.

## Files

| File | Purpose |
| --- | --- |
| `versions.sh` | Single source of truth for the pinned OpenWRT release and Image Builder URL. Sourced by everything else. |
| `build-router-image.sh` | Builds an x86_64 OpenWRT image with the familydns-agent ipk pre-installed. Runs Image Builder inside Docker. |
| `uci-defaults/99-familydns` | First-boot defaults baked into the image at `/etc/uci-defaults/`. OpenWRT runs it once on first boot. |
| `.cache/` | Downloads, extracted Image Builder, staging, and the final image. Git-ignored. |

## Building the image

```bash
# Local dev: builds the ipk from your working tree, bakes it in.
scripts/vm/build-router-image.sh

# From a published release artifact:
IPK_SOURCE=release scripts/vm/build-router-image.sh

# From an explicit path (e.g. a CI-built ipk you downloaded):
IPK_SOURCE=path IPK_PATH=/abs/path/familydns_X.Y.Z-1_all.ipk \
    scripts/vm/build-router-image.sh
```

The output is `scripts/vm/.cache/openwrt-familydns.img` — uncompressed,
ready to feed to QEMU once `scripts/vm/router-up.sh` (issue #144) lands.

**Image size:** ~30–50 MB (combined-ext4, x86_64).

**Host requirements:** Docker daemon running. macOS works (Docker Desktop)
and Linux works. The Image Builder itself runs inside a pinned Debian
container, so no host toolchain is required.

## Source-of-truth for the ipk

The image bakes in the **same ipk artifact** that production routers
install via `opkg` (built by `openwrt/build-ipk.sh`, published by
`.github/workflows/openwrt-build.yml`). Both `IPK_SOURCE=local` and
`IPK_SOURCE=release` produce the same artifact format; VM e2e and the
real router install path therefore exercise the same bits. If you change
the package contents, change them in `openwrt/` — never patch the staged
copy here.

## First-boot config (`uci-defaults/99-familydns`)

OpenWRT runs everything in `/etc/uci-defaults/` exactly once on first
boot, then deletes the script. We use this to seed:

- `familydns.@familydns[0].api_url='http://10.0.2.2:8080'` — the QEMU
  user-mode address of the host. Lets you `qemu ... -netdev user` and have
  the agent talk to an API server running on your laptop without any
  bridge plumbing.
- `familydns.@familydns[0].lan_prefix='192.168.1.'` — matches OpenWRT's
  default LAN subnet.

The test orchestrator (#148) overrides both values over SSH once it knows
the host's LAN-bridge IP. The defaults are only there so a developer who
just boots the image and pokes at it sees something sensible.

### Extending the first-boot config

Add UCI commands to `uci-defaults/99-familydns`. To validate before
rebuilding the image, you can dry-run the script against a stock OpenWRT
VM: copy it to `/etc/uci-defaults/`, reboot, then `uci show familydns`.

The script also seeds an empty `/etc/dropbear/authorized_keys` — the
orchestrator drops its public key in via the QEMU console before SSHing.
This is **only safe for ephemeral VMs**. Do not flash this image to a
real router.

## Bumping the OpenWRT release

1. Edit `OPENWRT_VERSION` in `versions.sh`.
2. Delete `scripts/vm/.cache/` so the new Image Builder downloads cleanly.
3. Run `scripts/vm/build-router-image.sh` and confirm the build succeeds.
4. Run the VM e2e suite (#148) end-to-end against the new image.

The build script downloads the official `sha256sums` from the same
release directory and verifies the Image Builder tarball against it, so
there's no hash to hand-edit.

## CI

`.github/workflows/router-image-build.yml` runs this script on every push
to `main` and on PRs touching `openwrt/` or `scripts/vm/`. It publishes
the resulting image as a workflow artifact named
`openwrt-familydns-<openwrt-version>-<sha>` for the VM e2e suite to
consume.

## Acceptance test (issue #150)

1. `scripts/vm/build-router-image.sh` completes on a clean checkout.
2. The image boots in QEMU via `scripts/vm/router-up.sh --image familydns`
   (#144).
3. After boot, `opkg list-installed | grep familydns` shows the agent.
4. `logread | grep familydns` shows the agent starting up.
5. `uci show familydns` returns the pre-baked defaults.
