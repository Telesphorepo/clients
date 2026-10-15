import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    signing
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "com.telesphorepo"

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

// The plugin-publish plugin adds the sources and javadoc jars, publishes to the Gradle Plugin
// Portal with publishPlugins, and signs every publication when a signing key is configured.
gradlePlugin {
    website = "https://docs.telesphorepo.com"
    vcsUrl = "https://github.com/Telesphorepo/clients"
    plugins {
        create("telesphorepo") {
            id = "com.telesphorepo.publish"
            implementationClass = "com.telesphorepo.gradle.TelesphorepoPlugin"
            displayName = "Telesphorepo"
            description = "Publish to a Telesphorepo artifact repository with short-lived, per-publish " +
                "credentials minted from an enrolled device key or a CI OIDC token (GitHub Actions, " +
                "Jenkins). No long-lived passwords in builds or CI."
            tags = listOf("publishing", "maven", "repository", "credentials", "security", "oidc")
            compatibility {
                features {
                    // Tasks that publish to a Telesphorepo server declare that they cannot use the
                    // configuration cache. All other tasks use it.
                    configurationCache = true
                    isolatedProjects = false
                }
            }
        }
    }
}

// The signing key: in CI, the Gradle properties signingKey (ASCII-armored private key) and
// signingPassword, from ORG_GRADLE_PROJECT_signingKey and ORG_GRADLE_PROJECT_signingPassword. By
// hand, gpg with the key that signing.gnupg.keyName names. With neither, nothing is signed.
signing {
    val signingKey = providers.gradleProperty("signingKey")
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), providers.gradleProperty("signingPassword").orNull)
    } else if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
        useGpgCmd()
    }
    isRequired = false
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "Telesphorepo Gradle plugin"
            description = "Gradle settings plugin that publishes to a Telesphorepo server with short-lived publish sessions."
            url = "https://github.com/Telesphorepo/clients/tree/main/gradle"
            licenses {
                license {
                    name = "MIT"
                    url = "https://opensource.org/license/mit"
                }
            }
            developers {
                developer {
                    id = "telesphoreo"
                    name = "Telesphoreo"
                    url = "https://github.com/Telesphorepo"
                }
            }
            scm {
                connection = "scm:git:https://github.com/Telesphorepo/clients.git"
                developerConnection = "scm:git:git@github.com:Telesphorepo/clients.git"
                url = "https://github.com/Telesphorepo/clients"
            }
        }
    }
    repositories {
        // Snapshots on Maven Central: publishAllPublicationsToCentralSnapshotsRepository. The
        // Central Portal user token comes from the Gradle properties centralSnapshotsUsername and
        // centralSnapshotsPassword. The Gradle Plugin Portal takes no snapshots.
        maven {
            name = "centralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            credentials(PasswordCredentials::class)
        }
        // The workflows set these variables. A final version goes to the repository "release"
        // and a -SNAPSHOT version goes to the repository "snapshot", both at NEXUS_MAVEN_URL. A
        // release build publishes the plugin and its marker artifact
        // (com.telesphorepo.publish:com.telesphorepo.publish.gradle.plugin).
        val nexusUrl = providers.environmentVariable("NEXUS_MAVEN_URL")
        if (nexusUrl.isPresent) {
            maven {
                name = if (project.version.toString().endsWith("-SNAPSHOT")) "snapshot" else "release"
                url = uri(nexusUrl.get())
                credentials {
                    username = providers.environmentVariable("NEXUS_USERNAME").orNull
                    password = providers.environmentVariable("NEXUS_PASSWORD").orNull
                }
            }
        }
    }
}
