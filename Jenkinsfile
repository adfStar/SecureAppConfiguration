// Declarative pipeline for building the pkl-vault KMP library.
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
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
            archiveArtifacts artifacts: '**/build/libs/*.jar', allowEmptyArchive: true, fingerprint: true
        }
    }
}
