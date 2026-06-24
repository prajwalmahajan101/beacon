plugins {
    java
    alias(libs.plugins.jmh)
}

description = "Public SDK overhead benchmark (JMH). Not shipped — local + CI execution only. See docs/benchmarks/sdk-overhead.md."

dependencies {
    jmh(project(":beacon-sdk-java"))
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.generator.annprocess)
}

jmh {
    warmupIterations.set(5)
    iterations.set(10)
    fork.set(2)
    benchmarkMode.set(listOf("avgt", "sample"))
    timeUnit.set("ns")
    resultFormat.set("JSON")
    resultsFile.set(file("build/results/jmh/results.json"))
    // CI-friendly knob: drop fork count to 1 when -PbenchmarkCI is set.
    if (project.hasProperty("benchmarkCI")) {
        fork.set(1)
        warmupIterations.set(3)
        iterations.set(5)
    }
}
