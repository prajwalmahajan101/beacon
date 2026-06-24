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

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
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
}
