# LiquidCopy for Minecraft 1.21.11

LiquidCopy is an independently implemented, loader-free custom client version
for Minecraft Java Edition **1.21.11 only**. It uses Mojang's official
`1.21.11_unobfuscated` client as the modern MCP-style development/runtime base,
then applies a small, version-pinned Java-agent bridge. It is not a Fabric,
Forge, or NeoForge mod and it does not use a `mods` folder.

It does **not** redistribute Minecraft. Its desktop launcher owns a dedicated
data directory, signs in through Microsoft's browser OAuth flow, downloads the
official client/libraries/assets from their declared sources, and starts the
game JVM itself. It neither installs a profile into nor hands execution to the
official Minecraft Launcher.

## Build

Build requirement: a Java 21 JDK. The Gradle wrapper downloads the pinned
Gradle 9.5.1. On Windows, `releaseZip` uses `jlink` from that toolchain to add a
stripped, compressed Java 21 runtime to the release.

```powershell
.\gradlew.bat clean test build releaseZip
```

Artifacts:

- `bootstrap/build/libs/*-all.jar` — the version-pinned Java-agent/bootstrap library.
- `launcher/build/libs/*-all.jar` — self-contained Swing installer/launcher.
- `build/distributions/LiquidCopy-1.21.11-*.zip` — complete release.

## Launch

1. Build or extract the release ZIP.
2. Run `Launch LiquidCopy.cmd` on Windows. The release uses its bundled Java 21
   runtime, so a system Java installation is not required. On macOS/Linux run
   `./launch-liquidcopy.sh`; that script explicitly requires and validates a
   system Java 21 runtime.
3. Keep or change the launcher-owned data directory and choose the maximum game
   memory.
4. Paste your registered Microsoft application (client) ID, then click
   **Sign in with Microsoft**. Authentication opens in the system browser and
   returns to LiquidCopy through a temporary loopback callback. While waiting,
   the launcher offers **Cancel sign-in** and **Copy sign-in URL**.
5. Click **Install / Update**, then **Play**. The custom launcher resolves and
   starts Minecraft directly; no other launcher is opened.

### One-time Microsoft application setup

The distributor of a LiquidCopy build must register a Microsoft Entra **public
client/native desktop application** and use its own application ID. Configure a
Mobile and desktop applications platform with `http://localhost` as a redirect
URI and enable public-client flows. The registration must also be accepted or
enabled for Xbox Live and Minecraft Services; a generic Entra registration is
not automatically sufficient. Distributors must use their own registration and
must not borrow another launcher's client ID. Paste the resulting **Application
(client) ID** into the launcher's editable field; it is saved in
`launcher-settings.json` under the selected data directory. The client ID is a
public identifier, not a client secret. LiquidCopy uses authorization-code PKCE
and never asks for a Microsoft password inside the application.

The same ID can instead be supplied with
`LIQUIDCOPY_MICROSOFT_CLIENT_ID` or
`-Dliquidcopy.microsoft.clientId=<id>`.

See [`docs/MICROSOFT_LOGIN.md`](docs/MICROSOFT_LOGIN.md) for the complete
registration, browser-login, callback, and local-session procedure.

Default launcher-owned data locations:

- Windows: `%APPDATA%\LiquidCopy`
- macOS: `~/Library/Application Support/LiquidCopy`
- Linux: `$XDG_DATA_HOME/liquidcopy` or `~/.local/share/liquidcopy`

On Windows, cached Microsoft/Minecraft access and refresh tokens are encrypted
at rest with current-user DPAPI. A session copied to another Windows account or
machine cannot be decrypted. Existing schema-1 plaintext sessions are migrated
to the protected envelope after a successful read. POSIX systems retain
owner-only directory/file permissions.

The default click-GUI key is **Right Shift**. Module state and typed settings
are persisted in the isolated instance's `config/liquidcopy/profile.json`.
The GUI can toggle every discovered module, edit booleans/numbers/enums/colors,
and capture or clear individual module keybinds.

Headless installation, authentication, verification, and direct launch are also
available:

```powershell
java -jar LiquidCopy-Launcher.jar --install
java -jar LiquidCopy-Launcher.jar --verify
java -jar LiquidCopy-Launcher.jar login
java -jar LiquidCopy-Launcher.jar play --memory 4096
java -jar LiquidCopy-Launcher.jar logout
```

## Included modules

| Category | Modules |
|---|---|
| Combat | Triggerbot, CooldownSync |
| Movement | Sprint, NoJumpDelay |
| Render | PlayerESP, StorageESP, ItemESP, Nametags, Chams, FullBright, Crosshair |

Render features use independent module settings and restore any vanilla state
they changed when disabled. A module failure is isolated by the registry and
does not take down the rest of the client.

## Module development

Implement `dev.liquidcopy.api.module.ModuleProvider` and register it through
Java's `ServiceLoader` on the custom version's bootstrap class path. LiquidCopy
discovers providers at startup without editing the kernel. Built-in modules are
small, independent classes with typed settings and lifecycle hooks.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/ADDON_EXAMPLE.md`](docs/ADDON_EXAMPLE.md).
