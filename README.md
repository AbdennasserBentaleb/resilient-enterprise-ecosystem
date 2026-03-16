# Resilient Multi-Role Enterprise Ecosystem

> A cloud-native financial ledger system demonstrating event-driven persistence and distributed locks.

[![Java](https://img.shields.io/badge/Java-17-blue)](https://www.java.com)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)](https://spring.io)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-1.29-blue)](https://kubernetes.io)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

##  Table of Contents

* [Architecture](#-architecture)
* [Tech Stack](#-tech-stack)
* [Prerequisites](#-prerequisites)
* [Quick Start (Docker Compose)](#-quick-start-docker-compose)
* [Kubernetes Deployment (Docker Desktop)](#-kubernetes-deployment-docker-desktop)
* [End-to-End Testing](#-end-to-end-testing)
* [API Reference](#-api-reference)
* [Management UIs](#-management-uis)
* [Chaos Engineering](#-chaos-engineering)
* [CI/CD Pipeline](#-cicd-pipeline)
* [Project Structure](#-project-structure)

---

## Architecture

```
Client → API Gateway (JWT validation) → Core Ledger Service
                                              │
                         ┌────────────────────┼────────────────────┐
                         │                    │                    │
                      Redis              PostgreSQL            RabbitMQ
                   (Dist. Locks)       (RLS + Liquibase)    (Events + DLQ)
                                            │
                                        Debezium (CDC)
                                            │
                                        Apache Kafka
                                            │
                                    Audit Log Service
                                   (Immutable Audit Trail)
```

**Key Design Decisions:**
*  **Zero-Trust Security**: JWT validated at gateway; never reaches downstream services unauthenticated
*  **Row-Level Security**: PostgreSQL enforces tenant isolation at the DB engine level via Spring AOP
*  **Idempotency**: All transactions require a unique `Idempotency-Key` to prevent double-processing
*  **Resilient Events**: RabbitMQ DLQ + exponential backoff retry catches all transient failures
*  **Immutable Audit Log**: Debezium captures every DB row change directly from PostgreSQL WAL

---

## Architecture Decisions & Trade-offs

Real enterprise engineering involves practical compromises. Here are the deliberate trade-offs made in this ecosystem:

1. **Trade-off: Java GC vs. Ultra-Low Latency**
   * **Limitation**: As a JVM-based application, this ledger is subject to Garbage Collection (GC) pauses. For a true High-Frequency Trading (HFT) matching engine with strict sub-microsecond requirements, C++ or Rust would be necessary to completely eliminate GC jitter.
   * **Why Java?**: This project prioritizes correct algorithmic transaction processing (price-time priority concepts) and asynchronous persistence where JVM throughput is sufficient, maximizing developer velocity and leveraging the Spring Cloud ecosystem.

2. **Trade-off: Managing Virtual Thread Locals**
   * **Challenge**: Handling massive event bursts can strain contextual data propagation. Utilizing Java 21+ Virtual Threads offers incredible throughput, but extreme care must be taken with `ThreadLocal` variables (like security contexts or tenant contexts), as virtual threads don't pool in the same way platform threads do.
   * **Decision**: We explicitly propagate the `TenantContext` across asynchronous boundaries where needed, avoiding heavy reliance on legacy `ThreadLocal` patterns that can leak memory or context in high-throughput Loom environments.

3. **Trade-off: Redis Distributed Locks vs. Postgres Row Locks**
   * **Why Specific Tool**: Under high concurrency, using `SELECT FOR UPDATE` in PostgreSQL can lead to massive connection pool exhaustion and database-level deadlocks.
   * **Decision**: We use Redis-based distributed locks with a fast-fail mechanism. If a transaction lock is contested, it instantly throws an exception yielding to the client to back-off and retry, keeping the database load predictable and connections free.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2, Spring Cloud Gateway 4.x |
| Security | Spring Security, Keycloak 22 (OAuth2/JWT) |
| Persistence | PostgreSQL 15 (RLS), Spring Data JPA, Liquibase |
| Caching / Locks | Redis 7 |
| Messaging | RabbitMQ 3.12 (AMQP + DLQ) |
| Streaming / CDC | Apache Kafka, Debezium Connect 2.4 |
| Containerization | Docker, Docker Compose |
| Orchestration | Kubernetes (Docker Desktop or K3s) |
| CI/CD | GitHub Actions + Trivy vulnerability scanning |
| GitOps | ArgoCD |
| Observability | Prometheus, Grafana, Spring Boot Actuator |

---

## Prerequisites

Install the following before getting started:

| Tool | Version | Download |
|---|---|---|
| **Git** | any | https://git-scm.com |
| **Java (JDK)** | **17 or higher** | https://adoptium.net |
| **Maven** | 3.8+ | https://maven.apache.org/download.cgi |
| **Docker Desktop** | Latest | https://www.docker.com/products/docker-desktop |

> **Important:** Spring Boot 3 requires **Java 17 minimum**. Java 8 or 11 will not compile this project.

**Windows users must also have WSL2 installed:**
1. Open Microsoft Store → search for **Windows Subsystem for Linux** → Install
2. Open Docker Desktop → Settings → General →  Use WSL2 based engine

---

## Quick Start (Docker Compose)

 **No Kubernetes needed.**

### 1. Clone the repository

```bash
git clone https://github.com/AbdennasserBentaleb/resilient-enterprise-ecosystem.git
cd resilient-enterprise-ecosystem
```

### 2. Start the entire ecosystem

```bash
docker-compose up -d --build
```

This automatically initializes **PostgreSQL, Redis, Keycloak, Zookeeper, Kafka, Debezium Connect, RabbitMQ**, AND builds/deploys the `api-gateway` (on port 8080), `core-ledger` (on port 8082), and `audit-log-service` simultaneously!

Wait ~40 seconds for all services to initialize.

### 3. Register the Debezium CDC connector

```bash
# Linux / macOS / WSL:
chmod +x register-connector.sh
./register-connector.sh

# Windows PowerShell:
$json = Get-Content debezium-postgres-connector.json -Raw
Invoke-RestMethod -Uri "http://localhost:8083/connectors/" -Method Post -Body $json -ContentType "application/json"
```

### 4. Seed Database with Test Account
Wait until `core-ledger-svc` has finished initializing (`docker logs core-ledger-svc`), then execute:

```bash
docker exec -i ledger-postgres psql -U postgres -d ledger_db -c "INSERT INTO accounts (id, tenant_id, balance, created_at) VALUES ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'f0e1d2c3-b4a5-6789-abcd-ef0123456789', 1000.00, NOW()) ON CONFLICT DO NOTHING;"
```
```

---

## Kubernetes Deployment (Docker Desktop)



### Prerequisites

In Docker Desktop → **Settings** → **Kubernetes** →  Enable Kubernetes → Apply & Restart.

### 1. Build Docker images

Run from the root of the project:

```bash
# Build all three microservices
mvn clean package -DskipTests -f core-ledger/pom.xml
docker build -t portfolio/core-ledger:latest core-ledger/

mvn clean package -DskipTests -f api-gateway/pom.xml
docker build -t portfolio/api-gateway:latest api-gateway/

mvn clean package -DskipTests -f audit-log-service/pom.xml
docker build -t portfolio/audit-log-service:latest audit-log-service/
```

### 2. Update image names in K8s manifests

Edit the `image:` field in `k8s/03-core-ledger.yaml`, `k8s/04-api-gateway.yaml`, and `k8s/05-audit-log.yaml` to match your local image tags (e.g. `portfolio/core-ledger:latest`), and set `imagePullPolicy: Never`.

### 3. Create namespace and deploy

```bash
kubectl create namespace portfolio-ecosystem

# Deploy all manifests
kubectl apply -f k8s/
```

### 4. Verify all pods are Running

```bash
kubectl get pods -n portfolio-ecosystem
```

Expected output (all `Running`):

```
NAME                                 READY   STATUS    RESTARTS
api-gateway-xxx                      1/1     Running   0
audit-log-service-xxx                1/1     Running   0
core-ledger-xxx                      1/1     Running   0
debezium-xxx                         1/1     Running   0
kafka-xxx                            1/1     Running   0
keycloak-xxx                         1/1     Running   0
postgres-xxx                         1/1     Running   0
rabbitmq-xxx                         1/1     Running   0
redis-xxx                            1/1     Running   0
zookeeper-xxx                        1/1     Running   0
```

### 5. Register the Debezium connector

Once Debezium pod is Running, you must port-forward before registering:

```bash
# Terminal 1: Port-forward the service
kubectl port-forward svc/debezium-svc 8083:8083 -n portfolio-ecosystem

# Terminal 2: Register the connector (Windows PowerShell example)
$json = Get-Content debezium-postgres-connector.json -Raw
Invoke-RestMethod -Uri "http://localhost:8083/connectors/" -Method Post -Body $json -ContentType "application/json"
```

---

## End-to-End Testing

### Step 1 — Seed a test account directly in PostgreSQL

```bash
kubectl exec -it deploy/postgres -n portfolio-ecosystem -- psql -U postgres -d ledger_db -c \
  "INSERT INTO accounts (id, tenant_id, balance, created_at) \
   VALUES ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', \
           'f0e1d2c3-b4a5-6789-abcd-ef0123456789', \
           1000.00, NOW());"
```

For **Docker Compose** (no Kubernetes), connect directly:
```bash
docker exec -it ledger-postgres psql -U postgres -d ledger_db -c \
  "INSERT INTO accounts (id, tenant_id, balance, created_at) \
   VALUES ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', \
           'f0e1d2c3-b4a5-6789-abcd-ef0123456789', \
           1000.00, NOW());"
```

### Step 2 — Post a credit transaction

```bash
curl -s -X POST http://localhost:8082/api/v1/transactions/credit \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: f0e1d2c3-b4a5-6789-abcd-ef0123456789" \
  -H "Idempotency-Key: txn-test-001" \
  -d '{"accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "amount": 250.00, "description": "Test Transaction"}'
```

**Expected response:**
```json
{
  "id": "<uuid>",
  "accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "amount": 250.00,
  "idempotencyKey": "txn-test-001",
  "status": "SUCCESS",
  "createdAt": "..."
}
```

### Step 3 — Verify idempotency (replay protection)

Re-run the **same** curl command with the same `Idempotency-Key`. The system should return the same transaction without creating a duplicate.

### Step 4 — Verify the retry/DLQ pattern

Check the Core Ledger logs. You should see the built-in **simulated failure + retry logic** fire:
```
INFO  Received event for transaction: <id>
WARN  Simulated failure ... Throwing exception to trigger retry...
WARN  Simulated failure ... Throwing exception to trigger retry...
INFO  Successfully processed event for transaction <id>
```

### Step 5 — Run unit tests

```bash
cd core-ledger && mvn test
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`

---

## API Reference

All requests pass through the **API Gateway** on port `80` (Kubernetes) or `8080` (local).

| Method | Endpoint | Headers Required | Description |
|---|---|---|---|
| `POST` | `/api/v1/transactions/credit` | `X-Tenant-Id`, `Idempotency-Key`, `Content-Type` | Credit an account |
| `POST` | `/api/v1/transactions/debit` | `X-Tenant-Id`, `Idempotency-Key`, `Content-Type` | Debit an account |
| `GET` | `/api/v1/accounts/{accountId}/balance` | `X-Tenant-Id` | Get account balance |

**Request body (credit/debit):**
```json
{
  "accountId": "<uuid>",
  "amount": 100.00,
  "description": "Optional description"
}
```

---

## Management UIs

| Service | URL | Credentials |
|---|---|---|
| **RabbitMQ Management** | http://localhost:15672 | `guest` / `guest` |
| **Keycloak Admin Console** | http://localhost:8084 | `admin` / `admin` |
| **Debezium Connect API** | http://localhost:8083/connectors | — |

**Port-forward RabbitMQ when using Kubernetes:**
```bash
kubectl port-forward svc/rabbitmq-svc 15672:15672 -n portfolio-ecosystem
```

Then visit http://localhost:15672 → **Queues and Streams** tab.

You should see:
* `ledger.transaction.completed.queue` — with **DLX** (Dead Letter Exchange) configured
* `ledger.transaction.dlq` — the Dead Letter Queue

---

## Chaos Engineering

Use the Chaos Monkey script to randomly terminate pods and verify Kubernetes self-heals:

```bash
# Linux / macOS / WSL
chmod +x chaos-monkey.sh
./chaos-monkey.sh
```

Watch the pods recover on their own:
```bash
kubectl get pods -n portfolio-ecosystem -w
```

During chaos, send transactions. Due to RabbitMQ's durable queues, **no messages are lost**.

---

## CI/CD Pipeline

A GitHub Actions workflow is defined in `.github/workflows/main.yml` that runs on push to `main`:

1. **Test** — `mvn test` for all services
2. **Build** — Docker image build
3. **Scan** — Trivy vulnerability scan
4. **Push** — Push images to your container registry

To enable image pushing, add these GitHub repository secrets:
* `DOCKER_USERNAME` — your Docker Hub username
* `DOCKER_PASSWORD` — your Docker Hub access token

---

## Project Structure

```
├── api-gateway/              # Spring Cloud Gateway — JWT validation & routing
│   ├── src/
│   └── Dockerfile
├── core-ledger/              # Main transaction engine
│   ├── src/
│   │   ├── main/java/...
│   │   │   ├── controller/   # REST endpoints
│   │   │   ├── service/      # Transaction logic + Redis locks
│   │   │   ├── entity/       # JPA entities (Account, Transaction)
│   │   │   ├── config/       # RabbitMQ, RLS AOP, Redis config
│   │   │   └── dto/          # Request/Response DTOs
│   │   └── resources/
│   │       └── db/changelog/ # Liquibase migration scripts
│   └── Dockerfile
├── audit-log-service/        # Kafka consumer — writes immutable audit log
│   ├── src/
│   └── Dockerfile
├── k8s/                      # Kubernetes manifests
│   ├── 00-backing-services.yaml
│   ├── 01-configmap.yaml
│   ├── 02-secret.yaml
│   ├── 03-core-ledger.yaml
│   ├── 04-api-gateway.yaml
│   ├── 05-audit-log.yaml
│   ├── 06-persistent-volumes.yaml
│   └── 09-postgres-init.yaml
├── docs/                     # Architecture & design documents
├── init-scripts/             # PostgreSQL initialization SQL
├── debezium-postgres-connector.json
├── register-connector.sh
├── chaos-monkey.sh
└── docker-compose.yml
```

---

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
