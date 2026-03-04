# System Architecture Document

## 1. System Overview
The Enterprise Ecosystem is a microservices-based architecture built on Spring Boot 3, deployed on a K3s cluster, and utilizing event-driven patterns to ensure high availability, fault tolerance, and data consistency.

## 2. Component Architecture

### 2.1 API Gateway
* **Technology**: Spring Cloud Gateway
* **Function**: Entry point for all external client requests. Handles rate limiting, SSL termination, and initial token validation.

### 2.2 Identity Provider (IdP)
* **Technology**: Keycloak
* **Function**: Issues and validates JWTs. Manages users, tenants, and roles.

### 2.3 Core Ledger Service
* **Technology**: Spring Boot 3, Java 17
* **Database**: PostgreSQL (with Row-Level Security)
* **Caching & Locks**: Redis
* **Function**: Manages transactional logic (credits, debits). Uses Redis distributed locks to serialize operations on the same account to prevent race conditions.

### 2.4 Event-Driven Pipeline
* **CDC Component**: Debezium connected to PostgreSQL.
* **Message Broker**: Apache Kafka.
* **Audit Logging Service**: Spring Boot service consuming Kafka topics to write immutable logs to an NFS mount.
* **Event Dispatcher Broker**: RabbitMQ (handling internal app events like notifications or async workflows) with DLQ support.

## 3. Database Schema (PostgreSQL)

### Table: `accounts`
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PK | Unique account identifier |
| `tenant_id` | UUID | FK | Tenant ID for RLS masking |
| `balance` | DECIMAL | NOT NULL | Current balance |
| `created_at`| TIMESTAMP | | |

### Table: `transactions`
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PK | Unique transaction ID |
| `account_id`| UUID | FK | Account involved |
| `tenant_id` | UUID | FK | Tenant ID for RLS masking |
| `amount` | DECIMAL | NOT NULL | Amount (pos/neg) |
| `ide_key` | VARCHAR | UNIQUE | Idempotency Key |
| `status` | VARCHAR | | PENDING, SUCCESS, FAILED |
| `created_at`| TIMESTAMP | | |

*Note: Both tables enforce Row-Level Security policy matching the `tenant_id` present in the current user's JWT context.*

## 4. API Endpoints (Core Ledger)

### POST `/api/v1/transactions/debit`
* **Headers**: `Authorization: Bearer <JWT>`, `Idempotency-Key: <UUID>`
* **Body**: `{ "accountId": "uuid", "amount": 100.50 }`
* **Response**: `200 OK`, `409 Conflict (Duplicate Key)`, `400 Bad Request (Insufficient Funds)`

### POST `/api/v1/transactions/credit`
* **Headers**: `Authorization: Bearer <JWT>`, `Idempotency-Key: <UUID>`
* **Body**: `{ "accountId": "uuid", "amount": 100.50 }`
* **Response**: `200 OK`

### GET `/api/v1/accounts/{accountId}/balance`
* **Headers**: `Authorization: Bearer <JWT>`
* **Response**: `200 OK`, Body: `{ "balance": 1500.00 }`

## 5. Data Flow
1. Client sends request to API Gateway with JWT and Idempotency Key.
2. Gateway verifies JWT via Keycloak and forwards request to Ledger Service.
3. Ledger Service intercepts request, sets DB context `tenant_id` (for RLS enforcement).
4. Ledger attempts to acquire Redis Distributed Lock on `accountId`.
5. If lock acquired, checks Idempotency Key. If new, proceeds to execute transaction logic.
6. DB updates; Debezium captures the transaction and streams it to Kafka.
7. Audit Service reads from Kafka and writes to NFS.
8. Ledger Service dispatches a notification event to RabbitMQ. If it fails, DLQ and retries handle it.
