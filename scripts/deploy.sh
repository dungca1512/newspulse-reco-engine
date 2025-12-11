#!/bin/bash

# ============================================
# NewsPulse Deployment Script
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# ============================================
# Start Infrastructure
# ============================================
start_infra() {
    log_info "Starting infrastructure services..."
    cd "$PROJECT_ROOT"
    docker compose up -d
    
    log_info "Waiting for services to be ready..."
    sleep 30
    
    # Check Elasticsearch
    until curl -s http://localhost:9200/_cluster/health > /dev/null; do
        log_warn "Waiting for Elasticsearch..."
        sleep 5
    done
    log_info "Elasticsearch is ready"
    
    # Check Kafka
    until docker exec newspulse-kafka kafka-topics --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do
        log_warn "Waiting for Kafka..."
        sleep 5
    done
    log_info "Kafka is ready"
}

# ============================================
# Create Kafka Topics
# ============================================
create_topics() {
    log_info "Creating Kafka topics..."
    
    docker exec newspulse-kafka kafka-topics --create \
        --if-not-exists \
        --bootstrap-server localhost:9092 \
        --topic news_raw \
        --partitions 6 \
        --replication-factor 1
    
    docker exec newspulse-kafka kafka-topics --create \
        --if-not-exists \
        --bootstrap-server localhost:9092 \
        --topic news_cleaned \
        --partitions 6 \
        --replication-factor 1
    
    docker exec newspulse-kafka kafka-topics --create \
        --if-not-exists \
        --bootstrap-server localhost:9092 \
        --topic news_embedding \
        --partitions 6 \
        --replication-factor 1
    
    docker exec newspulse-kafka kafka-topics --create \
        --if-not-exists \
        --bootstrap-server localhost:9092 \
        --topic trending_updates \
        --partitions 3 \
        --replication-factor 1
    
    log_info "Kafka topics created"
}

# ============================================
# Build Modules
# ============================================
build_crawler() {
    log_info "Building crawler..."
    cd "$PROJECT_ROOT/crawler-scala"
    sbt assembly
}

build_etl() {
    log_info "Building ETL..."
    cd "$PROJECT_ROOT/etl-spark-scala"
    sbt assembly
}

build_clustering() {
    log_info "Building clustering..."
    cd "$PROJECT_ROOT/topic-clustering-scala"
    sbt assembly
}

build_trending() {
    log_info "Building trending engine..."
    cd "$PROJECT_ROOT/trending-engine-scala"
    sbt assembly
}

build_indexer() {
    log_info "Building search indexer..."
    cd "$PROJECT_ROOT/search-indexer-java"
    mvn clean package -DskipTests
}

build_api() {
    log_info "Building API..."
    cd "$PROJECT_ROOT/api-springboot"
    mvn clean package -DskipTests
}

build_all() {
    build_crawler
    build_etl
    build_clustering
    build_trending
    build_indexer
    build_api
    log_info "All modules built successfully"
}

# ============================================
# Run Modules
# ============================================
run_crawler() {
    log_info "Starting crawler..."
    cd "$PROJECT_ROOT/crawler-scala"
    java -jar target/scala-3.3.1/crawler-scala-assembly-0.1.0-SNAPSHOT.jar --once
}

run_crawler_loop() {
    log_info "Starting crawler in loop mode..."
    cd "$PROJECT_ROOT/crawler-scala"
    java -jar target/scala-3.3.1/crawler-scala-assembly-0.1.0-SNAPSHOT.jar --loop 15
}

run_etl() {
    log_info "Starting ETL..."
    cd "$PROJECT_ROOT/etl-spark-scala"
    spark-submit \
        --master local[*] \
        --class com.newspulse.etl.NewsETL \
        target/scala-2.13/etl-spark-scala-assembly-0.1.0-SNAPSHOT.jar
}

run_embedding_service() {
    log_info "Starting embedding service..."
    cd "$PROJECT_ROOT/embedding-service-python"
    pip install -r requirements.txt
    uvicorn src.main:app --host 0.0.0.0 --port 8000
}

run_indexer() {
    log_info "Starting search indexer..."
    cd "$PROJECT_ROOT/search-indexer-java"
    java -jar target/search-indexer-java-0.1.0-SNAPSHOT.jar
}

run_api() {
    log_info "Starting API..."
    cd "$PROJECT_ROOT/api-springboot"
    java -jar target/api-springboot-0.1.0-SNAPSHOT.jar
}

# ============================================
# Stop Infrastructure
# ============================================
stop_infra() {
    log_info "Stopping infrastructure..."
    cd "$PROJECT_ROOT"
    docker compose down
}

# ============================================
# Status Check
# ============================================
status() {
    log_info "Checking service status..."
    
    echo ""
    echo "=== Docker Services ==="
    docker compose ps
    
    echo ""
    echo "=== Kafka Topics ==="
    docker exec newspulse-kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null || echo "Kafka not running"
    
    echo ""
    echo "=== Elasticsearch ==="
    curl -s http://localhost:9200/_cluster/health?pretty 2>/dev/null || echo "Elasticsearch not running"
    
    echo ""
    echo "=== API Health ==="
    curl -s http://localhost:8090/actuator/health 2>/dev/null || echo "API not running"
}

# ============================================
# Main
# ============================================
case "$1" in
    start)
        start_infra
        create_topics
        ;;
    stop)
        stop_infra
        ;;
    build)
        build_all
        ;;
    build-crawler)
        build_crawler
        ;;
    build-etl)
        build_etl
        ;;
    build-indexer)
        build_indexer
        ;;
    build-api)
        build_api
        ;;
    run-crawler)
        run_crawler
        ;;
    run-crawler-loop)
        run_crawler_loop
        ;;
    run-etl)
        run_etl
        ;;
    run-embedding)
        run_embedding_service
        ;;
    run-indexer)
        run_indexer
        ;;
    run-api)
        run_api
        ;;
    status)
        status
        ;;
    *)
        echo "Usage: $0 {start|stop|build|status|run-crawler|run-etl|run-embedding|run-indexer|run-api}"
        exit 1
        ;;
esac
