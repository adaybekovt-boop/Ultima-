uniform isamplerBuffer UltimaSectionTable;

// Minecraft 26.2's shaderc Vulkan target provides the ARB-suffixed
// draw-parameter builtins when GL_ARB_shader_draw_parameters is enabled.
// The unsuffixed core names are undeclared in that compiler.
// firstInstance in the indirect command is the ViewArea slot.
int ultima_section_index() {
    return gl_BaseInstanceARB;
}

ivec4 ultima_section_record() {
    return texelFetch(UltimaSectionTable, ultima_section_index());
}

ivec3 ultima_section_origin() {
    return ultima_section_record().xyz;
}

float ultima_section_visibility() {
    return intBitsToFloat(ultima_section_record().w);
}
