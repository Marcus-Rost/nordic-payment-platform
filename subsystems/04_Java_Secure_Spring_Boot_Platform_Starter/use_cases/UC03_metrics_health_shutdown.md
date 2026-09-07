# UC-03: Prometheus Metrics, Health Checks, and Graceful Shutdown

*Components: MetricsAutoConfiguration, HealthCheckAutoConfiguration, GracefulShutdownAutoConfiguration*

---

## Scenario 3A: Custom Payment Metrics Automatically Exposed

### Preconditions
- Starter on classpath, Prometheus on the network

### Main Success Scenario

| Step | Action | Result |
|------|--------|--------|
| 1 | Application starts | MetricsAutoConfiguration registers custom `MeterBinder` implementations |
| 2 | Prometheus scrapes `/actuator/prometheus` | Response includes standard Spring Boot metrics PLUS custom payment metrics |
| 3 | Custom metrics available | `nordpay_kafka_consumer_lag{topic="payment.instructions", group="clearing-engine"}`, `nordpay_jvm_gc_pause_seconds`, `nordpay_db_pool_active_connections{pool="HikariPool-1"}` |
| 4 | Application code uses `PaymentMetrics` helper | `paymentMetrics.recordTransaction("INSTANT", "SETTLED", Duration.ofMillis(42))` → increments counter + records timer |

### Grafana Dashboard

The starter ships a `grafana-dashboard.json` that visualizes:
- Request rate and latency (p50, p95, p99)
- Kafka consumer lag per topic
- DB connection pool utilization
- JVM memory and GC pauses
- Payment transaction counter by type and status

---

## Scenario 3B: Kubernetes Readiness Probe with Dependency Check

### Preconditions
- Application deployed in Kubernetes
- PostgreSQL database is a dependency

### Main Success Scenario

| Step | Action | Result |
|------|--------|--------|
| 1 | K8s sends: `GET /actuator/health/readiness` | Starter evaluates all readiness indicators |
| 2 | PostgreSQL is available, Kafka is available | Returns `{"status": "UP", "components": {"db": {"status": "UP"}, "kafka": {"status": "UP"}}}` with HTTP 200 |
| 3 | K8s marks pod as ready | Traffic is routed to the pod |

### Alternative: Database Down

| Step | Action | Result |
|------|--------|--------|
| 1 | K8s sends readiness probe | PostgreSQL health indicator fails (connection timeout) |
| 2 | Returns `{"status": "DOWN", "components": {"db": {"status": "DOWN", "details": {"error": "Connection refused"}}}}` with HTTP 503 |
| 3 | K8s marks pod as NOT ready | Traffic stops being routed to this pod. Other healthy pods handle requests |

---

## Scenario 3C: Graceful Shutdown During Deployment

### Preconditions
- Application is processing requests
- Kubernetes sends SIGTERM (rolling deployment)

### Main Success Scenario

| Step | Time | Action |
|------|------|--------|
| 1 | T+0s | SIGTERM received. Spring Boot initiates graceful shutdown |
| 2 | T+0s | Starter logs: `"Graceful shutdown initiated. Stopping new request acceptance."` |
| 3 | T+0s | Server stops accepting NEW connections |
| 4 | T+0-30s | In-flight requests continue to completion. Starter logs: `"Draining 3 in-flight requests..."` |
| 5 | T+Ns | Last in-flight request completes |
| 6 | T+Ns | Kafka consumers commit offsets and close |
| 7 | T+Ns | Database connections returned to pool, pool closes |
| 8 | T+Ns | Starter logs: `"Graceful shutdown complete. All resources released."` |
| 9 | T+Ns | JVM exits with code 0 |

### Alternative: Timeout Exceeded

| Step | Time | Action |
|------|------|--------|
| 4a | T+30s | Shutdown timeout (30s) exceeded with 1 request still in-flight |
| 5a | T+30s | Starter logs WARNING: `"Forced shutdown. 1 request(s) interrupted."` |
| 6a | T+30s | Force-close remaining connections. JVM exits |

---

## Scenario 3D: Kafka Consumer Lag Health Warning

### Preconditions
- Application consumes from Kafka topic `payment.instructions`
- Lag threshold configured at 1,000 messages

### Main Success Scenario

| Step | Action | Result |
|------|--------|--------|
| 1 | Kafka lag = 50 messages | Health indicator: UP. Metric: `nordpay_kafka_consumer_lag = 50` |
| 2 | Kafka lag spikes to 5,000 | Health indicator: WARN (still UP but degraded). Prometheus metric alerts via Grafana |
| 3 | Operator sees Grafana alert | Investigates: slow processing, consumer restart needed, or upstream burst |

---

## Security Considerations
- `/actuator/health/liveness` and `/actuator/health/readiness` are public (no auth) — K8s probes need them
- `/actuator/prometheus` is public (Prometheus needs to scrape without auth)
- ALL other actuator endpoints (`/actuator/env`, `/actuator/beans`, `/actuator/configprops`) require authentication and are disabled by default
- Health check details (db connection strings, Kafka broker addresses) are hidden from unauthenticated users (`management.endpoint.health.show-details=when-authorized`)
