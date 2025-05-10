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
    jvm()
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
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}