// Inherits java + sourceCompatibility 21 from the root subprojects {} block.
// Dependencies are sourced through the per-consumer build.gradle.kts; this
// module is a thin shared library compiled the same way as the other shared
// utility modules.

dependencies {
    // No external deps; pure helper code reused by services that wire Kafka.
}
