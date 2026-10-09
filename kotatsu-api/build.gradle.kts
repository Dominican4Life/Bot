// KMK: The Kotatsu parser ABI (org.koitharu.kotatsu.parsers.*) that Kotatsu-family JAR plugins are compiled
// against, plus the Tsuki (Usagi) runtime. Copied from Kototoro's parser-api; plugin JARs load their own site
// classes and share these model / context / parser interfaces with the app.
plugins {
    id("mihon.library")
    kotlin("android")
}

android {
    namespace = "org.koitharu.kotatsu.parsers"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.ExperimentalStdlibApi",
            "-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-Xannotation-default-target=first-only",
        )
    }
}

dependencies {
    api(libs.okhttp.core)
    api(libs.jsoup)
    api(kotlinx.coroutines.core)
    api("androidx.collection:collection-ktx:1.5.0")
    api("androidx.annotation:annotation:1.9.1")
    // Usagi's Tsuki core (tsuki.*): the ABI of Tsuki/UMA plugin JARs
    api("com.github.UsagiApp:Tsuki:1.0.5")
}
