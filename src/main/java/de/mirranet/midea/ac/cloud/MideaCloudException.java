package de.mirranet.midea.ac.cloud;

import de.mirranet.midea.ac.MideaException;

/** The Midea cloud rejected a request. */
public class MideaCloudException extends MideaException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public MideaCloudException(int code, String message) {
        super("Midea cloud error " + code + (message == null || message.isEmpty() ? "" : ": " + message));
        this.code = code;
    }

    /** The cloud's error code, e.g. 3004 for an invalid value or 9999 for a temporary failure. */
    public int code() {
        return code;
    }
}
