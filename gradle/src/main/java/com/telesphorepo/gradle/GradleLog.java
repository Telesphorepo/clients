package com.telesphorepo.gradle;

import com.telesphorepo.client.Logger;

/** The client output on the Gradle log: prompts at the quiet level, so they show also with -q. */
final class GradleLog implements Logger {
    private final org.gradle.api.logging.Logger log;

    GradleLog(org.gradle.api.logging.Logger log) {
        this.log = log;
    }

    @Override
    public void info(String line) {
        log.lifecycle("Telesphorepo: {}", line);
    }

    @Override
    public void warn(String line) {
        log.warn("Telesphorepo: {}", line);
    }

    @Override
    public void prompt(String line) {
        log.quiet("Telesphorepo: {}", line);
    }
}
