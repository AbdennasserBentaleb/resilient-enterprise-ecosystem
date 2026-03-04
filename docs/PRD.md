# Product Requirements Document (PRD)

## 1. Project Overview
**Name**: Resilient Multi-Role Enterprise Ecosystem (High-Throughput Financial Ledger)
**Description**: A fault-tolerant, cloud-native financial ledger system designed to handle high-throughput transactions with zero data loss. It features multi-tenancy, strict security protocols, asynchronous data pipelines, and a resilient bare-metal deployment strategy.

## 2. Target Audience / Roles Showcased
* **Cloud-Native Java Developer**: Demonstrating core transaction engines, distributed locks, and row-level security.
* **System Integration Engineer**: Demonstrating asynchronous data pipelines, Change Data Capture (CDC), and queue management with Dead Letter Queues (DLQ).
* **System Administrator / DevOps**: Demonstrating bare-metal Kubernetes (K3s), highly available persistent storage, GitOps pipelines, and Chaos Engineering.

## 3. Core Features & Requirements

### Phase 1: Core Transaction Engine (Java / Backend focus)
* **High-Throughput Ledger**: RESTful API to handle credit and debit transactions.
* **Concurrency Control**: Use Redis Distributed Locks to prevent race conditions (e.g., double-spending).
* **Multi-Tenant Gateway**: Spring Cloud Gateway to route and secure requests.
* **Identity & Access Management Policy**: Integration with Keycloak for incoming OAuth2/JWT token validation and Role-Based Access Control (RBAC).
* **Data Isolation**: PostgreSQL with Row-Level Security (RLS) to ensure strict tenant data segregation.

### Phase 2: Resilient Integration (Event-Driven focus)
* **Asynchronous Data Pipeline**: Debezium monitoring PostgreSQL tables to stream Change Data Capture (CDC) events into Apache Kafka.
* **Immutable Audit Log**: Spring Boot Kafka Consumer to write transaction logs to a persistent, read-only directory.
* **Fault-Tolerant Dispatching**: RabbitMQ for internal event queuing (e.g., "Transaction Complete").
* **Reliability Features**: Dead Letter Queues (DLQ), consumer retry logic with exponential backoff, and Idempotency Keys to prevent duplicate processing.

### Phase 3: Infrastructure and Automation (DevOps focus)
* **Kubernetes Environment**: Bare-metal K3s cluster setup.
* **Persistent Storage**: NFS via Network Attached Storage (NAS) for stateful applications (Postgres, Kafka, Keycloak).
* **GitOps CI/CD**: GitHub Actions pipeline for testing, Trivy vulnerability scanning, building, and pushing Docker images. ArgoCD for zero-downtime deployment.
* **Observability**: Prometheus and Grafana for system-wide monitoring.
* **Chaos Engineering**: Automated scripts (Chaos Monkey) simulating node/pod failures with Pod Disruption Budgets (PDBs) ensuring data integrity and system self-healing.

## 4. Non-Functional Requirements
* **Performance**: Millisecond response time for ledger API using Redis caching.
* **Security**: Zero-trust architecture, encryption in transit (TLS), and rest.
* **Availability**: High Availability (HA) across all critical components.
* **Testability**: 100% test coverage for core business logic using JUnit 5.
