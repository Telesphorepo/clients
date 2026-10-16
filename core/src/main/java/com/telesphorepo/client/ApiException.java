package com.telesphorepo.client;

/** A Telesphorepo API call that the server answered with an error status. */
public final class ApiException extends ClientException {
    private final String route;
    private final int status;
    private final Protocol.ApiError error;

    ApiException(String route, int status, Protocol.ApiError error) {
        super(route + " failed: HTTP " + status + (error.error().isEmpty() ? "" : ": " + error.error()));
        this.route = route;
        this.status = status;
        this.error = error;
    }

    /** The method and route of the call, for example {@code POST /api/publish/sessions}. */
    public String route() {
        return route;
    }

    /** The HTTP status. */
    public int status() {
        return status;
    }

    /** The {@code error} field of the answer, or an empty string. */
    public String error() {
        return error.error();
    }
}
