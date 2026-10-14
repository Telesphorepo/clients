# Telesphorepo clients

Build-tool clients for [Telesphorepo](https://github.com/Telesphorepo/Telesphorepo), a
self-hosted artifact repository. A write to a Telesphorepo repository needs a **publish
session**: a short-lived token that the server gives for one repository. Passwords and read
tokens can only read. These clients get a session for you, so no long-lived password is in a
build or in CI.

| Client | Coordinates | Directory | Setup |
|---|---|---|---|
| Gradle settings plugin | plugin id `com.telesphorepo` | [`gradle/`](gradle/README.md) | `./gradlew telesphorepoLogin` once |
| Maven core extension | `com.telesphorepo:telesphorepo-maven-extension` | [`maven/`](maven/README.md) | `mvn telesphorepo:login` once |
| Maven plugin (`login` goal) | `com.telesphorepo:telesphorepo-maven-plugin` | [`maven/`](maven/README.md) | |

In CI, the clients use the CI's identity token (OIDC). An admin must add a **trust policy** on
the server's **Trust policies** page. The policy names the issuer, the claims and the
repositories.

## Get the clients

The clients are on the Nexus repositories of the project:

- Releases: `https://nexus.telesphoreo.me/repository/gradle-plugins-releases/`
- Snapshots: `https://nexus.telesphoreo.me/repository/gradle-plugins-snapshots/`

The Gradle Plugin Portal and Maven Central do not have them yet.

Gradle, in `settings.gradle.kts`, before the `plugins` block:

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://nexus.telesphoreo.me/repository/gradle-plugins-releases/")
            // credentials { ... } only when the repository needs a login to read
        }
        gradlePluginPortal()
    }
}
```

Maven, for the extension: add the repository to `~/.m2/settings.xml` in an active profile, and
declare the extension in `.mvn/extensions.xml` (see [`maven/`](maven/README.md)). A core
extension resolves from the repositories of `settings.xml`, not from `pom.xml`:

```xml
<profiles>
  <profile>
    <id>telesphorepo-clients</id>
    <repositories>
      <repository><id>telesphorepo-clients</id><url>https://nexus.telesphoreo.me/repository/gradle-plugins-releases/</url></repository>
    </repositories>
    <pluginRepositories>
      <pluginRepository><id>telesphorepo-clients</id><url>https://nexus.telesphoreo.me/repository/gradle-plugins-releases/</url></pluginRepository>
    </pluginRepositories>
  </profile>
</profiles>
<activeProfiles><activeProfile>telesphorepo-clients</activeProfile></activeProfiles>
```

The same `<pluginRepositories>` block works in `pom.xml` for the `telesphorepo:login` plugin.

## Build

JDK 17 is required.

```sh
cd gradle && gradle build     # the Gradle plugin
cd maven && mvn package       # the Maven client library, extension and plugin
```

## Releases

A tag `v<version>` (for example `v0.2.0`) runs `.github/workflows/release.yml`. The version
comes from the tag and must be final (no `-SNAPSHOT`). The workflow publishes both clients to
`https://nexus.telesphoreo.me/repository/gradle-plugins-releases/`. The repository secrets
`NEXUS_USERNAME` and `NEXUS_PASSWORD` give the credentials.

A push to `main` runs `.github/workflows/ci.yml`. It builds both clients and publishes
snapshots to `https://nexus.telesphoreo.me/repository/gradle-plugins-snapshots/`.

The Gradle build adds a publish repository when the environment variable `NEXUS_MAVEN_URL` is
set, with the credentials from `NEXUS_USERNAME` and `NEXUS_PASSWORD`. The repository is named
`release` for a final version and `snapshot` for a `-SNAPSHOT` version.

The workflow has the Plugin Portal and Maven Central jobs as comments. Enable them after
namespace verification.

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
with the audience `https://repo.acme.com`. The trust policy issuer is `<jenkins url>/oidc`.

```groovy
withCredentials([string(credentialsId: 'telesphorepo-oidc', variable: 'OIDC')]) {
  sh '''
    SESSION=$(curl -sSf https://repo.acme.com/api/publish/sessions/oidc \
      -H 'Content-Type: application/json' \
      -d "{\\"token\\":\\"$OIDC\\",\\"repo\\":\\"releases\\"}")
    TOKEN=$(echo "$SESSION" | jq -r .token)
    curl -sSf -u "ci:$TOKEN" -T app-1.0.jar \
      https://repo.acme.com/maven/releases/com/acme/app/1.0/app-1.0.jar
  '''
}
```

With the Gradle plugin, set `oidcTokenEnv = "OIDC"` instead and run the build inside
`withCredentials`.

A session ends by itself after `publish.session_ttl` (15 minutes by default).

## License

MIT (SPDX: `MIT`), see [LICENSE](LICENSE). The server is AGPL-3.0 and is in its own repository.
