package com.telesphorepo.client;

/**
 * A failure of the Telesphorepo client. The message is for the person who runs the build and
 * never contains a token, a key or a code.
 */
public class ClientException extends RuntimeException {
    public ClientException(String message) {
        super(message);
    }

    public ClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
