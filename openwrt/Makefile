include $(TOPDIR)/rules.mk

PKG_NAME:=wifihaven
PKG_VERSION:=0.1.0
PKG_RELEASE:=1

PKG_MAINTAINER:=WifiHaven <noreply@example.com>
PKG_LICENSE:=MIT

include $(INCLUDE_DIR)/package.mk

define Package/wifihaven
  SECTION:=net
  CATEGORY:=Network
  TITLE:=WifiHaven router agent
  # NFLOG deps: the forward-chain drop rules carry an NFLOG `log group`
  # statement (#1122) on both v4 and v6 rules in the inet table. These are not
  # in the default image, and the agent's atomic `nft -f` rejects the whole
  # table without them — silently dropping all enforcement, including the
  # block-page DNAT (#1144). kmod-nfnetlink-log is the NFLOG backend;
  # kmod-nf-log{,6} provide the netfilter log framework for each family.
  # #383: libustream-mbedtls supplies the TLS backend uhttpd uses for the
  # listen_https block-page sibling on :8443; openssl-util provides
  # /usr/bin/openssl for the self-signed cert generator that ships at
  # /usr/lib/wifihaven/generate-block-page-cert.sh.
  # #573: tcpdump-mini provides libpcap-userspace capture for the SNI sidecar
  # (wifihaven-sni-tail). It parses the TLS ClientHello off the LAN bridge to
  # recover hostname attribution when the client used DoH/DoT or a hardcoded
  # IP — the same shared dns_cache feeds eb_/ea_/bl_ enforcement, so this is
  # an additive, observability-style dependency.
  # #1688: kmod-nf-conntrack6 loads the v6 conntrack hooks (via nf_defrag_ipv6
  # on modern kernels). Without it `conntrack -E -e NEW` sees 0 v6 flow events,
  # so the agent never emits connection_events for v6 destinations and the
  # Gate 2 v6 attribution suite (scripts/e2e/scenarios_fake/test_v6_*.py) fails.
  # #1651: lua-openssl provides HMAC-SHA256 + AES-128-ECB + AES-128-GCM so the
  # sni sidecar can extract the SNI from QUIC v1 Initial packets too (UDP/443),
  # closing the HTTP/3 leg of the #573 attribution gap. RFC 9001 §5.2 keys are
  # HKDF-derived from the destination connection ID + v1 salt; the payload is
  # AEAD-encrypted with AES-128-GCM and the header bits are protected with an
  # AES-128-ECB mask. ~464 KiB installed — additive, attribution-only.
  # #2036: cqueues + luaossl are the wifihaven-ws sidecar's runtime deps.
  # Shipping them in DEPENDS makes enabling ws a pure
  # `uci set wifihaven.ws.enabled=1` + restart with no manual `apk add` — the
  # prod-cutover gate for #1023. Marginal cost is ~0.5 MB (cqueues 328 KiB +
  # luaossl 205 KiB) and ZERO new transitive packages: their dep closure
  # (libc/liblua5.1.5/libopenssl3) is already pulled by +lua / +lua-openssl /
  # +openssl-util. luaossl (daurnimator) provides the `openssl.ssl.context`
  # submodule API the sidecar needs; it is DISTINCT from lua-openssl (zhaozg,
  # monolithic `openssl.so`) above — both are required, and the #1949
  # coexistence (quic.resolve_openssl() force-loads lua-openssl's .so even
  # though luaossl shadows the bare require("openssl")) still holds.
  # #2078: usign verifies the release signature on every downloaded update
  # asset before wifihaven-update installs it (was previously unverified,
  # apk add --allow-untrusted / opkg install --force-reinstall). usign is
  # OpenWrt's own signing tool (used for sysupgrade image verification) and
  # was confirmed present at /usr/bin/usign on a live apk-based router
  # (OpenWrt 25.12.3, openwrt.lan) without this package installed — but that
  # was only checked on the apk target, not the ipk/23.05 one, so it's pinned
  # here as an explicit DEPENDS regardless of what any given base image ships,
  # closing that gap either way.
  # #2153: ca-bundle materializes /etc/ssl/certs/ca-certificates.crt, the system
  # CA trust store the wifihaven-ws sidecar loads before a wss:// handshake.
  # luaossl's context.new() ships an EMPTY store, so without this bundle
  # VERIFY_PEER rejects every real cert (the prod-cutover blocker #1023 hit). It
  # was present on the live router but not guaranteed by DEPENDS; pin it so
  # enabling ws never depends on it arriving transitively.
  DEPENDS:=+lua +libuci-lua +luci-lib-jsonc +conntrack +curl +uhttpd-mod-lua +kmod-nf-log +kmod-nf-log6 +kmod-nf-conntrack6 +libustream-mbedtls +openssl-util +tcpdump-mini +lua-openssl +cqueues +luaossl +usign +ca-bundle
  PKGARCH:=all
endef

define Package/wifihaven/description
  Agent daemon that enforces per-device DNS filtering and time limits,
  accounts traffic, and reports connection events to the WifiHaven API.
endef

define Build/Compile
endef

define Package/wifihaven/conffiles
/etc/config/wifihaven
endef

