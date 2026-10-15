package com.telesphorepo.maven.client;

import java.util.Map;

/** Finds out whether the build runs in CI, and tells a CI job what it must add to deploy. */
public final class Ci {
    private Ci() {
    }

    static boolean githubActions(Map<String, String> env) {
        return "true".equalsIgnoreCase(env.get("GITHUB_ACTIONS"));
    }

    static boolean jenkins(Map<String, String> env) {
        return set(env.get("JENKINS_URL")) || set(env.get("JENKINS_HOME"));
    }

    /** True in GitHub Actions, in Jenkins, and when the variable CI is set to anything but false or 0. */
    public static boolean detected(Map<String, String> env) {
        String ci = env.get("CI");
        return githubActions(env) || jenkins(env)
                || (set(ci) && !"false".equalsIgnoreCase(ci.trim()) && !"0".equals(ci.trim()));
    }

    /** The message for a CI build that has no identity token. oidcTokenEnv may be null. */
    static String noToken(Map<String, String> env, String oidcTokenEnv, ServerUrl server) {
        if (githubActions(env)) {
            return "GitHub Actions gave this job no identity token, so it cannot deploy to " + server + ".\n"
                    + "Add these lines to the job in the workflow file:\n\n"
                    + "    permissions:\n"
                    + "      id-token: write\n"
                    + "      contents: read\n";
        }
        if (jenkins(env)) {
            String var = set(oidcTokenEnv) ? oidcTokenEnv : "OIDC_TOKEN";
            return "Jenkins gave this build no identity token, so it cannot deploy to " + server + ".\n"
                    + "Run the deploy in the Jenkinsfile like this:\n\n"
                    + "    withCredentials([string(credentialsId: 'telesphorepo-id-token', variable: '" + var + "')]) {\n"
                    + "      sh 'mvn -B deploy -Dtelesphorepo.oidcTokenEnv=" + var + "'\n"
                    + "    }\n\n"
                    + "telesphorepo-id-token is an \"OpenID Connect id token\" credential of the Jenkins plugin "
                    + "OpenID Connect Provider, with the audience " + server + ".\n";
        }
        return "This build runs in CI (the variable CI is set), so nobody can approve this computer in a browser.\n"
                + "Give the job an OIDC identity token. On GitHub Actions, add \"permissions: id-token: write\" to the job. "
                + "On another CI, put the token in an environment variable and name that variable with "
                + "-Dtelesphorepo.oidcTokenEnv=<variable>.";
    }

    private static boolean set(String s) {
        return s != null && !s.isBlank();
    }
}
