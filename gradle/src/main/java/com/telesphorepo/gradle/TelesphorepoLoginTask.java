package com.telesphorepo.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Makes sure this computer has a device that the server accepts. It keeps the saved device when
 * the server still accepts it. Otherwise it enrolls a new one: it prints the approval link, waits
 * until the developer approves it in a browser, then saves the key in
 * {@code ~/.telesphorepo/<host>/device.json}. A publish does the same when it needs a device.
 */
@UntrackedTask(because = "Enrolls a device with the server")
public abstract class TelesphorepoLoginTask extends DefaultTask {
    @Input
    @Optional
    public abstract Property<String> getUrl();

    @TaskAction
    public void login() {
        if (!getUrl().isPresent()) {
            throw new GradleException("Set the server URL in settings.gradle(.kts): telesphorepo { url = \"https://repo.example.com\" }");
        }
        Api api = new Api(getUrl().get());
        DeviceKey device = DeviceLogin.accepted(api, getLogger());
        if (device != null) {
            getLogger().lifecycle("This computer is already enrolled as device " + device.deviceId + " for " + api.base
                + ". Its key is in " + DeviceKey.file(api.base));
            return;
        }
        DeviceLogin.enroll(api, getLogger());
    }
}
