# Telesphorepo clients

Build-tool clients for [Telesphorepo](https://github.com/Telesphorepo/Telesphorepo), a
self-hosted artifact repository. A write to a Telesphorepo repository needs a **publish
session**: a short-lived token that the server gives for one repository. Passwords and read
tokens can only read. These clients get a session for you, so no long-lived password is in a
build or in CI.

| Client | Coordinates | Directory |
|---|---|---|
| Gradle settings plugin | plugin id `com.telesphorepo.publish` | [`gradle/`](gradle/README.md) |
| Maven core extension | `com.telesphorepo:telesphorepo-maven-extension` | [`maven/`](maven/README.md) |
| Maven plugin (`login` goal, optional) | `com.telesphorepo:telesphorepo-maven-plugin` | [`maven/`](maven/README.md) |

On a developer's computer, the first publish asks you to approve the computer in the browser.
The client prints the link, opens it, waits for the approval and then publishes. Later publishes
use the saved device key.

In CI, the clients use the CI's identity token (OIDC). An admin must add a **CI pipeline** on
the server's **Settings › CI pipelines** page. The pipeline names the CI, the jobs and the
repositories.

## Get the clients

Releases are on the Gradle Plugin Portal (the Gradle plugin) and on Maven Central (the Maven
extension and plugin). Builds find them with no extra repository.

Snapshots of `main` are on the Maven Central snapshot repository
`https://central.sonatype.com/repository/maven-snapshots/` (all clients, kept 90 days). Gradle,
in `settings.gradle.kts`, before the `plugins` block:

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
        gradlePluginPortal()
    }
}
```

Maven, for a snapshot of the extension: add the repository to `~/.m2/settings.xml` in an active
profile. A core extension resolves from the repositories of `settings.xml`, not from `pom.xml`:

```xml
<profiles>
  <profile>
    <id>telesphorepo-snapshots</id>
    <repositories>
      <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases><enabled>false</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
      </repository>
    </repositories>
    <pluginRepositories>
      <pluginRepository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases><enabled>false</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
      </pluginRepository>
    </pluginRepositories>
  </profile>
</profiles>
<activeProfiles><activeProfile>telesphorepo-snapshots</activeProfile></activeProfiles>
```

The project's Nexus also has every release and snapshot:
`https://nexus.telesphoreo.me/repository/gradle-plugins-releases/` and
`https://nexus.telesphoreo.me/repository/gradle-plugins-snapshots/`.

## Build

JDK 17 is required. The wrappers download Gradle and Maven.

```sh
cd gradle && ./gradlew build   # the Gradle plugin
cd maven && ./mvnw package     # the Maven client library, extension and plugin
```

## Release

Push a tag `v<version>`, for example `v0.1.0`. CI publishes both clients with the version of the tag.
GitHub secrets: environment `Nexus` (variable `NEXUS_USERNAME`, secret `NEXUS_PASSWORD`), `PluginPortal`
(`GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`) and `Central`
(`CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`).

## Tests

The [Telesphorepo server repository](https://github.com/Telesphorepo/Telesphorepo) tests these
clients in its `clients` e2e zone (`go tool task test clients`). The zone builds the clients from
a clone of this repository (or from `E2E_CLIENTS_DIR`) and uses them against a real server with
real Gradle and Maven builds. `E2E_CLIENTS_REF` picks the branch or tag.

## CI without a client

Get a session with two `curl` calls, then use its token as the HTTP Basic password (any
username). The examples use `jq`.

### GitHub Actions

The job needs `permissions: id-token: write`.

```sh
OIDC=$(curl -sSf -H "Authorization: bearer $ACTIONS_ID_TOKEN_REQUEST_TOKEN" \
  "$ACTIONS_ID_TOKEN_REQUEST_URL&audience=https://repo.acme.com" | jq -r .value)
SESSION=$(curl -sSf https://repo.acme.com/api/publish/sessions/oidc \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$OIDC\",\"repo\":\"releases\"}")
TOKEN=$(echo "$SESSION" | jq -r .token)

curl -sSf -u "ci:$TOKEN" -T app-1.0.jar \
  https://repo.acme.com/maven/releases/com/acme/app/1.0/app-1.0.jar

# End the session when the publish is done.
curl -sSf -X DELETE -u "ci:$TOKEN" \
  "https://repo.acme.com/api/publish/sessions/$(echo "$SESSION" | jq -r .id)"
```

### Jenkins

Install the **OpenID Connect Provider** plugin and add an "OpenID Connect id token" credential
with the ID `telesphorepo-id-token` and the audience `https://repo.acme.com`.

```groovy
withCredentials([string(credentialsId: 'telesphorepo-id-token', variable: 'OIDC_TOKEN')]) {
  sh '''
    set +x
    SESSION=$(curl -sSf https://repo.acme.com/api/publish/sessions/oidc \
      -H 'Content-Type: application/json' \
      -d "{\\"token\\":\\"$OIDC_TOKEN\\",\\"repo\\":\\"releases\\"}")
    TOKEN=$(echo "$SESSION" | jq -r .token)
    ID=$(echo "$SESSION" | jq -r .id)
    trap 'curl -sSf -X DELETE -u "ci:$TOKEN" "https://repo.acme.com/api/publish/sessions/$ID"' EXIT
    curl -sSf -u "ci:$TOKEN" -T app-1.0.jar \
      https://repo.acme.com/maven/releases/com/acme/app/1.0/app-1.0.jar
  '''
}
```

Jenkins prints each command of an `sh` step. `set +x` keeps the session token out of the build
log. The `trap` ends the session also when the upload fails.

With the Gradle plugin, set `oidcTokenEnv = "OIDC_TOKEN"` instead and run the publish inside
`withCredentials`. With the Maven extension, run `mvn -B deploy -Dtelesphorepo.oidcTokenEnv=OIDC_TOKEN`
inside it.

A session ends by itself after `publish.session_ttl` (15 minutes by default).

## License

MIT (SPDX: `MIT`), see [LICENSE](LICENSE). The server is AGPL-3.0 and is in its own repository.
