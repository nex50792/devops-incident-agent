CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS postmortems (
    id text PRIMARY KEY,
    title text NOT NULL,
    summary text NOT NULL,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS postmortems_embedding_hnsw
    ON postmortems
    USING hnsw (embedding vector_cosine_ops);
