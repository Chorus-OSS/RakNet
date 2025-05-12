plugins {
    kotlin("multiplatform") version "2.1.10"
}

group = "org.chorus_oss"
version = "1.0-SNAPSHOT"
description = "RakNet"

repositories {
    mavenCentral()
}

kotlin {
    jvm() // TODO: Fix SocketAddress issue (returns hostName instead of address)
    linuxX64()
    mingwX64()

    // TODO: Add more supported platforms
    // Open an issue if you want a platform to be added.

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.io)
                api(libs.ktor.network)
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlin.logging)
                implementation(libs.kotlinx.datetime)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.slf4j.simple)
            }
        }
    }
}