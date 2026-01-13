#!/usr/bin/env bash
set -euo pipefail

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color
BOLD='\033[1m'

echo -e "${MAGENTA}${BOLD}"
echo "╔════════════════════════════════════════════╗"
echo "║    DFDB Performance & Benchmark Suite      ║"
echo "╚════════════════════════════════════════════╝"
echo -e "${NC}"

echo -e "${BLUE}JVM Configuration:${NC}"
echo "  - Heap: 4GB (Xmx/Xms)"
echo "  - GC: G1 Garbage Collector"
echo "  - Profiling: Debug Non-Safepoints enabled"
echo ""

echo -e "${YELLOW}Running performance tests...${NC}\n"

# Create output file with timestamp
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
OUTPUT_FILE="perf_test_results_${TIMESTAMP}.txt"

# Run tests and capture output
if clojure -X:perf-test cognitect.test-runner.api/test :dirs '["perf"]' 2>&1 | tee "$OUTPUT_FILE"; then
    EXIT_CODE=0
    echo -e "\n${GREEN}${BOLD}"
    echo "╔════════════════════════════════════════════╗"
    echo "║      ✓ ALL PERFORMANCE TESTS PASSED        ║"
    echo "╚════════════════════════════════════════════╝"
    echo -e "${NC}"
    echo -e "${BLUE}Results saved to: ${BOLD}${OUTPUT_FILE}${NC}"
else
    EXIT_CODE=$?
    echo -e "\n${RED}${BOLD}"
    echo "╔════════════════════════════════════════════╗"
    echo "║      ✗ PERFORMANCE TESTS FAILED            ║"
    echo "╚════════════════════════════════════════════╝"
    echo -e "${NC}"
    echo -e "${BLUE}Results saved to: ${BOLD}${OUTPUT_FILE}${NC}"
fi

# Show quick summary if criterium results are present
if grep -q "Execution time mean" "$OUTPUT_FILE" 2>/dev/null; then
    echo -e "\n${MAGENTA}${BOLD}Performance Summary:${NC}"
    grep -A 2 "Execution time mean" "$OUTPUT_FILE" | head -20
fi

exit $EXIT_CODE
