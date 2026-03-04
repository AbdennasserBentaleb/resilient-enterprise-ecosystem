# Development Tickets

## Phase 1: The Core Application (Cloud-Native Java Developer)

**Ticket 1: Initialize Core Ledger Service Base Project**
* Start Spring Boot 3 project with Web, Data JPA, Validation, and PostgreSQL driver.
* Create `application.yml` with base environment variables for database credentials and server port.
* Create `pom.xml` / `build.gradle` and ensure Java 17 compatibility.

**Ticket 2: Containerization of Local Dependencies**
* Create `docker-compose.yml` defining PostgreSQL, Redis, and Keycloak services for local development.
* Configure initial initialization scripts (e.g., creating the initial database, setting up a realm in Keycloak).

**Ticket 3: Implement Database Schema and Entities**
* Create JPA Entities for `Account` and `Transaction`.
* Implement Liquibase or Flyway scripts to create tables and enforce constraints (including the Idempotency Key unique index).

**Ticket 4: Multi-Tenant Row-Level Security (RLS)**
* Configure PostgreSQL to enforce Row-Level Security policies based on `tenant_id`.
* Implement a Spring AOP aspect or Interceptor to extract the `tenant_id` from the security context and set it in the current database session before querying.

**Ticket 5: JWT Validation and Gateway Setup**
* Initialize the API Gateway project using Spring Cloud Gateway.
* Configure routes to the Ledger Service.
* Set up Spring Security Resource Server in the gateway to validate Keycloak JWTs.

**Ticket 6: Transaction Logic & Redis Distributed Locks**
* Implement `TransactionService` handling credits and debits.
* Integrate Redis (via Redisson or Spring Data Redis) to acquire distributed locks on the `accountId` before altering the balance.

## Phase 2: The Resilient Integration (System Integration Engineer)

**Ticket 7: Debezium and Kafka Setup**
* Update `docker-compose.yml` to include Zookeeper, Kafka, and Debezium Kafka Connect.
* Configure the Debezium connector to monitor the PostgreSQL `transactions` table.

**Ticket 8: Audit Log Service Construction**
* Initialize a new Spring Boot application for Audit Logging.
* Implement a Kafka Listener that consumes events from the Debezium topic.
* Write logic to append these events to a read-only log file on the local disk (simulating an NFS mount).

**Ticket 9: RabbitMQ Setup & Event Dispatch**
* Add RabbitMQ to `docker-compose.yml`.
* Update the Ledger Service to publish an integration event (e.g., `TransactionCompletedEvent`) to a RabbitMQ exchange upon successful transaction.

**Ticket 10: Dead Letter Queue and Resiliency Logic**
* Configure RabbitMQ DLQ for the events queue.
* Implement an Event Consumer in a dummy notification service (or within the Ledger for self-contained testing) with exponential backoff and retry mechanisms for message failures.

## Phase 3: The Infrastructure and Automation (SysAdmin / DevOps)
*(To be implemented post application completion, via configuration files and automation scripts)*

**Ticket 11: K3s Cluster Manifests**
* Generate Kubernetes Deployment, Service, ConfigMap, and Secret manifests for the Ledger Service, API Gateway, and Audit Service.

**Ticket 12: Persistent Volumes & NFS setup**
* Write Kubernetes PV and PVC definitions for PostgreSQL and Kafka relying on a theoretical NFS storage class.

**Ticket 13: CI/CD Pipeline (GitHub Actions)**
* Create `.github/workflows/main.yml`.
* Add steps for Checkout, Java 17 Setup, Maven/Gradle Test (`mvn test`), Docker Build, Trivy Image Scan, and Docker Push.

**Ticket 14: GitOps with ArgoCD**
* Define an ArgoCD Application manifest pointing to the infrastructure registry.

**Ticket 15: Observability & Chaos Monkey**
* Expand K8s manifests to include Prometheus ServiceMonitors for the Spring Boot Actuator endpoints.
* Draft a Bash/Python Chaos Monkey script to test Pod Disruption Budgets (PDB).
