plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "co.cben.dev.aiinlinereview"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build/run against PhpStorm. Bump as needed.
        phpstorm("2024.2.4")
        instrumentationTools()
        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    // Signing + publishing read credentials from the environment (set in CI secrets / locally).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(21)
}
