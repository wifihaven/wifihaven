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
  DEPENDS:=+lua +luci-lib-jsonc +conntrack-tools +curl
  PKGARCH:=all
endef

define Package/familydns/description
  Agent daemon that enforces per-device DNS filtering and time limits,
  accounts traffic, and reports connection events to the FamilyDNS API.
endef

define Build/Compile
endef

define Package/familydns/install
	$(INSTALL_DIR) $(1)/usr/sbin
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/usr/sbin/familydns-agent $(1)/usr/sbin/

	$(INSTALL_DIR) $(1)/usr/lib/lua/familydns
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/usr/lib/lua/familydns/*.lua $(1)/usr/lib/lua/familydns/

	$(INSTALL_DIR) $(1)/etc/init.d
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/files/etc/init.d/familydns $(1)/etc/init.d/

	$(INSTALL_DIR) $(1)/etc/config
	$(INSTALL_CONF) $(PKG_BUILD_DIR)/files/etc/config/familydns $(1)/etc/config/

	$(INSTALL_DIR) $(1)/www/familydns
	$(INSTALL_DATA) $(PKG_BUILD_DIR)/files/www/familydns/block.html $(1)/www/familydns/
endef

define Package/familydns/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/familydns enable
endef

$(eval $(call BuildPackage,familydns))