# NOTE: this install stanza is not exercised by the actual release pipeline —
# build-ipk.sh / build-apk.sh (invoked from build-release-artifacts.yml) build
# every shipped package without the full OpenWrt SDK, staging openwrt/files/
# wholesale instead of listing files here (no CI job builds via this
# Makefile's Package/wifihaven/install; grepped .github/workflows/ for any SDK
# feeds usage of it, found none). New files under files/ (e.g. #2078's
# etc/wifihaven/keys/release.pub) are NOT added here for that reason — if this
# stanza is ever wired back into a real SDK/feeds build, audit it against the
# current files/ tree first.
define Package/wifihaven/install
	$(INSTALL_DIR) $(1)/usr/sbin
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-agent $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-dns-tail $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-nflog-tail $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-sni-tail $(1)/usr/sbin/
	# #1848: websocket transport sidecar. Default-off (UCI wifihaven.ws.enabled);
	# the init script only starts it when enabled. Its runtime deps cqueues +
	# luaossl are shipped as hard DEPENDS (#2036), so enabling ws is purely
	# `uci set wifihaven.ws.enabled=1` + restart with no manual `apk add` (see
	# docs/design/websocket-transport.md / the ws UCI section).
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-ws $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-update $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-rotate-dnsmasq-log $(1)/usr/sbin/
	# #2381 enforcement escape-hatch CLI helpers (wifihaven-disable / -enable).
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-disable $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-enable $(1)/usr/sbin/

	$(INSTALL_DIR) $(1)/usr/lib/lua/wifihaven
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/usr/lib/lua/wifihaven/*.lua $(1)/usr/lib/lua/wifihaven/

	$(INSTALL_DIR) $(1)/etc/init.d
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/etc/init.d/wifihaven $(1)/etc/init.d/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/etc/init.d/wifihaven-boot $(1)/etc/init.d/

	$(INSTALL_DIR) $(1)/etc/config
	$(INSTALL_CONF) $(PKG_BUILD_DIR)/files/etc/config/wifihaven $(1)/etc/config/

	$(INSTALL_DIR) $(1)/usr/share/wifihaven
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/usr/share/wifihaven/boot.nft $(1)/usr/share/wifihaven/

	$(INSTALL_DIR) $(1)/www/wifihaven
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/www/wifihaven/handler.lua $(1)/www/wifihaven/

	$(INSTALL_DIR) $(1)/etc/sysctl.d
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/etc/sysctl.d/99-wifihaven.conf $(1)/etc/sysctl.d/

	$(INSTALL_DIR) $(1)/usr/lib/wifihaven
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/lib/wifihaven/setup-uhttpd-block-page.sh $(1)/usr/lib/wifihaven/
	# #383: cert generator for the TLS block-page listener (CN=block.wifihaven.local,
	# self-signed, RSA-2048). Idempotent; invoked from setup-uhttpd-block-page.sh.
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/lib/wifihaven/generate-block-page-cert.sh $(1)/usr/lib/wifihaven/
	# #771: bake PKG_VERSION into a file the lua agent reads at startup so it
	# can report itself to the API on every checkin (X-WifiHaven-Agent-Version).
	echo $(PKG_VERSION) > $(1)/usr/lib/wifihaven/VERSION

	$(INSTALL_DIR) $(1)/etc/uci-defaults
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/etc/uci-defaults/95-wifihaven-uhttpd $(1)/etc/uci-defaults/
	# #2381: ensure the enforcement-escape-hatch `settings` UCI section exists.
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/etc/uci-defaults/96-wifihaven-settings $(1)/etc/uci-defaults/
endef

define Package/wifihaven/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0

/etc/init.d/wifihaven enable
# #308: enable the boot default-deny skeleton init script so every reboot
# loads it before fw4 brings forwarding up. Also load it now so the very
# first install (no reboot) is protected during the gap until the agent's
# first policy.apply().
/etc/init.d/wifihaven-boot enable
/etc/init.d/wifihaven-boot start 2>/dev/null || true

# #542: uhttpd block-page wire-up runs from /etc/uci-defaults/95-wifihaven-uhttpd
# at first boot — postinst runs during offline-install before procd/uhttpd
# are up, so a uci-defaults stub is the right idiom here. Nothing for
# postinst to do for the block-page handler.
# Install cron entries. Replace any existing wifihaven entries so upgrades
# migrate the cadence. Same canonical block as build-ipk.sh and build-apk.sh;
# the crontab lines are pinned byte-for-byte across all copies by
# openwrt/test/install_spec.sh (#1532).
mkdir -p /etc/crontabs
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-update/d' /etc/crontabs/root
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-rotate-dnsmasq-log/d' /etc/crontabs/root
echo '0 * * * * /usr/sbin/wifihaven-update --jitter' >> /etc/crontabs/root
echo '*/10 * * * * /usr/sbin/wifihaven-rotate-dnsmasq-log' >> /etc/crontabs/root
/etc/init.d/cron enable 2>/dev/null || true
/etc/init.d/cron restart 2>/dev/null || true
endef

$(eval $(call BuildPackage,wifihaven))
