package dev.ultima.config;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates every Ultima Mixin on its owning module being enabled, so a single problematic optimization
 * can be switched off without disabling the mod.
 */
public final class UltimaMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("ultima-mixin");
    private static final String MIXIN_PACKAGE = ".mixin.";

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        String module = moduleOf(mixinClassName);
        if (module == null) {
            return true;
        }

        boolean enabled = UltimaConfig.get().isEnabled(module);
        if (!enabled) {
            LOGGER.info("Skipping {} because the '{}' module is disabled.", mixinClassName, module);
        }

        return enabled;
    }

    private static @org.jspecify.annotations.Nullable String moduleOf(final String mixinClassName) {
        int packageStart = mixinClassName.indexOf(MIXIN_PACKAGE);
        if (packageStart < 0) {
            return null;
        }

        int moduleStart = packageStart + MIXIN_PACKAGE.length();
        int moduleEnd = mixinClassName.indexOf('.', moduleStart);
        return moduleEnd < 0 ? null : mixinClassName.substring(moduleStart, moduleEnd);
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
