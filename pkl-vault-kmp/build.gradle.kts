import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "de.starfinanz.pkl.vault"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)

    jvm()

    // Single Swift-consumable artifact for iOS. The per-target frameworks are
    // combined into PklVault.xcframework (device + simulator) for SPM/Xcode;
    // see swift-adapter/Package.swift. Assemble with `assemblePklVaultXCFramework`.
    val xcframework = XCFramework("PklVault")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "PklVault"
            isStatic = true
            xcframework.add(this)
        }
    }

    // macOS framework kept for local experiments and to run native tests on the
    // CI host; not part of the iOS XCFramework.
    macosArm64 {
        binaries.framework {
            baseName = "PklVault"
            isStatic = true
        }
    }

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

// The Kotlin Multiplatform plugin auto-creates the publications (JVM jar, Apple
// klibs, and the `kotlinMultiplatform` metadata module). We only point them at
// a repository. The URL is a PLACEHOLDER for the internal Maven registry, which
// does not exist yet; until it does, `publishToMavenLocal` works for trials.
publishing {
    repositories {
        maven {
            name = "internal"
            url = uri(
                providers.gradleProperty("starfinanzMavenUrl")
                    .orElse("https://maven.internal.starfinanz.de/repository/pkl-vault"),
            )
            // Credentials are supplied by CI (e.g. -PstarfinanzMavenUsername=…).
            credentials {
                username = providers.gradleProperty("starfinanzMavenUsername").orNull
                password = providers.gradleProperty("starfinanzMavenPassword").orNull
            }
        }
    }
}
