-- One database per service: ticket-service owns "tickets", triage-service owns "triage" (the vector store).
CREATE DATABASE triage;
\connect triage
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
