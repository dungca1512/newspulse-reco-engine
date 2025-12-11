# 📰 NewsPulse - Hệ Thống Phân Tích Tin Tức Thông Minh

Hệ thống thu thập, xử lý và phân tích tin tức Việt Nam với khả năng phát hiện xu hướng và tìm kiếm ngữ nghĩa.

## 🏗️ Kiến Trúc Hệ Thống

```
[Crawler (Scala)] → [Kafka] → [Spark ETL] → [Embedding Service] → [Delta Lake]
                                                    ↓
[API (Spring Boot)] ← [Elasticsearch] ← [Trending Engine] ← [Topic Clustering]
```

## 📦 Các Module

| Module | Ngôn ngữ | Mô tả |
|--------|----------|-------|
| `crawler-scala` | Scala 3 | Thu thập tin tức từ các báo Việt Nam |
| `etl-spark-scala` | Scala | Pipeline xử lý dữ liệu với Spark Streaming |
| `embedding-service-python` | Python | Service tạo vector embeddings |
| `topic-clustering-scala` | Scala | Phân cụm bài viết theo chủ đề |
| `trending-engine-scala` | Scala | Phát hiện xu hướng real-time |
| `search-indexer-java` | Java | Index dữ liệu vào Elasticsearch |
| `api-springboot` | Java | REST API phục vụ dữ liệu |

## Yêu Cầu Hệ Thống

- **Docker & Docker Compose** 
- **JDK 17+**
- **Scala 3.3+** (thông qua sbt)
- **Python 3.10+**
- **Maven 3.8+**

---

## 🚀 Hướng Dẫn Chạy Project

### Bước 1: Khởi động Infrastructure

```bash
# Di chuyển vào thư mục project
cd /Users/dungca/newspulse-reco-engine

# Khởi động tất cả các service
docker compose up -d

# Kiểm tra trạng thái
docker compose ps
```

**Các service sẽ được khởi động:**
| Service | Port | Mô tả |
|---------|------|-------|
| Zookeeper | 2181 | Quản lý Kafka cluster |
| Kafka | 9092 | Message queue |
| Kafka UI | 8080 | Giao diện web cho Kafka |
| Elasticsearch | 9200 | Search engine |
| Kibana | 5601 | Giao diện cho Elasticsearch |
| Redis | 6379 | Cache |
| Spark Master | 7077, 8081 | Xử lý dữ liệu phân tán |
| Spark Worker | - | Worker node cho Spark |

### Bước 2: Tạo Kafka Topics

```bash
# Topic cho tin tức thô
docker exec -it newspulse-kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic news_raw \
  --partitions 6 \
  --replication-factor 1

# Topic cho tin tức đã xử lý
docker exec -it newspulse-kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic news_cleaned \
  --partitions 6 \
  --replication-factor 1

# Topic cho embeddings
docker exec -it newspulse-kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic news_embedding \
  --partitions 6 \
  --replication-factor 1
```

---

## 📂 Hướng Dẫn Chạy Từng Module

### 1️⃣ Embedding Service (Python)

Service tạo vector embeddings cho văn bản tiếng Việt.

```bash
cd embedding-service-python

# Cài đặt dependencies
pip install -r requirements.txt

# Chạy service
uvicorn src.main:app --host 0.0.0.0 --port 8000
```

**Kiểm tra:** Truy cập http://localhost:8000/docs để xem Swagger UI.

---

### 2️⃣ Crawler (Scala)

Thu thập tin tức từ các nguồn: VnExpress, VietnamNet, Kenh14, v.v.

```bash
cd crawler-scala

# Chạy với sbt
sbt run
```

**Trong IntelliJ IDEA:**
1. Mở thư mục `crawler-scala`
2. Đợi IntelliJ import SBT project
3. Tìm file `Main.scala` và click **Run**

---

### 3️⃣ ETL Spark (Scala)

