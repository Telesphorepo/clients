package com.telesphorepo.client;

/** The output of the client. The Gradle plugin and the Maven extension each implement it. */
public interface Logger {
    /** A normal progress line, for example the publish session that the client minted. */
    void info(String line);

    /** A problem that does not stop the build. */
    void warn(String line);

    /**
     * A line that the person must see also in a quiet build, for example the approval link and
     * code of a device enrollment.
     */
    void prompt(String line);
}
