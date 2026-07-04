plugins {
    java
    alias(libs.plugins.spotless) apply false
}

allprojects {
    group = "io.beacon"
    version = "0.2.0-m1-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }

    repositories {
        mavenCentral()
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"  // last stable supporting JDK 17 bytecode without surprises
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            html.required.set(true)
            xml.required.set(true)   // cheap; useful if someone later wires Codecov (out of scope here)
            csv.required.set(false)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
        // Make `./gradlew test` automatically generate the JaCoCo report so CI gets it
        // without an extra explicit `jacocoTestReport` task in the gradle invocation.
        finalizedBy(tasks.withType<JacocoReport>())
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.28.0")  // supports JDK 17–25 launcher JVMs; required because Gradle launcher may be 21+ on dev/CI machines
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.3.1")
        }
    }

    // Public-API SDK subprojects must ship clean Javadoc — -Werror catches broken
    // {@link}, missing @param/@return, and malformed HTML at PR time.
    // Internal subprojects (conformance harness, JMH benchmarks, examples) opt out:
    // no public consumers, so doc-warning fixes there are pure overhead.
    // See plan 03.1-03 + anticipated ADR-0012 for the scoping rationale.
    val publicApiSubprojects = setOf("beacon-sdk-java", "beacon-sdk-spring-adapter")
    if (project.name in publicApiSubprojects) {
        tasks.withType<Javadoc>().configureEach {
            options {
                this as StandardJavadocDocletOptions
                addBooleanOption("Werror", true)
                addBooleanOption("Xdoclint:all", true)
                // Quiet down the most common false-positive class — missing tags for
                // package-private API. We only audit public surface here.
                addStringOption("Xdoclint:-missing", "-quiet")
                encoding = "UTF-8"
                docEncoding = "UTF-8"
                charSet = "UTF-8"
                links("https://docs.oracle.com/en/java/javase/17/docs/api/")
            }
            isFailOnError = true
        }
    }
}
