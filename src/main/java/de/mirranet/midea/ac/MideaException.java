package de.mirranet.midea.ac;

import java.io.IOException;

/**
 * Something went wrong talking to a device. Extends {@link IOException} so callers can treat it
 * like any other network error and only catch the subclasses they care about.
 */
public class MideaException extends IOException {

    private static final long serialVersionUID = 1L;

    public MideaException(String message) {
        super(message);
    }

    public MideaException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The V3 handshake failed. Retrying won't help; the token or key is wrong, or the unit was
     * re-paired in the app and has new credentials.
     */
    public static final class Authentication extends MideaException {
        private static final long serialVersionUID = 1L;

        public Authentication(String message) {
            super(message);
        }
    }

    /** A packet arrived that could not be decoded or whose signature didn't match. */
    public static final class Protocol extends MideaException {
        private static final long serialVersionUID = 1L;

        public Protocol(String message) {
            super(message);
        }
    }

    /** The device didn't answer the basic status query in time. */
    public static final class Timeout extends MideaException {
        private static final long serialVersionUID = 1L;

        public Timeout(String message) {
            super(message);
        }
    }
}
