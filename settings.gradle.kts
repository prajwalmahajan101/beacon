rootProject.name = "beacon"

include(":beacon-sdk-java")
include(":beacon-s0-contract:conformance:java")

project(":beacon-s0-contract:conformance:java").name = "conformance-java"
