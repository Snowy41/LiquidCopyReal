package dev.liquidcopy.launcher;

/** Stable stages reported while a Microsoft account is authenticated. */
public enum AuthStage {
    OPENING_BROWSER,
    WAITING_FOR_CALLBACK,
    EXCHANGING_MICROSOFT_TOKEN,
    AUTHENTICATING_XBOX,
    AUTHENTICATING_MINECRAFT,
    CHECKING_OWNERSHIP,
    FETCHING_PROFILE,
    COMPLETE
}
