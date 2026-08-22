# Architecture

```text
LiquidCopy desktop launcher
  -> installs official 1.21.11_unobfuscated metadata as LiquidCopy-1.21.11
  -> installs the version-pinned bootstrap as a local launcher library
  -> official launcher supplies account authentication and game assets
      -> -javaagent: LiquidCopy Bootstrap
          -> exact 1.21.11 class/hash contracts + narrow ASM hook bridge
              -> official named 1.21.11 client
                  -> ClientKernel
                      -> EventBus (priority ordered, cancellable events)
                      -> ModuleRegistry (built-ins + ServiceLoader providers)
                      -> ConfigStore (atomic, versioned JSON)
                      -> Input / Click GUI / HUD
```

Each module owns immutable metadata, a typed setting list, and lifecycle hooks.
The registry is the only component allowed to enable, disable, or bind modules,
which makes config loading and shutdown deterministic. Failed modules are
isolated: the registry records the failure and disables only that module.

The Minecraft integration stays in the `dev.liquidcopy.client` tree. The public
API and core module/settings system avoid game types and can be unit-tested
without starting the game. The bootstrap targets only the official, named
1.21.11 client and adds narrow tick/HUD/render callbacks. Hook contracts and the
downloaded client are hash-pinned, so a different Minecraft build is rejected
instead of being patched heuristically. There is no general-purpose mod loader,
mapping/remapping stage, or dependency on Fabric.

Configuration is written to `config/liquidcopy/profile.json` using a temporary
file plus atomic move. Unknown module/setting keys survive load/save round trips
and are ignored by the current runtime, allowing forward-compatible add-ons.
