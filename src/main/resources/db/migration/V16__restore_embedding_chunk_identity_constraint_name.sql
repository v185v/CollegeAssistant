ALTER TABLE embedding_chunk
    RENAME CONSTRAINT uq_embedding_chunk_v14_identity
    TO uq_embedding_chunk_identity;
