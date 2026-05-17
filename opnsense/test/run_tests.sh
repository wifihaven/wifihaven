#!/bin/sh
# Run the wifihaven OPNsense agent unit tests.
# Requires: pip install pytest
set -e
cd "$(dirname "$0")/.."
python3 -m pytest test/ "$@"
