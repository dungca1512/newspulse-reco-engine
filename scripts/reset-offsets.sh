#!/bin/bash
# NewsPulse - Reset Kafka Consumer Offsets
# Use this to reprocess messages from the beginning

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR/.."

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}  Reset Kafka Consumer Offsets         ${NC}"
echo -e "${YELLOW}========================================${NC}"

echo -e "\n${RED}WARNING: This will stop consumers and reset offsets to reprocess all messages!${NC}"
read -p "Continue? [y/N] " -n 1 -r
echo

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Cancelled."
    exit 0
fi

# Stop consumers first
echo -e "\n${YELLOW}Stopping consumers...${NC}"
pkill -f "src.kafka_consumer" 2>/dev/null
pkill -f "search-indexer" 2>/dev/null
sleep 3

# Reset embedding-consumer offset
echo -e "\n${YELLOW}Resetting embedding-consumer offset...${NC}"
docker exec newspulse-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group embedding-consumer \
    --reset-offsets \
    --to-earliest \
    --topic news_cleaned \
    --execute

# Reset search-indexer offset
echo -e "\n${YELLOW}Resetting search-indexer offset...${NC}"
docker exec newspulse-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group search-indexer \
    --reset-offsets \
    --to-earliest \
    --topic news_embedding \
    --execute

echo -e "\n${GREEN}Offsets reset! Now restart the consumers:${NC}"
echo "  cd embedding-service-python && python -m src.kafka_consumer"
echo "  cd search-indexer-java && java -jar target/search-indexer-*.jar"
