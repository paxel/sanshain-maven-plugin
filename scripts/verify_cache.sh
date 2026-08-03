#!/usr/bin/env bash
set -euo pipefail

URL=${1:-"http://localhost:3000"}
PASSWORD=${2:-"root_password"}

echo "Logging in to get token..."
TOKEN=$(curl -s -X POST "$URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"root\", \"password\": \"$PASSWORD\"}" | jq -r .token)

if [ "$TOKEN" == "null" ] || [ -z "$TOKEN" ]; then
    echo "Error: Login failed"
    exit 1
fi

export SANSHAIN_TOKEN=$TOKEN
export SANSHAIN_URL=$URL

# Create a temporary project for testing
TEST_DIR="target/cache-test-project"
rm -rf "$TEST_DIR"
mkdir -p "$TEST_DIR"
cd "$TEST_DIR"

echo "Creating sanshain.yaml..."
cat > sanshain.yaml <<EOF
serviceName: cache-test-service
sanshainUrl: $URL
provide:
  file: openapi.yaml
  apiType: openapi
requires:
  - serviceName: cache-test-service
    version: 1.0.0
    endpoints:
      - method: GET
        path: /test
EOF

echo "Creating openapi.yaml..."
cat > openapi.yaml <<EOF
openapi: 3.0.0
info:
  title: Cache Test
  version: 1.0.0
paths:
  /test:
    get:
      responses:
        '200':
          description: OK
EOF

echo ""
echo "--- Step 1: Initial Provide ---"
mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:provide -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN

echo ""
echo "--- Step 2: Second Provide (should skip) ---"
OUTPUT=$(mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:provide -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN)
echo "$OUTPUT" | grep "Spec unchanged" || (echo "FAILURE: Expected skip message not found"; exit 1)
echo "SUCCESS: Skip message found."

echo ""
echo "--- Step 3: Modified Provide (should upload) ---"
sed -i 's/description: OK/description: Updated OK/' openapi.yaml
OUTPUT=$(mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:provide -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN)
if echo "$OUTPUT" | grep -q "Spec unchanged"; then
    echo "FAILURE: Unexpected skip message found after modification"
    exit 1
fi
echo "SUCCESS: Modification detected and uploaded."

echo ""
echo "--- Step 4: Initial Require ---"
mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:require -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN

echo ""
echo "--- Step 5: Second Require (should skip with 304) ---"
OUTPUT=$(mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:require -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN)
echo "$OUTPUT" | grep "spec unchanged (304)" || (echo "FAILURE: Expected 304 skip message not found"; exit 1)
echo "SUCCESS: 304 skip message found."

echo ""
echo "--- Step 6: Require after service update (should download) ---"
echo "--- Modifying service ---"
# We modify the spec again on the server (via provide)
sed -i 's/description: Updated OK/description: Updated OK 2/' openapi.yaml
mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:provide -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN

echo "--- Running require ---"
OUTPUT=$(mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:require -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN)
if echo "$OUTPUT" | grep -q "spec unchanged (304)"; then
    echo "FAILURE: Unexpected 304 skip message found after server update"
    exit 1
fi
echo "SUCCESS: Server update detected and downloaded."

echo "--- Step 7: Bundle Require ---"
cat > sanshain.yaml <<EOF
serviceName: cache-test-service
sanshainUrl: $URL
provide:
  file: openapi.yaml
  apiType: openapi
requires:
  - serviceName: cache-test-service
    version: 1.0.0
    endpoints:
      - method: GET
        path: /test
      - method: GET
        path: /test2
EOF
echo "  /test2:" >> openapi.yaml
echo "    get:" >> openapi.yaml
echo "      responses:" >> openapi.yaml
echo "        '200':" >> openapi.yaml
echo "          description: OK" >> openapi.yaml

mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:provide -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN

echo "--- Initial Bundle Require ---"
mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:require -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN

echo "--- Second Bundle Require (should skip with 304) ---"
OUTPUT=$(mvn io.github.paxel.sanshain:sanshain-maven-plugin:2.0.0:require -DconfigFile=sanshain.yaml -Dsanshain.token=$TOKEN)
echo "$OUTPUT" | grep "spec unchanged (304)" || (echo "FAILURE: Expected 304 skip message for bundle not found"; exit 1)
echo "SUCCESS: 304 skip message for bundle found."

echo ""
echo "--- CACHE VERIFICATION COMPLETE ---"
