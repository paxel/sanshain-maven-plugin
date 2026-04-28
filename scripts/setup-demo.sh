#!/usr/bin/env bash
set -euo pipefail

DEMO_DIR="demo-project"
mkdir -p "$DEMO_DIR"
cd "$DEMO_DIR"

echo "Creating dummy pom.xml..."
cat > pom.xml <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>demo-service</artifactId>
    <version>1.0.0</version>
</project>
EOF

echo "Creating sanshain.yaml..."
cat > sanshain.yaml <<EOF
sanshainUrl: "http://localhost:3000"
serviceName: "demo-service"
provides:
  - file: "specs/openapi.yaml"
    apiType: "openapi"
  - file: "specs/asyncapi.yaml"
    apiType: "asyncapi"
  - file: "specs/service.proto"
    apiType: "proto"
requires:
  - serviceName: "demo-service"
    apiType: "openapi"
    endpoints:
      - method: "GET"
        path: "/hello"
EOF

echo "Creating example specifications..."
mkdir -p specs
cat > specs/openapi.yaml <<EOF
openapi: 3.0.0
info:
  title: Demo Service
  version: 1.0.0
paths:
  /hello:
    get:
      responses:
        '200':
          description: OK
EOF

cat > specs/asyncapi.yaml <<EOF
asyncapi: 2.6.0
info:
  title: Demo Service Events
  version: 1.0.0
channels:
  hello:
    publish:
      message:
        payload:
          type: object
EOF

cat > specs/service.proto <<EOF
syntax = "proto3";
package demo;
service HelloService {
  rpc SayHello (HelloRequest) returns (HelloResponse);
}
message HelloRequest { string name = 1; }
message HelloResponse { string message = 1; }
EOF

echo "Demo project set up in $DEMO_DIR"
