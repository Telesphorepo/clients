# Telesphorepo Maven extension

The extension gets a publish session from Telesphorepo before `maven-deploy-plugin` uploads, and
closes it when the build ends. `settings.xml` needs no password for the server. Requires Maven
3.9 and Java 17.

## Set up a project

Declare the extension in `.mvn/extensions.xml`:

```xml
<extensions>
  <extension>
    <groupId>com.telesphorepo</groupId>
    <artifactId>telesphorepo-maven-extension</artifactId>
    <version>0.1.0</version>
  </extension>
</extensions>
```

Name the server in `.mvn/maven.config`:

```text
-Dtelesphorepo.url=https://repo.acme.com
```

Deploy to it as usual. In `pom.xml`:

```xml
<distributionManagement>
  <repository><id>releases</id><url>https://repo.acme.com/maven/releases</url></repository>
  <snapshotRepository><id>releases</id><url>https://repo.acme.com/maven/releases</url></snapshotRepository>
</distributionManagement>
```

A `<telesphorepo.url>` property in `pom.xml` and the environment variable `TELESPHOREPO_URL`
also work. The extension handles every deployment repository whose URL is
`<telesphorepo.url>/maven/<repo>` with the same scheme, host and port,
`-DaltDeploymentRepository` and `deploy:deploy-file` included (see
[server identity](../README.md#server-identity)). Every other repository goes to Maven
unchanged, with the credentials of `settings.xml`.

For a repository on the server, a failed session request stops the deploy with the server's
message. The extension never sends the `settings.xml` credentials to such a repository.

## Publish

```sh
mvn deploy
```

The first deploy on a computer prints a line like
`Open https://repo.acme.com/#/device?code=ABCD-EFGH and approve code ABCD-EFGH` and opens the
link in your browser. Sign in, check the code and approve it with your second factor. The build
then continues. The key goes to `~/.telesphorepo/<dir>/device.json`, and only you can read it
(see [the device file](../README.md#device-file)).
The Gradle plugin uses the same file. Later deploys use it until it expires (90 days by default)
or someone revokes it on the server's **Devices** page.

The extension proves who you are, in this order:

1. GitHub Actions: the job's identity token, when `ACTIONS_ID_TOKEN_REQUEST_URL` is set. The job
   needs `permissions: id-token: write`.
2. Other CI, such as Jenkins: the identity token in the environment variable that the property
   `telesphorepo.oidcTokenEnv` names. When that variable is empty, the extension uses the device
   key.
3. The device key of this computer, approved at the first deploy.

CI needs an admin **CI pipeline** on the server. In CI (the variable `CI` is set, or GitHub
Actions or Jenkins), the extension never waits for a browser. A deploy with no identity token
stops before the build and tells you what to add to the job.

The session lasts `publish.session_ttl` (15 minutes by default). The extension gets the
session when the deploy goal of the first module starts. With `-DdeployAtEnd=true`,
`maven-deploy-plugin` keeps each module's credentials until the last module uploads, so the
whole reactor must finish within that time.

## Approve a computer without a deploy

```sh
mvn com.telesphorepo:telesphorepo-maven-plugin:login -Dtelesphorepo.url=https://repo.acme.com
```

The goal keeps a device that the server still accepts. `-Dtelesphorepo.deviceName=...` sets the
name the device list shows.

## Build the clients

```sh
./mvnw install   # in maven/
```

`telesphorepo-maven-client` holds the shared core; its sources are in `../core`.

`./mvnw deploy -DaltDeploymentRepository=release::<url>` publishes to a Maven repository such as
Nexus. The release workflow does this. `./mvnw -P release deploy` adds the sources and javadoc
jars, signs the artifacts and publishes them to Maven Central. It needs a `central` server in
`settings.xml` and a GPG key. To use a snapshot, see [`../README.md`](../README.md).

License: MIT (SPDX: `MIT`).
