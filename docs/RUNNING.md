# NewsPulse Recommendation Engine - Hướng dẫn chạy hệ thống

## Tổng quan kiến trúc

```
┌─────────────┐     ┌─────────────┐     ┌─────────────────┐     ┌─────────────────┐     ┌──────────────┐
│   Crawler   │────▶│  Kafka      │────▶│    ETL Spark    │────▶│    Embedding    │────▶│ Elasticsearch│
│   (Scala)   │     │  news_raw   │     │    (Scala)      │     │   Service (Py)  │     │              │
└─────────────┘     └─────────────┘     └─────────────────┘     └─────────────────┘     └──────────────┘
                          │                     │                       │                      │
                          ▼                     ▼                       ▼                      ▼
                    Kafka Topic           Kafka Topic             Kafka Topic           API Server
                    news_raw              news_cleaned            news_embedding        (Spring Boot)
                    (~2000 msg)           (~500 msg)              (~500 msg)            Port 8090
```

---

## Yêu cầu hệ thống

- **Docker** + Docker Compose
- **Java 17+** (cho Spring Boot, Search Indexer)
- **Scala/SBT** (cho Crawler, ETL)
- **Python 3.10+** với conda/pip (cho Embedding Service)
- **Maven** (cho Spring Boot projects)

---

## Quick Start

### Cách 1: Chạy tất cả bằng script

```bash
# Khởi động tất cả services
./scripts/start-all.sh

# Kiểm tra trạng thái
./scripts/status.sh

# Dừng tất cả services
./scripts/stop-all.sh
```

### Cách 2: Chạy từng service thủ công

Xem chi tiết bên dưới.

---

## Chi tiết từng bước

### Bước 1: Khởi động Infrastructure

```bash
# Khởi động Docker containers (Kafka, Elasticsearch, Redis)
docker compose up -d

# Kiểm tra containers đang chạy
docker ps
```

**Services được khởi động:**
| Service | Port | URL |
|---------|------|-----|
| Kafka | 9092 | - |
| Zookeeper | 2181 | - |
| Elasticsearch | 9200 | http://localhost:9200 |
| Redis | 6379 | - |
| Kafka UI | 8088 | http://localhost:8088 |

---

### Bước 2: Tạo Elasticsearch Indices

```bash
# Tạo index cho articles
curl -X PUT "http://localhost:9200/news_articles" -H "Content-Type: application/json" -d '{
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "title": { "type": "text" },
      "content": { "type": "text" },
      "source": { "type": "keyword" },
      "category": { "type": "keyword" },
      "publishTime": { "type": "date" },
      "crawlTime": { "type": "date" },
      "embedding": { "type": "dense_vector", "dims": 768, "index": true, "similarity": "cosine" }
    }
  }
}'

# Tạo index cho trending
curl -X PUT "http://localhost:9200/news_trending" -H "Content-Type: application/json" -d '{
  "mappings": {
    "properties": {
      "cluster_id": { "type": "integer" },
      "title": { "type": "text" },
      "trending_score": { "type": "float" },
      "article_count": { "type": "integer" },
      "category": { "type": "keyword" },
      "is_spike": { "type": "boolean" }
    }
  }
}'
```

---

### Bước 3: Chạy API Server (Spring Boot)

```bash
cd api-springboot
mvn spring-boot:run
```

**URL:** http://localhost:8090
**Swagger UI:** http://localhost:8090/swagger-ui.html

---

### Bước 4: Chạy Embedding Service (Python)

**Terminal 1 - REST API:**
```bash
cd embedding-service-python
pip install -r requirements.txt  # Lần đầu tiên
uvicorn src.main:app --host 0.0.0.0 --port 8000
```

**Terminal 2 - Kafka Consumer (QUAN TRỌNG!):**
```bash
cd embedding-service-python
python -m src.kafka_consumer
```

> ⚠️ **Lưu ý:** Phải chạy cả 2 process! Kafka Consumer đọc từ `news_cleaned` và produce vào `news_embedding`.

---

### Bước 5: Chạy ETL Spark

```bash
cd etl-spark-scala
sbt run
```

Hoặc với Spark cluster:
```bash
sbt "run --master spark://localhost:7077"
```

---

### Bước 6: Chạy Search Indexer (Java)

```bash
cd search-indexer-java
mvn package -DskipTests  # Build lần đầu
java -jar target/search-indexer-*.jar
```

---

### Bước 7: Chạy Crawler (Scala)

```bash
cd crawler-scala
sbt run
```

---

## Kiểm tra Pipeline

### Kiểm tra Kafka messages

```bash
# Xem số lượng messages trong mỗi topic
docker exec newspulse-kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic news_raw

docker exec newspulse-kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic news_cleaned

docker exec newspulse-kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic news_embedding
```

### Kiểm tra Elasticsearch

```bash
# Đếm documents
curl "http://localhost:9200/news_articles/_count"
curl "http://localhost:9200/news_trending/_count"

# Search thử
curl "http://localhost:9200/news_articles/_search?size=1"
```

### Test API

```bash
# Search
curl "http://localhost:8090/api/search?q=news"

# Trending
curl "http://localhost:8090/api/trending"
```

---

## Xử lý sự cố

### Problem: API trả về 500 Error

**Nguyên nhân:** Elasticsearch index không tồn tại hoặc mapping sai.

**Giải pháp:**
```bash
# Kiểm tra indices
curl "http://localhost:9200/_cat/indices"

# Tạo lại index nếu cần
curl -X DELETE "http://localhost:9200/news_articles"
curl -X DELETE "http://localhost:9200/news_trending"
# Sau đó chạy lại Bước 2
```

---

### Problem: Elasticsearch có 0 documents

**Nguyên nhân 1:** Embedding Kafka Consumer không chạy.
```bash
# Kiểm tra
ps aux | grep "kafka_consumer"

# Chạy nếu chưa có
cd embedding-service-python && python -m src.kafka_consumer
```

**Nguyên nhân 2:** Kafka offset đã committed nhưng không index.
```bash
# Reset offset và chạy lại
./scripts/reset-offsets.sh
```

---

### Problem: Jackson Serialization Error

**Lỗi:** `Java 8 date/time type java.time.Instant not supported`

**Đã fix:** File `search-indexer-java/src/main/java/com/newspulse/indexer/ElasticsearchClientWrapper.java` đã được cập nhật với `JavaTimeModule`.

```bash
# Rebuild
cd search-indexer-java
mvn package -DskipTests
java -jar target/search-indexer-*.jar
```

---

## Scripts hữu ích

| Script | Mô tả |
|--------|-------|
| `./scripts/start-all.sh` | Khởi động tất cả services |
| `./scripts/stop-all.sh` | Dừng tất cả services |
| `./scripts/status.sh` | Kiểm tra trạng thái |
| `./scripts/reset-offsets.sh` | Reset Kafka offsets để reprocess data |

---

## Logs

Logs được lưu trong thư mục `logs/`:
- `api-springboot.log`
- `embedding-api.log`
- `embedding-consumer.log`
- `etl-spark.log`
- `search-indexer.log`
- `crawler.log`

---

## Ports Summary

| Service | Port |
|---------|------|
| API Server | 8090 |
| Embedding Service | 8000 |
| Kafka | 9092 |
| Elasticsearch | 9200 |
| Redis | 6379 |
| Kafka UI | 8088 |
| Zookeeper | 2181 |
