include $(TOPDIR)/rules.mk

PKG_NAME:=luci-app-wifihaven
PKG_VERSION:=0.1.0
PKG_RELEASE:=1

PKG_MAINTAINER:=WifiHaven <noreply@example.com>
PKG_LICENSE:=MIT

LUCI_TITLE:=LuCI support for WifiHaven
LUCI_DEPENDS:=+luci-base +luci-compat +wifihaven
LUCI_PKGARCH:=all

include $(INCLUDE_DIR)/package.mk

define Package/$(PKG_NAME)
  SECTION:=luci
  CATEGORY:=LuCI
  SUBMENU:=3. Applications
  TITLE:=$(LUCI_TITLE)
  DEPENDS:=$(LUCI_DEPENDS)
  PKGARCH:=$(LUCI_PKGARCH)
endef

define Package/$(PKG_NAME)/description
  Web UI (LuCI) for the WifiHaven router agent. Exposes cadence tunables
  from /etc/config/wifihaven and a read-only status overview.
endef

define Build/Prepare
endef

define Build/Configure
endef

define Build/Compile
endef

define Package/$(PKG_NAME)/install
	$(INSTALL_DIR) $(1)/usr/lib/lua/luci/controller
	$(INSTALL_DATA) ./luasrc/controller/wifihaven.lua $(1)/usr/lib/lua/luci/controller/

	$(INSTALL_DIR) $(1)/usr/lib/lua/luci/model/cbi/wifihaven
	$(INSTALL_DATA) ./luasrc/model/cbi/wifihaven/settings.lua $(1)/usr/lib/lua/luci/model/cbi/wifihaven/

	$(INSTALL_DIR) $(1)/usr/lib/lua/luci/view/wifihaven
	$(INSTALL_DATA) ./luasrc/view/wifihaven/status.htm $(1)/usr/lib/lua/luci/view/wifihaven/

	$(INSTALL_DIR) $(1)/usr/share/rpcd/acl.d
	$(INSTALL_DATA) ./root/usr/share/rpcd/acl.d/luci-app-wifihaven.json $(1)/usr/share/rpcd/acl.d/

	$(INSTALL_DIR) $(1)/usr/share/luci/menu.d
	$(INSTALL_DATA) ./root/usr/share/luci/menu.d/luci-app-wifihaven.json $(1)/usr/share/luci/menu.d/

	$(INSTALL_DIR) $(1)/usr/libexec/rpcd
	$(INSTALL_BIN) ./root/usr/libexec/rpcd/wifihaven $(1)/usr/libexec/rpcd/wifihaven
endef

$(eval $(call BuildPackage,$(PKG_NAME)))
