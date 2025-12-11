"""
Pydantic models for the embedding service API
"""
from pydantic import BaseModel, Field
from typing import List, Optional
from datetime import datetime


class EmbedRequest(BaseModel):
    """Request model for single text embedding"""
    text: str = Field(..., description="Text to embed", min_length=1, max_length=10000)
    model: Optional[str] = Field(None, description="Model name override")


class EmbedBatchRequest(BaseModel):
    """Request model for batch text embedding"""
    texts: List[str] = Field(..., description="List of texts to embed", min_length=1, max_length=100)
    model: Optional[str] = Field(None, description="Model name override")


class EmbedResponse(BaseModel):
    """Response model for embedding"""
    embedding: List[float] = Field(..., description="Vector embedding")
    model: str = Field(..., description="Model used for embedding")
    dimensions: int = Field(..., description="Embedding dimensions")


class EmbedBatchResponse(BaseModel):
    """Response model for batch embedding"""
    embeddings: List[List[float]] = Field(..., description="List of vector embeddings")
    model: str = Field(..., description="Model used for embedding")
    dimensions: int = Field(..., description="Embedding dimensions")
    count: int = Field(..., description="Number of embeddings")


class Article(BaseModel):
    """Article model from Kafka"""
    id: str
    url: str
    title: str
    description: Optional[str] = None
    content: str
    author: Optional[str] = None
    source: str
    category: Optional[str] = None
    tags: Optional[List[str]] = None
    imageUrl: Optional[str] = None
    publishTime: Optional[int] = None
    crawlTime: int
    language: Optional[str] = None
    word_count: Optional[int] = None


class ArticleWithEmbedding(Article):
    """Article with embedding vector"""
    embedding: List[float]
    embedding_model: str
    embedding_time: datetime = Field(default_factory=datetime.utcnow)


class HealthResponse(BaseModel):
    """Health check response"""
    status: str = "healthy"
    model_loaded: bool
    model_name: str
    dimensions: int


class SimilarityRequest(BaseModel):
    """Request for similarity calculation"""
    text1: str
    text2: str


class SimilarityResponse(BaseModel):
    """Response for similarity calculation"""
    similarity: float = Field(..., ge=-1.0, le=1.0)
    method: str = "cosine"
