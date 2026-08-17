#version 330

// AMD FidelityFX FSR 1 EASU — GLSL port for Ultima / Minecraft 26.2
// Upstream: GPUOpen-Effects/FidelityFX-FSR  FSR 1.0.2  ffx_fsr1.h v1.20210629
// Commit: a21ffb8f6c13233ba336352bdff293894c706575
// License: MIT  Copyright (c) 2021 Advanced Micro Devices, Inc.
// HLSL/portable-header → GLSL port. Not copied from Sodium/Iris.

#moj_import <ultima:fsr1.glsl>

uniform sampler2D InSampler;

layout(std140) uniform FsrConstants {
    vec4 Con0;
    vec4 Con1;
    vec4 Con2;
    vec4 Con3;
    vec2 InputSize;
    vec2 OutputSize;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    uvec2 ip = uvec2(gl_FragCoord.xy);
    vec3 color = ultima_fsr_easu(InSampler, ip, Con0, Con1, Con2, Con3);
    float alpha = ultima_fsr_load(InSampler, ivec2(clamp(texCoord * InputSize, vec2(0.0), InputSize - vec2(1.0)))).a;
    fragColor = vec4(color, alpha);
}
