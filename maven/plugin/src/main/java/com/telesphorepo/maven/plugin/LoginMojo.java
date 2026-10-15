package com.telesphorepo.maven.plugin;

import com.telesphorepo.maven.client.Api;
import com.telesphorepo.maven.client.DeviceKey;
import com.telesphorepo.maven.client.DeviceLogin;
import com.telesphorepo.maven.client.ServerUrl;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Makes sure this computer has a Telesphorepo publishing device that the server accepts. It keeps
 * the saved device when the server still accepts it. Otherwise it prints the approval link,
 * waits until the developer approves the code in a browser, and saves the key in
 * {@code ~/.telesphorepo/<host>/device.json}. {@code mvn deploy} with the Telesphorepo extension
 * does the same when it needs a device, so this goal is optional. Run
 * {@code mvn com.telesphorepo:telesphorepo-maven-plugin:login -Dtelesphorepo.url=https://repo.acme.com}.
 */
@Mojo(name = "login", requiresProject = false, threadSafe = true)
public class LoginMojo extends AbstractMojo {
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
        ServerUrl server;
        try {
            server = ServerUrl.parse(u);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        String name = deviceName != null && !deviceName.isBlank() ? deviceName.trim() : DeviceLogin.defaultName();
        Api api = new Api(server);
        try {
            DeviceKey device = DeviceLogin.accepted(api, line -> getLog().info(line));
            if (device != null) {
                getLog().info("This computer is already enrolled as device " + device.deviceId() + " for " + server
                        + ". Its key is in " + DeviceKey.file(server));
                return;
            }
            DeviceLogin.enroll(api, name, "maven", line -> getLog().info(line));
        } catch (IOException e) {
            throw new MojoExecutionException("Telesphorepo login failed: " + e.getMessage(), e);
        }
    }
}
