#!/usr/bin/env bash

./gradlew clean
./gradlew pushImages -Penable-docker=true -Pbuild-mcp-tool-gateway=true
cd ../deploy-helm
helm upgrade --install cdc . --set-string rollme="$(uuidgen)"
cd ../mcp-tool-gateway