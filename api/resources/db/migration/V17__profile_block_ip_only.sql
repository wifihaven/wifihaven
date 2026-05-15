-- #424: per-profile toggle for blockIpOnly enforcement.
--
-- #353 landed router-side enforcement: dnsmasq populates a per-MAC
-- resolved_<mac>/resolved6_<mac> nftables set at DNS-resolve time, and the
-- forward chain drops any v4/v6 daddr not in that set. The BlockRules wire
-- field has shipped since #354. Until now PolicyService.computeBlockRules
-- hardcoded blockIpOnly=false because the profile had no column to read.
--
-- Default FALSE preserves existing behaviour on migration (no enforcement
-- change for any existing profile). Admins opt in per-profile via the
-- existing profile-update endpoint.
ALTER TABLE profiles
  ADD COLUMN block_ip_only BOOLEAN NOT NULL DEFAULT FALSE;
