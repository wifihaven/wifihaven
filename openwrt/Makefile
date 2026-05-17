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
endef

define Package/wifihaven/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0

# One-shot migration from pre-rename familydns config (issue #357).
if [ -f /etc/config/familydns ] && [ ! -s /etc/config/wifihaven ]; then
    # Copy old UCI section name to the new section name as we move it.
    sed 's/familydns/wifihaven/g' /etc/config/familydns > /etc/config/wifihaven
    rm -f /etc/config/familydns
fi
if [ -d /var/lib/familydns ] && [ ! -d /var/lib/wifihaven ]; then
    mv /var/lib/familydns /var/lib/wifihaven
fi
# Stop and remove old service scripts if still present from a pre-rename install.
if [ -x /etc/init.d/familydns ]; then
    /etc/init.d/familydns stop 2>/dev/null || true
    /etc/init.d/familydns disable 2>/dev/null || true
    rm -f /etc/init.d/familydns /etc/init.d/familydns-boot
fi
# Drop the stale nftables tables created by the old daemon. The new agent
# will create `inet wifihaven` / `inet wifihaven_boot` on its first policy
# render; leaving the old tables around is harmless but confusing.
nft delete table inet familydns 2>/dev/null || true
nft delete table inet familydns_boot 2>/dev/null || true

/etc/init.d/wifihaven enable
# #308: enable the boot default-deny skeleton init script so every reboot
# loads it before fw4 brings forwarding up. Also load it now so the very
# first install (no reboot) is protected during the gap until the agent's
# first policy.apply().
/etc/init.d/wifihaven-boot enable
/etc/init.d/wifihaven-boot start 2>/dev/null || true
# Install cron entry for the auto-updater (daily, 04:00 router-local).
# Replace any existing wifihaven-update entry so upgrades migrate the
# cadence — older packages installed it at "0 */6 * * *".
mkdir -p /etc/crontabs
[ -f /etc/crontabs/root ] && sed -i '/wifihaven-update/d' /etc/crontabs/root
echo '0 4 * * * /usr/sbin/wifihaven-update' >> /etc/crontabs/root
/etc/init.d/cron enable 2>/dev/null || true
/etc/init.d/cron restart 2>/dev/null || true
endef

$(eval $(call BuildPackage,wifihaven))
