-- V30__apps_icon_type.sql
-- #768: the SPA needs to know how to render the `apps.icon` value (emoji
-- character, remote URL, or inline base64 PNG). We store the icon as opaque
-- TEXT so the column itself doesn't need to change shape; `icon_type` is the
-- discriminator. Existing seeded rows use emoji icons, so default to 'emoji'.
ALTER TABLE apps
  ADD COLUMN icon_type TEXT NOT NULL DEFAULT 'emoji'
    CHECK (icon_type IN ('emoji', 'url', 'png_base64'));
