package net.irisshaders.iris.pipeline.transform.parameter;

import java.util.Map;
import java.util.Set;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.helpers.Tri;

/** Exact field-shape fixture corresponding to Iris 1.11.4 SodiumParameters. */
public final class SodiumParameters extends Parameters {
    public final AlphaTest alpha;
    public final boolean shadow;

    public SodiumParameters(
            final Map<Tri<String, TextureType, TextureStage>, String> textureMap,
            final Set<String> textureOverrides,
            final AlphaTest alpha,
            final boolean shadow) {
        super(Patch.SODIUM, textureMap, textureOverrides);
        this.alpha = alpha;
        this.shadow = shadow;
    }
}
