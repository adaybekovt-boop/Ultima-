#version 330

// Fullscreen triangle matching vanilla assets/minecraft/shaders/core/screenquad.vsh.
// Used by Ultima FSR1 EASU/RCAS passes. Not an AMD algorithm file.

out vec2 texCoord;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vec4 pos = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    gl_Position = pos;
    texCoord = uv;
}
