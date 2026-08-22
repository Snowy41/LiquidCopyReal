package dev.liquidcopy.launcher;

import java.net.URI;
import java.util.Objects;

/** A non-secret progress update suitable for showing directly in the launcher UI. */
public record AuthProgress(AuthStage stage, String message, URI browserUri) {
    public AuthProgress {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(message, "message");
    }

    public static AuthProgress of(AuthStage stage, String message) {
        return new AuthProgress(stage, message, null);
    }
}
