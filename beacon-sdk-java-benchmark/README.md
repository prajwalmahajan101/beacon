# beacon-sdk-java-benchmark

JMH benchmark subproject measuring `BeaconSdk.emit` hot-path overhead (PRD NFR-6 / JSDK-10). **Not shipped as a runtime artifact** — local + CI execution only.

## Run

Full run (5×1s warmup, 10×1s measurement, 2 forks, AverageTime + SampleTime):

```bash
./gradlew :beacon-sdk-java-benchmark:jmh
```

CI mode (1 fork, 3 warmup, 5 measurement — faster, lower-resolution):

```bash
./gradlew :beacon-sdk-java-benchmark:jmh -PbenchmarkCI
```

## Output

- `build/results/jmh/results.json` — machine-readable, percentile distribution + averages.
- `build/reports/jmh/results.txt` — human-readable JMH summary table.

## Published results

The latest baseline (workload + hardware + p50/p95/p99/p999) lives at [`../docs/benchmarks/sdk-overhead.md`](../docs/benchmarks/sdk-overhead.md).

## NOT shipped

This subproject pulls in JMH (Apache-2.0 / GPL-classpath) for measurement tooling. It is deliberately a sibling of `:beacon-sdk-java` (not a sourceSet inside it) so the JMH dependency never enters the published SDK artifact. `./gradlew build` does not run `:jmh`; that task is executed manually or by an out-of-band CI workflow only.
