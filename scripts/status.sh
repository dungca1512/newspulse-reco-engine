#!/bin/bash
# NewsPulse - Check Status Script

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR/.."

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  NewsPulse Service Status             ${NC}"
echo -e "${GREEN}========================================${NC}"

# Function to check service
check_service() {
    local name=$1
    local url=$2
    
    if curl -s "$url" > /dev/null 2>&1; then
        echo -e "  $name: ${GREEN}RUNNING${NC}"
    else
        echo -e "  $name: ${RED}DOWN${NC}"
    fi
}

# Check infrastructure
echo -e "\n${YELLOW}Infrastructure:${NC}"
check_service "Kafka" "http://localhost:9092"
check_service "Elasticsearch" "http://localhost:9200"
check_service "Redis" "localhost:6379"
check_service "Kafka UI" "http://localhost:8088"

# Check application services
echo -e "\n${YELLOW}Application Services:${NC}"
check_service "API Server (8090)" "http://localhost:8090/actuator/health"
check_service "Embedding API (8000)" "http://localhost:8000/health"

# Check Kafka topics
echo -e "\n${YELLOW}Kafka Topics:${NC}"
if docker exec newspulse-kafka kafka-topics --list --bootstrap-server localhost:9092 2>/dev/null; then
    echo ""
else
    echo -e "  ${RED}Cannot connect to Kafka${NC}"
fi

# Check Elasticsearch indices
echo -e "\n${YELLOW}Elasticsearch Indices:${NC}"
curl -s "http://localhost:9200/_cat/indices?v" 2>/dev/null || echo -e "  ${RED}Cannot connect to Elasticsearch${NC}"

# Check data counts
echo -e "\n${YELLOW}Data Counts:${NC}"
articles=$(curl -s "http://localhost:9200/news_articles/_count" 2>/dev/null | grep -o '"count":[0-9]*' | cut -d: -f2)
trending=$(curl -s "http://localhost:9200/news_trending/_count" 2>/dev/null | grep -o '"count":[0-9]*' | cut -d: -f2)
echo "  - news_articles: ${articles:-0} documents"
echo "  - news_trending: ${trending:-0} documents"

# Check Kafka message counts
echo -e "\n${YELLOW}Kafka Message Counts:${NC}"
docker exec newspulse-kafka kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic news_raw 2>/dev/null | awk -F: '{sum+=$3} END {print "  - news_raw: " sum " messages"}' || echo "  - Cannot get counts"
docker exec newspulse-kafka kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic news_cleaned 2>/dev/null | awk -F: '{sum+=$3} END {print "  - news_cleaned: " sum " messages"}' || true
docker exec newspulse-kafka kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic news_embedding 2>/dev/null | awk -F: '{sum+=$3} END {print "  - news_embedding: " sum " messages"}' || true

echo -e "\n${GREEN}========================================${NC}"
