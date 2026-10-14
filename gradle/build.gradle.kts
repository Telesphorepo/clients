plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.0.0"
}

group = "com.telesphorepo"

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

gradlePlugin {
    website = "https://github.com/Telesphorepo/clients/tree/main/gradle"
    vcsUrl = "https://github.com/Telesphorepo/clients"
    plugins {
        create("telesphorepo") {
            id = "com.telesphorepo"
            implementationClass = "com.telesphorepo.gradle.TelesphorepoPlugin"
            displayName = "Telesphorepo"
            description = "Publish to a Telesphorepo artifact repository with short-lived, per-publish " +
                "credentials minted from an enrolled device key or a CI OIDC token (GitHub Actions, " +
                "Jenkins). No long-lived passwords in builds or CI."
            tags = listOf("publishing", "maven", "repository", "credentials", "security", "oidc")
        }
    }
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
        // The workflows set these variables. A final version goes to the repository "release"
        // and a -SNAPSHOT version goes to the repository "snapshot", both at NEXUS_MAVEN_URL. A
        // release build publishes the plugin and its marker artifact
        // (com.telesphorepo:com.telesphorepo.gradle.plugin).
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