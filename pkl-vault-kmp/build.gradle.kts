plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "de.starfinanz.pkl.vault"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)

    jvm()

    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    // Shares the Vault core across all Apple native targets under src/nativeMain.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.pkl.config.kotlin)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
