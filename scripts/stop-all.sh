#!/bin/bash
# NewsPulse Recommendation Engine - Stop Script
# This script stops all running services

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR/.."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}Stopping all NewsPulse services...${NC}"

# Function to stop a service by PID file
stop_service() {
    local name=$1
    local pid_file=$2
    
    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        if ps -p $pid > /dev/null 2>&1; then
            kill $pid 2>/dev/null
            echo -e "  - $name (PID: $pid) ${GREEN}stopped${NC}"
        else
            echo -e "  - $name ${YELLOW}not running${NC}"
        fi
        rm -f "$pid_file"
    else
        echo -e "  - $name ${YELLOW}no PID file${NC}"
    fi
}

# Stop all services
stop_service "API Server" "pids/api-springboot.pid"
stop_service "Embedding API" "pids/embedding-api.pid"
stop_service "Embedding Consumer" "pids/embedding-consumer.pid"
stop_service "ETL Spark" "pids/etl-spark.pid"
stop_service "Search Indexer" "pids/search-indexer.pid"
stop_service "Crawler" "pids/crawler.pid"

# Kill any remaining Java/Python processes for safety
echo -e "\n${YELLOW}Cleaning up any remaining processes...${NC}"
pkill -f "spring-boot:run" 2>/dev/null || true
pkill -f "src.kafka_consumer" 2>/dev/null || true
pkill -f "search-indexer" 2>/dev/null || true

echo -e "\n${YELLOW}Stopping Docker containers?${NC}"
read -p "Stop Docker containers (Kafka, Elasticsearch, Redis)? [y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    docker compose down
    echo -e "${GREEN}Docker containers stopped${NC}"
else
    echo "Docker containers kept running"
fi

echo -e "\n${GREEN}All services stopped!${NC}"
