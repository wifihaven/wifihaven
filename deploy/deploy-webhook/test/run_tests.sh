#!/bin/sh
# Run the wifihaven deploy-webhook receiver unit tests.
# Requires: pip install pytest
set -e
cd "$(dirname "$0")/.."
python3 -m pytest test/ "$@"
