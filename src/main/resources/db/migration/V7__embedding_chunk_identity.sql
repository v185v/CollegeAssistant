ALTER TABLE embedding_chunk
    ADD CONSTRAINT uq_embedding_chunk_identity
    UNIQUE (doc_ref_type, doc_ref_id, chunk_index);
