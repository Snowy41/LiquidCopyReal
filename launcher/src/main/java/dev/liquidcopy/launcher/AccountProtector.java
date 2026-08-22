package dev.liquidcopy.launcher;

import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Platform binding for the account envelope; Windows uses current-user DPAPI. */
interface AccountProtector {
    String WINDOWS_DPAPI = "windows-dpapi-current-user";
    String POSIX_OWNER_ONLY = "posix-owner-only";

    String id();

    byte[] protect(byte[] plaintext) throws IOException;

    byte[] unprotect(byte[] protectedBytes) throws IOException;

    static AccountProtector system() {
        return forOperatingSystem(System.getProperty("os.name", ""));
    }

    static AccountProtector forOperatingSystem(String osName) {
        Objects.requireNonNull(osName, "osName");
        return osName.toLowerCase(Locale.ROOT).startsWith("windows")
            ? WindowsDpapi.INSTANCE
            : PosixOwnerOnly.INSTANCE;
    }

    /** DPAPI binds ciphertext to the currently signed-in Windows account. */
    enum WindowsDpapi implements AccountProtector {
        INSTANCE;

        @Override
        public String id() {
            return WINDOWS_DPAPI;
        }

        @Override
        public byte[] protect(byte[] plaintext) throws IOException {
            Objects.requireNonNull(plaintext, "plaintext");
            try {
                return Crypt32Util.cryptProtectData(plaintext);
            } catch (RuntimeException | LinkageError exception) {
                throw new IOException("Windows DPAPI could not protect the Microsoft account session", exception);
            }
        }

        @Override
        public byte[] unprotect(byte[] protectedBytes) throws IOException {
            Objects.requireNonNull(protectedBytes, "protectedBytes");
            try {
                return Crypt32Util.cryptUnprotectData(protectedBytes);
            } catch (RuntimeException | LinkageError exception) {
                throw new IOException("Windows DPAPI could not open the Microsoft account session for this user",
                    exception);
            }
        }
    }

    /** POSIX relies on the store's owner-only directory/file modes; the envelope remains portable. */
    enum PosixOwnerOnly implements AccountProtector {
        INSTANCE;

        @Override
        public String id() {
            return POSIX_OWNER_ONLY;
        }

        @Override
        public byte[] protect(byte[] plaintext) {
            return Arrays.copyOf(plaintext, plaintext.length);
        }

        @Override
        public byte[] unprotect(byte[] protectedBytes) {
            return Arrays.copyOf(protectedBytes, protectedBytes.length);
        }
    }
}
