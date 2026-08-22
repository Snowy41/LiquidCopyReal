package dev.liquidcopy.api.module;

import java.util.Collection;

/** Fabric entrypoint contract used by third-party LiquidCopy add-ons. */
public interface ModuleProvider {
    Collection<? extends Module> createModules();
}

