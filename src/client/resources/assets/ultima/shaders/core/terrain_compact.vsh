#version 330

#extension GL_ARB_shader_draw_parameters : enable

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#moj_import <ultima:section_table.glsl>

// Compact terrain vertex: RGBA16_UNORM position (xyz), RGBA8 color, RG16_UNORM UV0, RG16_SINT UV2.
// packed = round((local + 32) * 1024); UNORM decode is packed/65535.
in vec4 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
flat out float UltimaChunkVisibility;

void main() {
    vec3 local = Position.xyz * (65535.0 / 1024.0) - 32.0;
    vec3 pos = local + (ultima_section_origin() - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    UltimaChunkVisibility = ultima_section_visibility();
}
