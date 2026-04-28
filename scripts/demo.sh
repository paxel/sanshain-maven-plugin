#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==========================================================================="
echo "  Sanshain Maven Plugin Demo"
echo "==========================================================================="

echo "1. Setting up demo project..."
"$SCRIPT_DIR/setup-demo.sh"

echo "2. Obtaining Authentication Token..."
# We use the initial admin password to get a real session token
TOKEN=$(curl -s -X POST http://localhost:3000/auth/login -H "Content-Type: application/json" -d '{"username": "root", "password": "root_password"}' | jq -r .token)
if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
    echo "Error: Failed to obtain token. Check if Sanshain Service is running on port 3000."
    exit 1
fi
export SANSHAIN_TOKEN="$TOKEN"
echo "Token obtained."

echo ""
echo "3. Running provide goal..."
echo "This will upload OpenAPI, AsyncAPI and Proto specs to Sanshain."
"$SCRIPT_DIR/provide.sh"

echo ""
echo "4. Running require goal..."
echo "This will download required snippets from Sanshain."
# Note: This might fail if user-service doesn't exist yet in the local Sanshain instance
"$SCRIPT_DIR/require.sh" || echo "Note: require failed (likely because user-service doesn't exist yet). This is expected if the service is empty."

echo ""
echo "==========================================================================="
echo "  Demo finished!"
echo "  Check demo-project/target/generated-sources/sanshain for downloaded specs."
echo "==========================================================================="
