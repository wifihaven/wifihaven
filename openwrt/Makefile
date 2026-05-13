include $(TOPDIR)/rules.mk

PKG_NAME:=familydns
PKG_VERSION:=0.1.0
PKG_RELEASE:=1

PKG_MAINTAINER:=FamilyDNS <noreply@example.com>
PKG_LICENSE:=MIT

include $(INCLUDE_DIR)/package.mk

define Package/familydns
  SECTION:=net
  CATEGORY:=Network
  TITLE:=FamilyDNS router agent
  DEPENDS:=+lua +libuci-lua +luci-lib-jsonc +conntrack +curl
  PKGARCH:=all
endef

define Package/familydns/description
  Agent daemon that enforces per-device DNS filtering and time limits,
  accounts traffic, and reports connection events to the FamilyDNS API.
endef

define Build/Compile
endef

define Package/familydns/conffiles
/etc/config/familydns
endef

define Package/familydns/install
	$(INSTALL_DIR) $(1)/usr/sbin
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/familydns-agent $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/familydns-dns-tail $(1)/usr/sbin/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/familydns-update $(1)/usr/sbin/

	$(INSTALL_DIR) $(1)/usr/lib/lua/familydns
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/usr/lib/lua/familydns/*.lua $(1)/usr/lib/lua/familydns/

	$(INSTALL_DIR) $(1)/etc/init.d
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/etc/init.d/familydns $(1)/etc/init.d/
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/etc/init.d/familydns-boot $(1)/etc/init.d/

	$(INSTALL_DIR) $(1)/etc/config
	$(INSTALL_CONF) $(PKG_BUILD_DIR)/files/etc/config/familydns $(1)/etc/config/

	$(INSTALL_DIR) $(1)/usr/share/familydns
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/usr/share/familydns/boot.nft $(1)/usr/share/familydns/

	$(INSTALL_DIR) $(1)/www/familydns
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/www/familydns/block.html $(1)/www/familydns/
endef

define Package/familydns/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/familydns enable
# #308: enable the boot default-deny skeleton init script so every reboot
# loads it before fw4 brings forwarding up. Also load it now so the very
# first install (no reboot) is protected during the gap until the agent's
# first policy.apply().
/etc/init.d/familydns-boot enable
/etc/init.d/familydns-boot start 2>/dev/null || true
# Install cron entry for the auto-updater (daily, 04:00 router-local).
# Replace any existing familydns-update entry so upgrades migrate the
# cadence — older packages installed it at "0 */6 * * *".
mkdir -p /etc/crontabs
[ -f /etc/crontabs/root ] && sed -i '/familydns-update/d' /etc/crontabs/root
echo '0 4 * * * /usr/sbin/familydns-update' >> /etc/crontabs/root
/etc/init.d/cron enable 2>/dev/null || true
/etc/init.d/cron restart 2>/dev/null || true
endef

$(eval $(call BuildPackage,familydns))
