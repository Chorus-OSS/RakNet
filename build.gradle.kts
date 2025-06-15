import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("multiplatform") version "2.1.10"
    id("org.jetbrains.dokka") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.32.0"
}

description = "RakNet library for Kotlin Multiplatform"
group = "org.chorus-oss"
version = "0.0.1"

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
                implementation(libs.kotlin.logging)
                implementation(libs.kotlinx.datetime)
            }
        }

        val commonTest by getting

        jvmMain {
            dependencies {
                implementation(libs.slf4j.simple)
            }
        }
    }

    mavenPublishing {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
        signAllPublications()

        coordinates(
            group.toString(),
            "raknet",
            version.toString()
        )

        pom {
            name = "RakNet"
            description = project.description
            inceptionYear = "2025"
            url = "https://github.com/Chorus-OSS/RakNet"
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = "omniacdev"
                    name = "OmniacDev"
                    url = "https://github.com/OmniacDev"
                    email = "omniacdev@chorus-oss.org"
                }
            }
            scm {
                url = "https://github.com/Chorus-OSS/RakNet"
                connection = "scm:git:git://github.com/Chorus-OSS/RakNet.git"
                developerConnection = "scm:git:ssh://github.com/Chorus-OSS/RakNet.git"
            }
            issueManagement {
                system = "GitHub Issues"
                url = "https://github.com/Chorus-OSS/RakNet/issues"
            }
        }

        configure(
            KotlinMultiplatform(
                javadocJar = JavadocJar.Dokka("dokkaHtml"),
                sourcesJar = true,
                androidVariantsToPublish = emptyList(),
            )
        )
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}