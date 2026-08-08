#!/bin/bash

set -e

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if k6 is installed
if ! command -v k6 &> /dev/null; then
    echo -e "${RED}Error: k6 is not installed. Please install k6 first.${NC}"
    echo "See https://k6.io/docs/getting-started/installation/ for installation instructions."
    exit 1
fi

# Get API base URL from environment or use default
API_BASE_URL=${API_BASE_URL:-"http://localhost:8080"}

echo -e "${YELLOW}Performance Test Suite${NC}"
echo "======================="
echo "API URL: $API_BASE_URL"
echo ""

# Check if API is reachable
echo -n "Checking API health... "
if curl -s "$API_BASE_URL/actuator/health" > /dev/null 2>&1; then
    echo -e "${GREEN}OK${NC}"
else
    echo -e "${RED}FAILED${NC}"
    echo "Make sure the API server is running at $API_BASE_URL"
    exit 1
fi

echo ""
export API_BASE_URL

# Run tests
TESTS=("load-test-render-endpoint.js" "load-test-database-queries.js" "load-test-spike.js")
RESULTS_DIR="results-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

for test in "${TESTS[@]}"; do
    if [ ! -f "$test" ]; then
        echo -e "${YELLOW}Skipping $test (file not found)${NC}"
        continue
    fi

    echo -e "${YELLOW}Running $test...${NC}"
    TEST_NAME=$(basename "$test" .js)

    if k6 run \
        --out "json=$RESULTS_DIR/${TEST_NAME}-results.json" \
        --out "html=$RESULTS_DIR/${TEST_NAME}-report.html" \
        "$test"; then
        echo -e "${GREEN}✓ $TEST_NAME completed${NC}"
    else
        echo -e "${RED}✗ $TEST_NAME failed${NC}"
    fi

    echo ""
done

echo -e "${YELLOW}Test Summary${NC}"
echo "============="
echo "Results directory: $RESULTS_DIR"
echo ""
echo "Generated files:"
ls -lh "$RESULTS_DIR"

echo ""
echo -e "${GREEN}All tests completed!${NC}"
echo ""
echo "View detailed results:"
for report in "$RESULTS_DIR"/*-report.html; do
    if [ -f "$report" ]; then
        echo "  - $report"
    fi
done
