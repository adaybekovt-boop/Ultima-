package dev.ultima.scenario;

import java.util.List;

/** The scenario suite. Arenas are far enough apart that no scenario can touch another. */
final class Scenarios {
    private Scenarios() {
    }

    static List<Scenario> all() {
        return List.of(
                new CollisionScenario("collision_positive", 96, 96, 11),
                new CollisionScenario("collision_negative", -224, -224, 23),
                new CollisionScenario("collision_chunk_edge", 8, -120, 37),
                new EntityQueryScenario("entity_query", 320, 320),
                new HopperScenario("hoppers", 480, 0),
                new RecipeScenario("recipes"),
                new TagStateScenario("tags_and_states", 0, 480));
    }
}
