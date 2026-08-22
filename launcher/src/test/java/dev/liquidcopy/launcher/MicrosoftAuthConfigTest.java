package dev.liquidcopy.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MicrosoftAuthConfigTest {
    @Test
    void usesConsumersAuthorityAndRequiredDesktopScopes() {
        MicrosoftAuthConfig config = new MicrosoftAuthConfig("registered-client-id");

        assertEquals("login.microsoftonline.com", config.authorizationEndpoint().getHost());
        assertEquals("/consumers/oauth2/v2.0/authorize", config.authorizationEndpoint().getPath());
        assertEquals("/consumers/oauth2/v2.0/token", config.tokenEndpoint().getPath());
        assertEquals("XboxLive.signin offline_access", config.scopes());
    }

    @Test
    void rejectsMissingClientId() {
        assertThrows(IllegalArgumentException.class, () -> new MicrosoftAuthConfig("  "));
    }
}
