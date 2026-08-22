package dev.liquidcopy.launcher;

import java.io.IOException;

/** An authentication failure with a stable, UI-safe error code. */
public final class MicrosoftAuthException extends IOException {
    private final String code;
    private final int httpStatus;

    public MicrosoftAuthException(String code, String message) {
        this(code, message, -1, null);
    }

    public MicrosoftAuthException(String code, String message, int httpStatus) {
        this(code, message, httpStatus, null);
    }

    public MicrosoftAuthException(String code, String message, Throwable cause) {
        this(code, message, -1, cause);
    }

    private MicrosoftAuthException(String code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        if (code == null || !code.matches("[a-z0-9_]{2,64}")) {
            throw new IllegalArgumentException("Invalid authentication error code");
        }
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
