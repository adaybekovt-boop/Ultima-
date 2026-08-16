uniform isamplerBuffer UltimaSectionTable;

int ultima_section_index() {
#ifdef ULTIMA_GL_DRAW_PARAMETERS
    return gl_BaseInstanceARB;
#else
    return gl_BaseInstance;
#endif
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
