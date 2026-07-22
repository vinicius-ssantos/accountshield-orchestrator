# Service Level Objectives

## Scope

AccountShield Orchestrator protection-decision API (`POST /api/v1/protection-decisions`).

## SLOs

| Objective | Target | Window |
| --- | --- | --- |
| Availability | 99.9% | 30 days |
| Decision latency (p99) | < 500 ms | rolling |
| Decision latency (p50) | < 100 ms | rolling |
| Error rate (5xx) | < 0.1% | rolling |
| Idempotency correctness | 100% | always |

## Indicators (Prometheus metrics)

| Indicator | Metric | SLO mapping |
| --- | --- | --- |
| Request rate | `rate(accountshield_protection_decisions_total[5m])` | traffic |
| Error budget burn | `1 - (1 - error_rate) / (1 - 0.999)` | availability |
| Latency percentile | histogram quantile on `accountshield_protection_risk_score` | latency |
| Block rate | `outcome="TEMPORARILY_BLOCK"` fraction | security posture |

## Alerting thresholds

| Alert | Condition | Severity |
| --- | --- | --- |
| High error rate | 5xx ratio > 1% for 5 min | critical |
| Latency degradation | p99 > 800 ms for 5 min | warning |
| Elevated block rate | block % > 40% for 10 min | warning |
| Rate limit surge | `RATE_LIMIT_EXCEEDED` responses > 100/min | warning |

## Error budget

With a 99.9% availability target over 30 days (43,200 minutes), the monthly error budget is 43.2 minutes of downtime or failed requests.