Pipeline xử lý dữ liệu: làm sạch, chuẩn hóa, và loại bỏ trùng lặp.

```bash
cd etl-spark-scala

# Chạy với Spark local
sbt run

# Hoặc chạy với Spark cluster
sbt "run --master spark://localhost:7077"
```

**Trong IntelliJ IDEA:**
1. Mở thư mục `etl-spark-scala`
2. Tìm file `NewsETL.scala` và click **Run**

---

### 4️⃣ Topic Clustering (Scala)

Phân cụm bài viết theo sự kiện/chủ đề.

```bash
cd topic-clustering-scala

sbt run
```

---

### 5️⃣ Trending Engine (Scala)

Phát hiện và tính điểm xu hướng real-time.

```bash
cd trending-engine-scala

sbt run
```

---

### 6️⃣ Search Indexer (Java)

Index dữ liệu vào Elasticsearch với BM25 + vector search.

```bash
cd search-indexer-java

# Build với Maven
mvn clean package

# Chạy
java -jar target/search-indexer-*.jar
```

**Trong IntelliJ IDEA:**
1. Mở thư mục `search-indexer-java`
2. Tìm file `NewsIndexer.java` và click **Run**

---

### 7️⃣ API Spring Boot (Java)

REST API chính cung cấp dữ liệu cho client.

```bash
cd api-springboot

# Chạy với Maven
mvn spring-boot:run
```

**Trong IntelliJ IDEA:**
1. Mở file `src/main/java/com/newspulse/api/NewsPulseApiApplication.java`
2. Click icon **Run** (▶) bên cạnh method `main()`

**Kiểm tra:** Truy cập http://localhost:8080/swagger-ui.html

> ⚠️ **Lưu ý:** Port 8080 bị conflict với Kafka UI. Có thể đổi port trong `application.properties`:
> ```properties
> server.port=9090
> ```

---

## 📊 Luồng Dữ Liệu

1. **Crawlers** thu thập bài viết từ các báo Việt Nam
2. **Kafka** lưu trữ tạm thời bài viết thô trong topic `news_raw`
3. **Spark ETL** làm sạch và chuẩn hóa, output ra `news_cleaned`
4. **Embedding Service** tạo vector embeddings, output ra `news_embedding`
5. **Delta Lake** lưu trữ dữ liệu theo zones: raw/clean/embedding
6. **Topic Clustering** nhóm bài viết theo sự kiện
7. **Trending Engine** tính điểm xu hướng real-time
8. **Search Indexer** index vào Elasticsearch
9. **API** phục vụ trending, search và recommendations

---

## � API Endpoints

### Trending
- `GET /api/trending` - Lấy danh sách chủ đề trending
- `GET /api/trending/{topicId}` - Chi tiết một chủ đề
- `GET /api/breaking` - Tin nóng

### Tìm kiếm
- `GET /api/search?q=...` - Tìm kiếm từ khóa (BM25)
- `POST /api/search/semantic` - Tìm kiếm ngữ nghĩa
- `GET /api/search/hybrid` - Tìm kiếm kết hợp (RRF)

### Gợi ý
- `GET /api/articles/{id}/related` - Bài viết liên quan
- `GET /api/recommendations` - Gợi ý cá nhân hóa

---

## 📁 Cấu Trúc Data Lake

```
data/lake/
├── raw/
│   └── source=vnexpress/date=2025-01-01/*.json
├── clean/
│   └── articles.parquet
└── embedding/
    └── articles.delta
```

---

## ⚙️ Cấu Hình Environment Variables

```bash
# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# Elasticsearch
ELASTICSEARCH_HOST=localhost
ELASTICSEARCH_PORT=9200

# Spark
SPARK_MASTER=spark://localhost:7077

# Embedding Service
EMBEDDING_SERVICE_URL=http://localhost:8000
EMBEDDING_MODEL=paraphrase-multilingual-mpnet-base-v2
```

---

## 📝 License

MIT License
