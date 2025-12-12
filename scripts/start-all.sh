#!/bin/bash
# NewsPulse Recommendation Engine - Startup Script
# This script starts all services in the correct order

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  NewsPulse Recommendation Engine      ${NC}"
echo -e "${GREEN}========================================${NC}"

# Function to check if a port is in use
check_port() {
    lsof -i :$1 > /dev/null 2>&1
}

# Function to wait for a service to be ready
wait_for_service() {
    local host=$1
    local port=$2
    local service=$3
    local max_attempts=30
    local attempt=1
    
    echo -n "Waiting for $service..."
    while ! nc -z $host $port 2>/dev/null; do
        if [ $attempt -ge $max_attempts ]; then
            echo -e " ${RED}FAILED${NC}"
            return 1
        fi
        echo -n "."
        sleep 2
        ((attempt++))
    done
    echo -e " ${GREEN}READY${NC}"
}

# ========================================
# Step 1: Start Infrastructure (Docker)
# ========================================
echo -e "\n${YELLOW}[1/7] Starting Infrastructure (Docker)...${NC}"
docker compose up -d

wait_for_service localhost 9092 "Kafka"
wait_for_service localhost 9200 "Elasticsearch"
wait_for_service localhost 6379 "Redis"

# ========================================
# Step 2: Create Elasticsearch Indices
# ========================================
echo -e "\n${YELLOW}[2/7] Creating Elasticsearch Indices...${NC}"

# Create news_articles index if not exists
curl -s -X PUT "http://localhost:9200/news_articles" -H "Content-Type: application/json" -d '{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "url": { "type": "keyword" },
      "title": { "type": "text" },
      "description": { "type": "text" },
      "content": { "type": "text" },
      "author": { "type": "keyword" },
      "source": { "type": "keyword" },
      "category": { "type": "keyword" },
      "tags": { "type": "keyword" },
      "imageUrl": { "type": "keyword" },
      "publishTime": { "type": "date" },
      "crawlTime": { "type": "date" },
      "language": { "type": "keyword" },
      "wordCount": { "type": "integer" },
      "clusterId": { "type": "keyword" },
      "trendingScore": { "type": "float" },
      "embedding": { "type": "dense_vector", "dims": 768, "index": true, "similarity": "cosine" }
    }
  }
}' 2>/dev/null || true
echo "  - news_articles index created/exists"

# Create news_trending index if not exists
curl -s -X PUT "http://localhost:9200/news_trending" -H "Content-Type: application/json" -d '{
  "mappings": {
    "properties": {
      "cluster_id": { "type": "integer" },
      "title": { "type": "text" },
      "generated_label": { "type": "text" },
      "article_count": { "type": "integer" },
      "source_count": { "type": "integer" },
      "sources": { "type": "keyword" },
      "trending_score": { "type": "float" },
      "rank": { "type": "integer" },
      "category": { "type": "keyword" },
      "image_url": { "type": "keyword" },
      "earliest_publish": { "type": "date" },
      "latest_publish": { "type": "date" },
      "is_spike": { "type": "boolean" },
      "velocity": { "type": "float" }
    }
  }
}' 2>/dev/null || true
echo "  - news_trending index created/exists"

# ========================================
# Step 3: Start API Server (Spring Boot)
# ========================================
echo -e "\n${YELLOW}[3/7] Starting API Server (Spring Boot)...${NC}"
cd api-springboot
nohup mvn spring-boot:run > ../logs/api-springboot.log 2>&1 &
echo $! > ../pids/api-springboot.pid
cd ..
echo "  - API Server starting on port 8090 (PID: $(cat pids/api-springboot.pid))"

# ========================================
# Step 4: Start Embedding Service (Python)
# ========================================
echo -e "\n${YELLOW}[4/7] Starting Embedding Service (Python)...${NC}"
cd embedding-service-python
nohup uvicorn src.main:app --host 0.0.0.0 --port 8000 > ../logs/embedding-api.log 2>&1 &
echo $! > ../pids/embedding-api.pid
echo "  - Embedding API starting on port 8000 (PID: $(cat ../pids/embedding-api.pid))"

# Start Kafka consumer for embeddings
nohup python -m src.kafka_consumer > ../logs/embedding-consumer.log 2>&1 &
echo $! > ../pids/embedding-consumer.pid
cd ..
echo "  - Embedding Kafka Consumer started (PID: $(cat pids/embedding-consumer.pid))"

# ========================================
# Step 5: Start ETL Spark Pipeline
# ========================================
echo -e "\n${YELLOW}[5/7] Starting ETL Spark Pipeline...${NC}"
cd etl-spark-scala
nohup sbt run > ../logs/etl-spark.log 2>&1 &
echo $! > ../pids/etl-spark.pid
cd ..
echo "  - ETL Spark starting (PID: $(cat pids/etl-spark.pid))"

# ========================================
# Step 6: Start Search Indexer (Java)
# ========================================
echo -e "\n${YELLOW}[6/7] Starting Search Indexer (Java)...${NC}"
cd search-indexer-java
# Build if needed
if [ ! -f target/search-indexer-*.jar ]; then
    echo "  - Building search-indexer..."
    mvn package -DskipTests -q
fi
nohup java -jar target/search-indexer-*.jar > ../logs/search-indexer.log 2>&1 &
echo $! > ../pids/search-indexer.pid
cd ..
echo "  - Search Indexer started (PID: $(cat pids/search-indexer.pid))"

# ========================================
# Step 7: Start Crawler (Scala)
# ========================================
echo -e "\n${YELLOW}[7/7] Starting Crawler...${NC}"
cd crawler-scala
nohup sbt run > ../logs/crawler.log 2>&1 &
echo $! > ../pids/crawler.pid
cd ..
echo "  - Crawler started (PID: $(cat pids/crawler.pid))"

# ========================================
# Summary
# ========================================
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}  All Services Started!                ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Services:"
echo "  - API Server:         http://localhost:8090"
echo "  - Swagger UI:         http://localhost:8090/swagger-ui.html"
echo "  - Embedding Service:  http://localhost:8000"
echo "  - Elasticsearch:      http://localhost:9200"
echo "  - Kafka UI:           http://localhost:8088"
echo ""
echo "Logs:"
echo "  - API:        logs/api-springboot.log"
echo "  - Embedding:  logs/embedding-api.log"
echo "  - ETL:        logs/etl-spark.log"
echo "  - Indexer:    logs/search-indexer.log"
echo "  - Crawler:    logs/crawler.log"
echo ""
echo "Use './scripts/stop-all.sh' to stop all services"
