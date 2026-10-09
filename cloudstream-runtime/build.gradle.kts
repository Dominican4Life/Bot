// KMK: The Cloudstream 3 runtime (official library:jvmJar, minus the JVM-only stubs) plus the Android shims
// Cloudstream plugins (.cs3) link against, ported from Kototoro. Cloudstream providers become anime sources.
plugins {
    id("mihon.library")
    kotlin("android")
}

android {
    namespace = "com.lagradost.cloudstream3.runtime"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=com.lagradost.cloudstream3.InternalAPI",
            "-Xannotation-default-target=first-only",
        )
    }
}

dependencies {
    api(files("libs/cloudstream3-runtime.jar", "libs/fuzzywuzzy-1.4.0.jar"))
    api(libs.okhttp.core)
    api(libs.jsoup)
    api(kotlinx.coroutines.core)
    api("com.github.Blatzar:NiceHttp:0.4.18") {
        exclude(group = "org.json", module = "json")
    }
    api("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    api("org.mozilla:rhino:1.8.1")
    api("io.ktor:ktor-http:3.5.0")
    api("dev.whyoleg.cryptography:cryptography-core:0.6.0")
    api("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
    api("com.google.code.gson:gson:2.13.2")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
