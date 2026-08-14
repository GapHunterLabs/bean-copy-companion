// Demo project for Bean Copy Companion screenshots -- NOT part of the
// plugin's own build, and the plugin's action itself never needs this
// to even compile (it only reads the two selected classes' own PSI,
// no Gradle-resolved classpath dependency detection involved). Kept
// buildable anyway for realism when opened in the runIde sandbox --
// same "real demo, not a mockup" discipline as Test Scaffold
// Companion's demo/ project.
plugins {
    java
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

// Deliberately no explicit sourceCompatibility/jvmToolchain pin -- this
// demo only needs to open cleanly in the runIde sandbox for screenshots,
// not target a specific deployment JVM. Pinning to 17 failed on this dev
// machine (no local JDK 17, no toolchain auto-provisioning configured);
// letting both compileJava/compileKotlin default to Gradle's own JVM
// avoids the mismatch without adding a download dependency.
