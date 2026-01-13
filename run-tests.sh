#!/usr/bin/env bash
set -euo pipefail

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color
BOLD='\033[1m'

echo -e "${BLUE}${BOLD}"
echo "╔════════════════════════════════════════════╗"
echo "║        DFDB Unit Test Suite                ║"
echo "╚════════════════════════════════════════════╝"
echo -e "${NC}"

echo -e "${YELLOW}Running unit tests...${NC}\n"

# Run tests and capture output
if clojure -X:unit-test cognitect.test-runner.api/test :dirs '["test"]'; then
    EXIT_CODE=0
    echo -e "\n${GREEN}${BOLD}"
    echo "╔════════════════════════════════════════════╗"
    echo "║           ✓ ALL TESTS PASSED               ║"
    echo "╚════════════════════════════════════════════╝"
    echo -e "${NC}"
else
    EXIT_CODE=$?
    echo -e "\n${RED}${BOLD}"
    echo "╔════════════════════════════════════════════╗"
    echo "║           ✗ TESTS FAILED                   ║"
    echo "╚════════════════════════════════════════════╝"
    echo -e "${NC}"
fi

exit $EXIT_CODE
