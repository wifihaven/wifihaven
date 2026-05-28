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
  DEPENDS:=+lua +libuci-lua +luci-lib-jsonc +conntrack +curl +uhttpd-mod-lua
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

define Package/wifihaven/install
	$(INSTALL_DIR) $(1)/usr/sbin
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-agent $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-dns-tail $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-update $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/wifihaven-rotate-dnsmasq-log $(1)/usr/sbin/

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
	# #771: bake PKG_VERSION into a file the lua agent reads at startup so it
	# can report itself to the API on every checkin (X-WifiHaven-Agent-Version).
	echo $(PKG_VERSION) > $(1)/usr/lib/wifihaven/VERSION

	$(INSTALL_DIR) $(1)/etc/uci-defaults
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/etc/uci-defaults/95-wifihaven-uhttpd $(1)/etc/uci-defaults/
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
# migrate the cadence. Kept in sync with build-ipk.sh, build-apk.sh.
mkdir -p /etc/crontabs
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-update/d' /etc/crontabs/root
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-rotate-dnsmasq-log/d' /etc/crontabs/root
echo '0 4 * * * /usr/sbin/wifihaven-update' >> /etc/crontabs/root
echo '*/10 * * * * /usr/sbin/wifihaven-rotate-dnsmasq-log' >> /etc/crontabs/root
/etc/init.d/cron enable 2>/dev/null || true
/etc/init.d/cron restart 2>/dev/null || true
endef

$(eval $(call BuildPackage,wifihaven))
