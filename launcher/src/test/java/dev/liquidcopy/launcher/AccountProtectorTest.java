package dev.liquidcopy.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountProtectorTest {
    @Test
    void selectsDpapiOnlyForWindowsAndNeverForDarwin() {
        assertEquals(AccountProtector.WINDOWS_DPAPI,
            AccountProtector.forOperatingSystem("Windows 11").id());
        assertEquals(AccountProtector.POSIX_OWNER_ONLY,
            AccountProtector.forOperatingSystem("Darwin").id());
        assertEquals(AccountProtector.POSIX_OWNER_ONLY,
            AccountProtector.forOperatingSystem("Mac OS X").id());
        assertEquals(AccountProtector.POSIX_OWNER_ONLY,
            AccountProtector.forOperatingSystem("Linux").id());
    }
}
