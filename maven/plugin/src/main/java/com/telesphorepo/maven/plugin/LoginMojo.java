package com.telesphorepo.maven.plugin;

import com.telesphorepo.client.ClientException;
import com.telesphorepo.client.DeviceFile;
import com.telesphorepo.client.Enrollment;
import com.telesphorepo.client.Http;
import com.telesphorepo.client.Logger;
import com.telesphorepo.client.ServerIdentity;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Makes sure this computer has a Telesphorepo publishing device that the server accepts. It keeps
 * the saved device when the server still accepts it. Otherwise it prints the approval link,
 * waits until the developer approves the code in a browser, and saves the key in
 * {@code ~/.telesphorepo/<dir>/device.json}. {@code mvn deploy} with the Telesphorepo extension
 * does the same when it needs a device, so this goal is optional. Run
 * {@code mvn com.telesphorepo:telesphorepo-maven-plugin:login -Dtelesphorepo.url=https://repo.acme.com}.
 */
@Mojo(name = "login", requiresProject = false, threadSafe = true)
public class LoginMojo extends AbstractMojo {
    /** The publisher client ID of the Maven extension and plugin. */
    private static final String CLIENT = "maven";

    /** The Telesphorepo server. Falls back to the environment variable TELESPHOREPO_URL. */
    @Parameter(property = "telesphorepo.url")
    private String url;

    /** The device name the approval page and the device list show. Defaults to "&lt;user&gt;@&lt;hostname&gt;". */
    @Parameter(property = "telesphorepo.deviceName")
    private String deviceName;

    @Override
    public void execute() throws MojoExecutionException {
        String u = url != null && !url.isBlank() ? url : System.getenv("TELESPHOREPO_URL");
        if (u == null || u.isBlank()) {
            throw new MojoExecutionException("Set the server: mvn com.telesphorepo:telesphorepo-maven-plugin:login "
                    + "-Dtelesphorepo.url=https://repo.example.com");
        }
        String name = deviceName != null && !deviceName.isBlank() ? deviceName.trim() : Enrollment.defaultName();
        Logger log = new Logger() {
            @Override
            public void info(String line) {
                getLog().info(line);
            }

            @Override
            public void warn(String line) {
                getLog().warn(line);
            }

            @Override
            public void prompt(String line) {
                // Straight to stderr, so that the approval link and code show also with -q.
                System.err.println(line);
                System.err.flush();
            }
        };
        try {
            ServerIdentity server = ServerIdentity.of(u);
            Http http = new Http(server, CLIENT);
            DeviceFile device = Enrollment.accepted(http, log);
            if (device != null) {
                getLog().info("This computer is already enrolled as device " + device.deviceId() + " for " + server
                        + ". Its key is in " + DeviceFile.path(server));
                return;
            }
            Enrollment.enroll(http, CLIENT, name, log);
        } catch (ClientException e) {
            throw new MojoExecutionException("Telesphorepo login failed: " + e.getMessage(), e);
        }
    }
}
