#!/usr/bin/env bash
set -euo pipefail

# Configuration
PLUGIN="io.github.paxel.sanshain:sanshain-maven-plugin:1.9.0"
SETTINGS_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/settings-example.xml"
DEMO_DIR="demo-project"

if [ ! -d "$DEMO_DIR" ]; then
    echo "Error: $DEMO_DIR not found. Run setup-demo.sh first."
    exit 1
fi

echo "Running sanshain:require for $DEMO_DIR..."
cd "$DEMO_DIR"

# Demonstrate using environment variables for sensitive data
export SANSHAIN_TOKEN="${SANSHAIN_TOKEN:-root_password}"

mvn $PLUGIN:require \
    -s "$SETTINGS_FILE" \
    -Dsanshain.strict=true \
    -X
