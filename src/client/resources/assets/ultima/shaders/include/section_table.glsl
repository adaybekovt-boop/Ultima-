#version 330

layout(std140) uniform UltimaSectionBatch {
    ivec4 UltimaSections[256];
};

int ultima_section_index() {
    int drawId = 0;
    int baseInstance = 0;
#if defined(GL_ARB_shader_draw_parameters)
    drawId = gl_DrawIDARB;
    baseInstance = gl_BaseInstanceARB;
#else
    drawId = gl_DrawID;
    baseInstance = gl_BaseInstance;
#endif
    return drawId != 0 ? drawId : baseInstance;
}

ivec3 ultima_section_origin() {
    return UltimaSections[ultima_section_index()].xyz;
}

float ultima_section_visibility() {
    return intBitsToFloat(UltimaSections[ultima_section_index()].w);
}
