"""
FastAPI application for the embedding service
"""
import os
import logging
from contextlib import asynccontextmanager
from typing import List

from fastapi import FastAPI, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware

from .models import (
    EmbedRequest,
    EmbedBatchRequest,
    EmbedResponse,
    EmbedBatchResponse,
    HealthResponse,
    SimilarityRequest,
    SimilarityResponse,
)
from .embedder import Embedder, get_embedder

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)

# Global embedder instance
embedder: Embedder = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan handler"""
    global embedder
    
    # Startup: Load model
    logger.info("Starting embedding service...")
    model_name = os.getenv("MODEL_NAME", "paraphrase-multilingual-mpnet-base-v2")
    embedder = Embedder(model_name)
    logger.info(f"Model loaded: {embedder.model_name} ({embedder.dimensions}d)")
    
    yield
    
    # Shutdown
    logger.info("Shutting down embedding service...")


# Create FastAPI app
app = FastAPI(
    title="NewsPulse Embedding Service",
    description="Text embedding service for news articles using Sentence Transformers",
    version="1.0.0",
    lifespan=lifespan,
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint"""
    global embedder
    
    return HealthResponse(
        status="healthy",
        model_loaded=embedder is not None,
        model_name=embedder.model_name if embedder else "not loaded",
        dimensions=embedder.dimensions if embedder else 0,
    )


@app.post("/embed", response_model=EmbedResponse)
async def embed_text(request: EmbedRequest):
    """
    Generate embedding for a single text
    
    - **text**: Text to embed (required)
    - **model**: Model name override (optional)
    """
    global embedder
    
    if embedder is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        # Use custom model if specified
        if request.model and request.model != embedder.model_name:
            custom_embedder = Embedder(request.model)
            embedding = custom_embedder.embed(request.text)
            return EmbedResponse(
                embedding=embedding,
                model=custom_embedder.model_name,
                dimensions=custom_embedder.dimensions,
            )
        
        embedding = embedder.embed(request.text)
        
        return EmbedResponse(
            embedding=embedding,
            model=embedder.model_name,
            dimensions=embedder.dimensions,
        )
    except Exception as e:
        logger.error(f"Embedding error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/embed/batch", response_model=EmbedBatchResponse)
async def embed_batch(request: EmbedBatchRequest):
    """
    Generate embeddings for multiple texts
    
    - **texts**: List of texts to embed (required, max 100)
    - **model**: Model name override (optional)
    """
    global embedder
    
    if embedder is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        embeddings = embedder.embed_batch(request.texts)
        
        return EmbedBatchResponse(
            embeddings=embeddings,
            model=embedder.model_name,
            dimensions=embedder.dimensions,
            count=len(embeddings),
        )
    except Exception as e:
        logger.error(f"Batch embedding error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/similarity", response_model=SimilarityResponse)
async def compute_similarity(request: SimilarityRequest):
    """
    Compute cosine similarity between two texts
    
    - **text1**: First text
    - **text2**: Second text
    """
    global embedder
    
    if embedder is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        similarity = embedder.similarity(request.text1, request.text2)
        
        return SimilarityResponse(
            similarity=similarity,
            method="cosine",
        )
    except Exception as e:
        logger.error(f"Similarity error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/models")
async def list_models():
    """List recommended models for Vietnamese text"""
    return {
        "current_model": embedder.model_name if embedder else None,
        "recommended_models": Embedder.RECOMMENDED_MODELS,
    }


if __name__ == "__main__":
    import uvicorn
    
    uvicorn.run(
        "src.main:app",
        host=os.getenv("HOST", "0.0.0.0"),
        port=int(os.getenv("PORT", 8000)),
        reload=True,
    )
