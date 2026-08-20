package dev.ultima.mixin.recipe_match_cache;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ultima.recipe.RecipeCacheDoors;
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

/**
 * Caches the first {@link RecipeMap#getRecipesFor} result for proven-pure inputs. Hinted lookups
 * still try the hint first unless the cached first-match is that same holder.
 *
 * <p>Fail-open is {@link RecipeCacheDoors} ({@code callNullable}/{@code run}). Do not add a
 * second {@code catch}/{@code failOpen()} around those calls.
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
        RecipeCacheDoors.onRecipesReplaced(this.ultimaRecipeCache, this.recipes);
    }

    @WrapMethod(
            method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;")
    private Optional<RecipeHolder<?>> ultimaGetRecipeFor(
            final RecipeType<?> type,
            final RecipeInput input,
            final Level level,
            final Operation<Optional<RecipeHolder<?>>> original) {
        return RecipeCacheDoors.wrapGetRecipeFor(
                this.ultimaRecipeCache, type, input, () -> original.call(type, input, level));
    }

    @WrapMethod(
            method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;")
    private Optional<RecipeHolder<?>> ultimaHintedGetRecipeFor(
            final RecipeType<?> type,
            final RecipeInput input,
            final Level level,
            final @Nullable RecipeHolder<?> recipeHint,
            final Operation<Optional<RecipeHolder<?>>> original) {
        return RecipeCacheDoors.wrapHintedGetRecipeFor(
                this.ultimaRecipeCache,
                type,
                input,
                recipeHint,
                () -> original.call(type, input, level, recipeHint));
    }
}
