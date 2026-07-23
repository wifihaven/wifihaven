# shellcheck shell=sh
# Shared ar-container assembler for the .ipk builders (sourced, not executed).
#
# Single source (#1717 pattern, like depends-list.sh) for both build-ipk.sh and
# luci/build-ipk.sh so the archive layout can't drift between them. opkg on
# OpenWrt 21.02-era (GL.iNet stock 4.8.x) requires the ar members to be named
# EXACTLY debian-binary / control.tar.gz / data.tar.gz. GNU binutils `ar` (the
# Linux CI host's `ar`) writes SysV-style names WITH a trailing slash as its
# name terminator ("control.tar.gz/"), which that opkg's pkg_init_from_file
# rejects as "Malformed package file"; modern opkg (23.05+) and macOS BSD `ar`
# tolerate or avoid it, which is why it went unnoticed (#2363). Hand-assembling
# the container makes the member names byte-for-byte correct regardless of which
# `ar` variant the build host ships. ipk_ar_format_spec.test.sh pins the names.
#
# ar layout: 8-byte global header "!<arch>\n", then per member a 60-byte header
# (name[16] mtime[12] uid[6] gid[6] mode[8] size[10] magic[2]) followed by the
# data, padded to an even byte boundary with a newline.

# Start a fresh archive at $1 (writes the global header).
wh_ar_init() {  # $1 = archive path
    printf '!<arch>\n' > "$1"
}

# Append one member to archive $1 under the exact name $2 from source file $3.
wh_ar_append() {  # $1 = archive, $2 = member name (as opkg must see it), $3 = source file
    _ar="$1"; _name="$2"; _file="$3"
    _size=$(wc -c < "$_file")
    # Deterministic mtime/uid/gid (0), mode 100644, magic = 0x60 0x0a (`\n).
    printf '%-16s%-12d%-6d%-6d%-8s%-10d\140\n' \
        "$_name" 0 0 0 100644 "$_size" >> "$_ar"
    cat "$_file" >> "$_ar"
    [ $((_size % 2)) -ne 0 ] && printf '\n' >> "$_ar"
    return 0  # neutralise set -e on the even-size (false) branch above
}
