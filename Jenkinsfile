// Declarative pipeline for building and packaging the pkl-vault KMP library.
//
// Requires a macOS agent: the KMP targets include iOS/macOS, which can only be
// compiled on an Apple host. Adjust the `agent` label to match your Jenkins
// macOS node, and ensure JDK 21 is available on that node (configured below via
// the `jdk` tool name — rename to the JDK installation configured in Jenkins).
pipeline {
    agent { label 'macos' }

    tools {
        jdk 'jdk-21'
    }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    stages {
        stage('Build & test') {
            // Produces the JVM jar + native klibs and runs the test suite.
            steps {
                sh './gradlew build --stacktrace'
            }
        }

        stage('Assemble XCFramework') {
            // PklVault.xcframework (iosArm64 + iosSimulatorArm64) for the Swift
            // adapter's SwiftPM package (swift-adapter/Package.swift).
            steps {
                sh './gradlew :pkl-vault-kmp:assemblePklVaultReleaseXCFramework --stacktrace'
            }
        }

        stage('Publish') {
            // Publishes the JVM + KMP Maven artifacts to the internal registry.
            // Runs only on release tags, and only when registry credentials are
            // provided (the registry URL/credentials are placeholders today —
            // see pkl-vault-kmp/build.gradle.kts). Supply via Jenkins credentials:
            //   -PstarfinanzMavenUrl / -PstarfinanzMavenUsername / -PstarfinanzMavenPassword
            when {
                buildingTag()
            }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'starfinanz-maven',
                    usernameVariable: 'MAVEN_USER',
                    passwordVariable: 'MAVEN_PASS',
                )]) {
                    sh '''./gradlew publishAllPublicationsToInternalRepository \
                        -PstarfinanzMavenUsername="$MAVEN_USER" \
                        -PstarfinanzMavenPassword="$MAVEN_PASS" \
                        --stacktrace'''
                }
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
            archiveArtifacts artifacts: '**/build/libs/*.jar', allowEmptyArchive: true, fingerprint: true
            archiveArtifacts(
                artifacts: 'pkl-vault-kmp/build/XCFrameworks/release/**',
                allowEmptyArchive: true,
                fingerprint: true,
            )
        }
    }
}
