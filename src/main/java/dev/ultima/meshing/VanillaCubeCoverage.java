package dev.ultima.meshing;

import java.util.List;

/**
 * Diagnosis of vanilla 26.2 {@code assets/minecraft/blockstates} against the
 * mesher fast-path rule. This is a catalog, not an allow-list: production
 * admission is baked-quad geometry per {@code BlockState}, never this table.
 *
 * <p>Minecraft 26.2 bakes the variants map <em>per BlockState</em>. Facing /
 * axis / lit cubes such as furnace and oak_log are already
 * {@code SingleVariant} at {@code BlockStateModelSet.get(state)}. The hardware
 * {@code NOT_SINGLE_VARIANT} spike is <em>not</em> those property cubes — it
 * is {@code WeightedVariants} of still-cubic models (stone, dirt, deepslate,
 * sand, …) plus multipart.
 */
public final class VanillaCubeCoverage {
    private VanillaCubeCoverage() {
    }

    /**
     * Weighted random rotations/mirrors whose every alternative resolves to a
     * parent cube ({@code cube_all}, {@code cube_column}, {@code cube_mirrored},
     * {@code cube_bottom_top}, …). Geometry is a unit cube; UVs depend on
     * {@code BlockState.getSeed(pos)}. Safe to fast-path by caching <em>every</em>
     * alternative and picking with vanilla's weighted RNG.
     */
    public static final List<String> WEIGHTED_UNIT_CUBE_BLOCKS = List.of(
            "bedrock",
            "black_concrete_powder",
            "blue_concrete_powder",
            "brown_concrete_powder",
            "cyan_concrete_powder",
            "deepslate",
            "dirt",
            "gray_concrete_powder",
            "green_concrete_powder",
            "infested_deepslate",
            "infested_stone",
            "light_blue_concrete_powder",
            "light_gray_concrete_powder",
            "lime_concrete_powder",
            "magenta_concrete_powder",
            "mycelium",
            "netherrack",
            "orange_concrete_powder",
            "pink_concrete_powder",
            "podzol",
            "purple_concrete_powder",
            "red_concrete_powder",
            "red_sand",
            "rooted_dirt",
            "sand",
            "sculk",
            "stone",
            "white_concrete_powder",
            "yellow_concrete_powder");

    /**
     * Property-keyed cubes that bake to {@code SingleVariant} per BlockState
     * (furnace facing/lit, logs on axis, glazed terracotta, barrels, …).
     * Already eligible under the original SingleVariant rule; the cache keys
     * by BlockState identity so north vs south furnace are distinct entries.
     */
    public static final List<String> PER_STATE_SINGLE_VARIANT_CUBE_EXAMPLES = List.of(
            "furnace",
            "blast_furnace",
            "smoker",
            "oak_log",
            "oak_wood",
            "basalt",
            "bone_block",
            "hay_block",
            "black_glazed_terracotta",
            "barrel",
            "dispenser",
            "observer",
            "carved_pumpkin",
            "jack_o_lantern",
            "loom",
            "bee_nest",
            "beehive",
            "redstone_lamp",
            "tnt");

    /**
     * Weighted models that are <em>not</em> 6-quad unit cubes (grass overlay,
     * dirt path, lily pad, …). Remain vanilla fallback.
     */
    public static final List<String> WEIGHTED_NON_CUBE_BLOCKS = List.of(
            "dirt_path",
            "grass_block",
            "lily_pad",
            "sea_pickle",
            "turtle_egg");

    /**
     * Multipart definitions. Geometry is connected-state / layered. Remain
     * vanilla fallback even when a particular state happens to look cubic.
     */
    public static final List<String> MULTIPART_EXAMPLES = List.of(
            "oak_fence",
            "oak_wall",
            "glass_pane",
            "iron_bars",
            "redstone_wire",
            "chorus_plant",
            "mushroom_stem",
            "brown_mushroom_block",
            "bamboo",
            "tripwire");
}
