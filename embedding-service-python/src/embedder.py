"""
Embedding generation using Sentence Transformers
"""
import os
import logging
from typing import List, Optional
from functools import lru_cache

import numpy as np
from sentence_transformers import SentenceTransformer

logger = logging.getLogger(__name__)


class Embedder:
    """
    Text embedder using Sentence Transformers
    Supports multilingual models for Vietnamese text
    """
    
    # Recommended models for Vietnamese
    RECOMMENDED_MODELS = {
        "multilingual": "paraphrase-multilingual-mpnet-base-v2",
        "bge-m3": "BAAI/bge-m3",
        "e5-multilingual": "intfloat/multilingual-e5-large",
        "labse": "sentence-transformers/LaBSE",
    }
    
    def __init__(self, model_name: Optional[str] = None):
        """
        Initialize the embedder
        
        Args:
            model_name: Name or path of the sentence-transformers model
        """
        self.model_name = model_name or os.getenv(
            "MODEL_NAME", 
            self.RECOMMENDED_MODELS["multilingual"]
        )
        
        logger.info(f"Loading embedding model: {self.model_name}")
        
        self.model = SentenceTransformer(self.model_name)
        self.dimensions = self.model.get_sentence_embedding_dimension()
        
        logger.info(f"Model loaded. Embedding dimensions: {self.dimensions}")
    
    def embed(self, text: str, normalize: bool = True) -> List[float]:
        """
        Generate embedding for a single text
        
        Args:
            text: Input text
            normalize: Whether to L2 normalize the embedding
            
        Returns:
            List of floats representing the embedding
        """
        if not text or not text.strip():
            return [0.0] * self.dimensions
        
        embedding = self.model.encode(
            text,
            convert_to_numpy=True,
            normalize_embeddings=normalize
        )
        
        return embedding.tolist()
    
    def embed_batch(
        self, 
        texts: List[str], 
        normalize: bool = True,
        batch_size: int = 32,
        show_progress: bool = False
    ) -> List[List[float]]:
        """
        Generate embeddings for multiple texts
        
        Args:
            texts: List of input texts
            normalize: Whether to L2 normalize embeddings
            batch_size: Batch size for encoding
            show_progress: Show progress bar
            
        Returns:
            List of embeddings
        """
        if not texts:
            return []
        
        # Replace empty texts with placeholder
        processed_texts = [t if t and t.strip() else " " for t in texts]
        
        embeddings = self.model.encode(
            processed_texts,
            convert_to_numpy=True,
            normalize_embeddings=normalize,
            batch_size=batch_size,
            show_progress_bar=show_progress
        )
        
        return embeddings.tolist()
    
    def similarity(self, text1: str, text2: str) -> float:
        """
        Compute cosine similarity between two texts
        
        Args:
            text1: First text
            text2: Second text
            
        Returns:
            Cosine similarity score (-1 to 1)
        """
        emb1 = np.array(self.embed(text1, normalize=True))
        emb2 = np.array(self.embed(text2, normalize=True))
        
        return float(np.dot(emb1, emb2))
    
    def find_similar(
        self, 
        query: str, 
        candidates: List[str], 
        top_k: int = 5
    ) -> List[tuple]:
        """
        Find most similar texts from a list of candidates
        
        Args:
            query: Query text
            candidates: List of candidate texts
            top_k: Number of results to return
            
        Returns:
            List of (index, text, similarity) tuples
        """
        query_emb = np.array(self.embed(query, normalize=True))
        candidate_embs = np.array(self.embed_batch(candidates, normalize=True))
        
        similarities = np.dot(candidate_embs, query_emb)
        top_indices = np.argsort(similarities)[::-1][:top_k]
        
        results = []
        for idx in top_indices:
            results.append((int(idx), candidates[idx], float(similarities[idx])))
        
        return results


@lru_cache(maxsize=1)
def get_embedder(model_name: Optional[str] = None) -> Embedder:
    """
    Get cached embedder instance (singleton pattern)
    """
    return Embedder(model_name)
