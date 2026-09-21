package net.irisshaders.iris.pipeline.transform.parameter;

import java.util.Map;
import java.util.Set;
import net.irisshaders.iris.helpers.Tri;

/** Exact field-shape fixture corresponding to Iris 1.11.4 Parameters. */
public abstract class Parameters {
    public final Patch patch;
    private final Map<Tri<String, TextureType, TextureStage>, String> textureMap;
    private final Set<String> textureOverrides;
    public ShaderType type;
    public String name;

    protected Parameters(
            final Patch patch,
            final Map<Tri<String, TextureType, TextureStage>, String> textureMap,
            final Set<String> textureOverrides) {
        this.patch = patch;
        this.textureMap = textureMap;
        this.textureOverrides = Set.copyOf(textureOverrides);
    }

    public enum Patch {
        SODIUM
    }

    public enum TextureType {
        TEXTURE_2D,
        TEXTURE_3D
    }

    public enum TextureStage {
        GBUFFERS_AND_SHADOW,
        COMPOSITE
    }

    public enum ShaderType {
        VERTEX,
        FRAGMENT
    }
}
