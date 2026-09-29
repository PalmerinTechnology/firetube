plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-JVM module: everything FireTube knows about talking to YouTube lives here, behind the
// StreamSource interface, so it can be tested (and swapped) without Android.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Live tests hit YouTube; run them explicitly with -Plive (CI does this nightly).
    if (!project.hasProperty("live")) exclude("**/*LiveTest*")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
