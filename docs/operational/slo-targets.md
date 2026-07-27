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
| Latency p50 | `histogram_quantile(0.50, sum(rate(accountshield_protection_decision_duration_seconds_bucket[5m])) by (le))` | latency |
| Latency p95 | `histogram_quantile(0.95, sum(rate(accountshield_protection_decision_duration_seconds_bucket[5m])) by (le))` | latency |
| Latency p99 | `histogram_quantile(0.99, sum(rate(accountshield_protection_decision_duration_seconds_bucket[5m])) by (le))` | latency |
| Block rate | `outcome="TEMPORARILY_BLOCK"` fraction | security posture |

`accountshield_protection_decision_duration_seconds` is a real duration `Timer` around `ProtectionDecisionApplicationService.decide()` (tagged only by the bounded `outcome` value, including `ERROR`), with explicit SLO histogram buckets at 50/100/250/500/1000/2000 ms so the quantile queries above resolve to real bucket boundaries rather than Micrometer's generic defaults (ADR 0030). Previously this row incorrectly pointed at `accountshield_protection_risk_score` -- a `DistributionSummary` over risk scores (0-100), not a duration metric at all -- which could never have produced a meaningful latency percentile.

## Alerting thresholds

| Alert | Condition | Severity |
| --- | --- | --- |
| High error rate | 5xx ratio > 1% for 5 min | critical |
| Latency degradation | p99 > 800 ms for 5 min | warning |
| Elevated block rate | block % > 40% for 10 min | warning |
| Rate limit surge | `RATE_LIMIT_EXCEEDED` responses > 100/min | warning |

## Error budget

With a 99.9% availability target over 30 days (43,200 minutes), the monthly error budget is 43.2 minutes of downtime or failed requests.
