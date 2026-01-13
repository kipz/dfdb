#!/bin/bash
# Run generative stress tests for dfdb query and differential dataflow engine

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}================================================${NC}"
echo -e "${YELLOW}Running Generative Stress Tests${NC}"
echo -e "${YELLOW}================================================${NC}"
echo ""

# Run the tests
clojure -X:unit-test cognitect.test-runner.api/test \
  :dirs '["test"]' \
  :patterns '["dfdb.generative-stress-test"]'

TEST_EXIT_CODE=$?

echo ""
echo -e "${YELLOW}================================================${NC}"

if [ $TEST_EXIT_CODE -eq 0 ]; then
  echo -e "${GREEN}✓ All generative tests passed!${NC}"
else
  echo -e "${RED}✗ Some generative tests failed${NC}"
fi

echo -e "${YELLOW}================================================${NC}"

exit $TEST_EXIT_CODE
