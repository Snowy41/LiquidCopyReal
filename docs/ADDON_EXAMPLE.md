# Add-on example

```java
package example.addon;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.module.ModuleProvider;
import java.util.Collection;
import java.util.List;

public final class ExampleProvider implements ModuleProvider {
    @Override
    public Collection<? extends Module> createModules() {
        return List.of(new ExampleModule());
    }
}
```

Register the provider in
`META-INF/services/dev.liquidcopy.api.module.ModuleProvider`:

```text
example.addon.ExampleProvider
```

Add-ons must be compiled specifically for LiquidCopy/Minecraft 1.21.11 and
placed on the custom version's bootstrap class path. The dynamic ClickGUI and
configuration store enumerate provider modules automatically; no GUI code or
hard-coded registry edit is required.
