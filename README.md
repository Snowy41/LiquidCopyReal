# LiquidCopy for Minecraft 1.21.11

LiquidCopy is an independently implemented, loader-free custom client version
for Minecraft Java Edition **1.21.11 only**. It uses Mojang's official
`1.21.11_unobfuscated` client as the modern MCP-style development/runtime base,
then applies a small, version-pinned Java-agent bridge. It is not a Fabric,
Forge, or NeoForge mod and it does not use a `mods` folder.

It does **not** redistribute Minecraft. The launcher installs a dedicated
`LiquidCopy-1.21.11` version profile into an existing official Minecraft
Launcher directory, keeps its data in an isolated game directory, and opens the
official launcher for Microsoft account authentication.

## Build

Requirements: Java 21. The Gradle wrapper downloads the pinned Gradle 9.5.1.

```powershell
.\gradlew.bat clean test build releaseZip
```

Artifacts:

- `bootstrap/build/libs/*-all.jar` — the version-pinned Java-agent/bootstrap library.
- `launcher/build/libs/*-all.jar` — self-contained Swing installer/launcher.
- `build/distributions/LiquidCopy-1.21.11-*.zip` — complete release.

## Install / launch

1. Build or extract the release ZIP.
2. Run `java -jar LiquidCopy-Launcher.jar`.
3. Select the Minecraft directory, click **Install / Repair**, then click
   **Open Minecraft Launcher**.
4. In the official launcher choose the installation named
   **LiquidCopy 1.21.11** and press Play.

The default click-GUI key is **Right Shift**. Module state and typed settings
are persisted in the isolated instance's `config/liquidcopy/profile.json`.
The GUI can toggle every discovered module, edit booleans/numbers/enums/colors,
and capture or clear individual module keybinds.

Headless install/repair and verification are also available:

```powershell
java -jar LiquidCopy-Launcher.jar --install
java -jar LiquidCopy-Launcher.jar --verify
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
