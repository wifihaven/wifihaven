#!/usr/bin/env bash
# Guardrail (#2843): `DeviceAssignment.assign` (api/src/db/DeviceAssignment.scala)
# is the ONLY code that writes `devices.profile_id`. It writes the device's
# assignment history (`device_profile_assignments`, V90) in the same
# transaction; any other writer moves a device between profiles with no record
# of when, which re-attributes its past usage to the new profile. Design:
# docs/design/shared-devices.md §5.3.
#
# This check scans every Scala file under api/src (not just the diff, so an
# existing bypass can never hide) for a SQL string that starts with
# `UPDATE devices` or `INSERT INTO devices` and mentions `profile_id` before
# the string literal ends. Only DeviceAssignment.scala may contain one.
#
# It is a text scan, so it cannot see a statement assembled from separate
# fragments (`fr"UPDATE devices SET" ++ ...`). The AssignmentInvariant test-pin
# (run after every feature test) covers that case at runtime.
#
# A statement that only READS profile_id (e.g. `UPDATE devices SET name=...
# WHERE profile_id=...`) also matches. Rewrite it to key on the device id, or
# route it through the primitive.
set -euo pipefail

ROOT="${1:-.}"
ALLOWED="api/src/db/DeviceAssignment.scala"

violations=0
while IFS= read -r -d '' f; do
  rel="${f#"${ROOT}"/}"
  [[ "${rel}" == "${ALLOWED}" ]] && continue
  # Slurp the file; print `line: statement` for each devices write whose text,
  # up to the closing quote of its string literal, names profile_id.
  hits="$(perl -0777 -ne '
    while (/((?:UPDATE\s+devices\b|INSERT\s+INTO\s+devices\b)[^"]*)/gi) {
      my ($stmt, $pos) = ($1, $-[1]);
      next unless $stmt =~ /\bprofile_id\b/i;
      my $line = 1 + (() = substr($_, 0, $pos) =~ /\n/g);
      (my $one = $stmt) =~ s/\s+/ /g;
      print "$line: $one\n";
    }
  ' "${f}")"
  if [[ -n "${hits}" ]]; then
    if [[ ${violations} -eq 0 ]]; then
      echo "ERROR: devices.profile_id is written outside DeviceAssignment.assign (#2843)."
      echo "Route the write through DeviceAssignment.assign (api/src/db/DeviceAssignment.scala),"
      echo "which records the assignment history in the same transaction."
      echo
    fi
    while IFS= read -r h; do
      echo "  ${rel}:${h}"
      violations=$((violations + 1))
    done <<< "${hits}"
  fi
done < <(find "${ROOT}/api/src" -name '*.scala' -print0)

if [[ ${violations} -gt 0 ]]; then
  exit 1
fi
echo "OK: devices.profile_id has one writer (DeviceAssignment.assign)."
