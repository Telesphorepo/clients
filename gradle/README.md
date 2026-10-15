# Telesphorepo Gradle plugin

A settings plugin that publishes to a Telesphorepo server without a stored secret. When a task
publishes to a Maven repository on the server, the plugin gets a publish session for that
repository. The session ends when the build ends. Requires Gradle 8.2 or later and Java 17.

## Use it

Put this at the top of `settings.gradle.kts`. Only a `pluginManagement` block can come before
it. If the file has a `plugins` block, add the `id` line to it.

```kotlin
plugins {
    id("com.telesphorepo.publish") version "0.1.0"
}

telesphorepo {
    url = "https://repo.acme.com"
    repository = "releases"
}
```

Then publish:

```sh
./gradlew publish
```

The first publish on a computer prints a line like
`Open https://repo.acme.com/#/device?code=ABCD-EFGH and approve code ABCD-EFGH` and opens the
link in your browser. Sign in, check the code and approve it with your second factor. The
publish then continues. The plugin saves the key in `~/.telesphorepo/<host>/device.json`, which
only you can read. Later publishes use it until it expires (90 days by default) or someone
revokes it on the server's **Devices** page. Then the next publish asks for an approval again.

The project needs a `group` and a `version` in its build file.

## What the plugin adds to each project

With `repository` set, each project with the `java` or `java-platform` plugin gets:

- the `maven-publish` plugin;
- the repository `telesphorepo` at `<url>/maven/<repository>`, unless the build file declares a
  repository on the server itself;
- the publication `maven` of the `java` (or `javaPlatform`) component, unless the project has a
  publication of its own. A `java-gradle-plugin` project keeps the publications that
  `java-gradle-plugin` makes.

To keep one project out of publishing, add this to its build file:

```kotlin
tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
```

Without `repository`, the plugin adds nothing. Declare the repository in `build.gradle.kts` as
usual, without credentials, and the plugin gives it a session:

```kotlin
publishing {
    repositories {
        maven {
            name = "acme"
            url = uri("https://repo.acme.com/maven/releases")
        }
    }
}
```

The plugin sets credentials only on repositories under `<url>/maven/`.

## Proof of identity

For each repository, the plugin uses the first proof that it finds:

1. **GitHub Actions.** The workflow needs `permissions: id-token: write`, and an admin adds a
   CI pipeline for the repository on the server.
2. **Another CI with OIDC, such as Jenkins.** Set `oidcTokenEnv` to the name of the environment
   variable that holds the identity token:

   ```kotlin
   telesphorepo {
       url = "https://repo.acme.com"
       repository = "releases"
       oidcTokenEnv = "OIDC_TOKEN"
   }
   ```

3. **This computer's device key.** When there is none, or the server no longer accepts it, the
   publish asks for an approval as above.

In CI (the variable `CI` is set, or GitHub Actions or Jenkins), the plugin never waits for a
browser. A publish with no identity token fails and tells you what to add to the job.

`./gradlew telesphorepoLogin` approves this computer without a publish. It keeps a device that
the server still accepts.

## Configuration cache

The plugin works with the configuration cache. A build that runs a task that publishes to the
server does not store or reuse a cache entry: the plugin sets the credentials of that task when
it runs, and a cache entry rebuilds repository credentials from Gradle properties only. All
other builds use the cache.

## Build

```sh
./gradlew build                 # in gradle/
./gradlew publishToMavenLocal   # try a local build in another project through mavenLocal()
```

## License

MIT (SPDX: `MIT`).
