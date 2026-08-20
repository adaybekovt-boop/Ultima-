package dev.ultima.mixin.recipe_match_cache;

import dev.ultima.failopen.FailOpenGuard;
import dev.ultima.recipe.RecipeCachePolicy;
import dev.ultima.recipe.RecipeFirstMatchCache;
import java.util.Optional;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches the first {@link RecipeMap#getRecipesFor} result for proven-pure inputs. Hinted lookups
 * still try the hint first unless the cached first-match is that same holder.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Shadow
    private RecipeMap recipes;

    @Unique
    private final RecipeFirstMatchCache ultimaRecipeCache = new RecipeFirstMatchCache();

    @Inject(method = "apply", at = @At("RETURN"))
    private void ultimaInvalidateRecipeCache(
            final RecipeMap recipes, final ResourceManager manager, final ProfilerFiller profiler, final CallbackInfo ci) {
        try {
            this.ultimaRecipeCache.onRecipesReplaced(RecipeCachePolicy.inspect(this.recipes));
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.RECIPE_MATCH_CACHE, "reload", error);
        }
    }

    @Inject(
            method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
            at = @At("HEAD"),
            cancellable = true)
    private void ultimaFirstMatchCache(
            final RecipeType<?> type,
            final RecipeInput input,
            final Level level,
            final CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {
        try {
            Optional<RecipeHolder<?>> cached = this.ultimaRecipeCache.lookup(type, input, true);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.RECIPE_MATCH_CACHE, type, error);
        }
    }

    @Inject(
            method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
            at = @At("RETURN"))
    private void ultimaStoreFirstMatch(
            final RecipeType<?> type,
            final RecipeInput input,
            final Level level,
            final CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {
        try {
            Optional<RecipeHolder<?>> result = cir.getReturnValue();
            if (result == null) {
                return;
            }
            this.ultimaRecipeCache.store(type, input, result);
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.RECIPE_MATCH_CACHE, type, error);
        }
    }

    @Inject(
            method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;",
            at = @At("HEAD"),
            cancellable = true)
    private void ultimaHintedFirstMatchCache(
            final RecipeType<?> type,
            final RecipeInput input,
            final Level level,
            final @Nullable RecipeHolder<?> recipeHint,
            final CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {
        if (recipeHint == null) {
            return;
        }
        try {
            Optional<RecipeHolder<?>> cached = this.ultimaRecipeCache.hintedHit(type, input, recipeHint);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        } catch (Throwable error) {
            FailOpenGuard.failOpen(FailOpenGuard.Module.RECIPE_MATCH_CACHE, type, error);
        }
    }
}
