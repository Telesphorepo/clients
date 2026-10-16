package com.telesphorepo.client;

import java.util.Map;

/** Finds out whether the build runs in CI, and tells a CI job what it must add to publish. */
public final class Environment {
    private Environment() {
    }

    /**
     * What the messages of a build tool say.
     *
     * @param client          the publisher client ID, for example {@code gradle}
     * @param verb            the word for a publish in this tool: {@code publish} or {@code deploy}
     * @param variableSetting where a build names the variable of a CI identity token, with
     *                        {@code %s} for the variable name
     * @param command         the command that publishes, for a Jenkinsfile
     * @param repoHint        where a build names the repository, for a repository the server does not have
     */
    public record Tool(String client, String verb, String variableSetting, String command, String repoHint) {
    }

    static boolean githubActions(Map<String, String> env) {
        return "true".equalsIgnoreCase(env.get("GITHUB_ACTIONS"));
    }

    static boolean jenkins(Map<String, String> env) {
        return set(env.get("JENKINS_URL")) || set(env.get("JENKINS_HOME"));
    }

    /** True in GitHub Actions, in Jenkins, and when the variable CI is set to anything but false or 0. */
    public static boolean ci(Map<String, String> env) {
        String ci = env.get("CI");
        return githubActions(env) || jenkins(env)
                || (set(ci) && !"false".equalsIgnoreCase(ci.trim()) && !"0".equals(ci.trim()));
    }

    /** The message for a CI build that has no identity token. oidcTokenEnv may be null. */
    static String noToken(Map<String, String> env, String oidcTokenEnv, ServerIdentity server, Tool tool) {
        if (githubActions(env)) {
            return "GitHub Actions gave this job no identity token, so it cannot " + tool.verb() + " to " + server + ".\n"
                    + "Add these lines to the job in the workflow file:\n\n"
                    + "    permissions:\n"
                    + "      id-token: write\n"
                    + "      contents: read\n";
        }
        if (jenkins(env)) {
            String var = set(oidcTokenEnv) ? oidcTokenEnv : "OIDC_TOKEN";
            StringBuilder b = new StringBuilder("Jenkins gave this build no identity token, so it cannot ")
                    .append(tool.verb()).append(" to ").append(server).append(".\n");
            if (!set(oidcTokenEnv)) {
                b.append("Name the variable of the token with ").append(String.format(tool.variableSetting(), var)).append(".\n");
            }
            return b.append("Run the ").append(tool.verb()).append(" in the Jenkinsfile like this:\n\n")
                    .append("    withCredentials([string(credentialsId: 'telesphorepo-id-token', variable: '").append(var).append("')]) {\n")
                    .append("      sh '").append(tool.command()).append("'\n")
                    .append("    }\n\n")
                    .append("telesphorepo-id-token is an \"OpenID Connect id token\" credential of the Jenkins plugin ")
                    .append("OpenID Connect Provider, with the audience ").append(server).append(".\n")
                    .toString();
        }
        return "This build runs in CI (the variable CI is set), so nobody can approve this computer in a browser.\n"
                + "Give the job an OIDC identity token. On GitHub Actions, add \"permissions: id-token: write\" to the job. "
                + "On another CI, put the token in an environment variable and name that variable with "
                + String.format(tool.variableSetting(), "<variable>") + ".";
    }

    static boolean set(String s) {
        return s != null && !s.isBlank();
    }
}
