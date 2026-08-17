// AMD FidelityFX SUPER RESOLUTION [FSR 1] ::: SPATIAL SCALING — GLSL 330 port
//
// Upstream: GPUOpen-Effects/FidelityFX-FSR
// Package: FSR 1.0.2   Header: ffx_fsr1.h v1.20210629
// Commit:  a21ffb8f6c13233ba336352bdff293894c706575
// License: MIT  Copyright (c) 2021 Advanced Micro Devices, Inc.
//
// This file is a HLSL/portable-header → GLSL port of the official 32-bit
// FsrEasuF / FsrRcasF path and the APrx* helpers from ffx_a.h. It is not
// copied from Sodium, Iris, or any other Minecraft mod.
//
// textureGather is emulated with clamped texelFetch so the shader stays on
// GLSL 330 (Minecraft 26.2 / Ultima) and screen edges clamp instead of wrap.

#ifndef ULTIMA_FSR1_GLSL
#define ULTIMA_FSR1_GLSL

#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0))

float ultima_fsr_rcp(float a) {
    return 1.0 / a;
}

float ultima_fsr_sat(float a) {
    return clamp(a, 0.0, 1.0);
}

float ultima_fsr_prx_lo_rcp(float a) {
    return uintBitsToFloat(0x7ef07ebbu - floatBitsToUint(a));
}

float ultima_fsr_prx_med_rcp(float a) {
    float b = uintBitsToFloat(0x7ef19fffu - floatBitsToUint(a));
    return b * (-b * a + 2.0);
}

float ultima_fsr_prx_lo_rsq(float a) {
    return uintBitsToFloat(0x5f347d74u - (floatBitsToUint(a) >> 1u));
}

float ultima_fsr_min3(float x, float y, float z) {
    return min(x, min(y, z));
}

float ultima_fsr_max3(float x, float y, float z) {
    return max(x, max(y, z));
}

ivec2 ultima_fsr_clamp_texel(ivec2 p, ivec2 sizeMinusOne) {
    return clamp(p, ivec2(0), sizeMinusOne);
}

// OpenGL textureGather component order: (i0,j1), (i1,j1), (i1,j0), (i0,j0)
// with CLAMP_TO_EDGE. Matches the official A_GLSL gather callbacks.
vec4 ultima_fsr_gather(sampler2D source, vec2 p, int comp) {
    ivec2 size = textureSize(source, 0);
    ivec2 sizeMinusOne = size - ivec2(1);
    vec2 unnorm = p * vec2(size) - vec2(0.5);
    ivec2 base = ivec2(floor(unnorm));
    float x = texelFetch(source, ultima_fsr_clamp_texel(base + ivec2(0, 1), sizeMinusOne), 0)[comp];
    float y = texelFetch(source, ultima_fsr_clamp_texel(base + ivec2(1, 1), sizeMinusOne), 0)[comp];
    float z = texelFetch(source, ultima_fsr_clamp_texel(base + ivec2(1, 0), sizeMinusOne), 0)[comp];
    float w = texelFetch(source, ultima_fsr_clamp_texel(base, sizeMinusOne), 0)[comp];
    return vec4(x, y, z, w);
}

vec4 ultima_fsr_load(sampler2D source, ivec2 p) {
    ivec2 sizeMinusOne = textureSize(source, 0) - ivec2(1);
    return texelFetch(source, ultima_fsr_clamp_texel(p, sizeMinusOne), 0);
}

void ultima_fsr_easu_tap(
        inout vec3 aC,
        inout float aW,
        vec2 off,
        vec2 dir,
        vec2 len,
        float lob,
        float clp,
        vec3 c) {
    vec2 v;
    v.x = (off.x * (dir.x)) + (off.y * dir.y);
    v.y = (off.x * (-dir.y)) + (off.y * dir.x);
    v *= len;
    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);
    float wB = (2.0 / 5.0) * d2 + (-1.0);
    float wA = lob * d2 + (-1.0);
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB + (-(25.0 / 16.0 - 1.0));
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void ultima_fsr_easu_set(
        inout vec2 dir,
        inout float len,
        vec2 pp,
        bool biS,
        bool biT,
        bool biU,
        bool biV,
        float lA,
        float lB,
        float lC,
        float lD,
        float lE) {
    float w = 0.0;
    if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);
    if (biT) w = pp.x * (1.0 - pp.y);
    if (biU) w = (1.0 - pp.x) * pp.y;
    if (biV) w = pp.x * pp.y;
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = ultima_fsr_prx_lo_rcp(lenX);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = ultima_fsr_sat(abs(dirX) * lenX);
    lenX *= lenX;
    len += lenX * w;
    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = ultima_fsr_prx_lo_rcp(lenY);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = ultima_fsr_sat(abs(dirY) * lenY);
    lenY *= lenY;
    len += lenY * w;
}

vec3 ultima_fsr_easu(sampler2D source, uvec2 ip, vec4 con0, vec4 con1, vec4 con2, vec4 con3) {
    vec2 pp = vec2(ip) * con0.xy + con0.zw;
    vec2 fp = floor(pp);
    pp -= fp;
    vec2 p0 = fp * con1.xy + con1.zw;
    vec2 p1 = p0 + con2.xy;
    vec2 p2 = p0 + con2.zw;
    vec2 p3 = p0 + con3.xy;
    vec4 bczzR = ultima_fsr_gather(source, p0, 0);
    vec4 bczzG = ultima_fsr_gather(source, p0, 1);
    vec4 bczzB = ultima_fsr_gather(source, p0, 2);
    vec4 ijfeR = ultima_fsr_gather(source, p1, 0);
    vec4 ijfeG = ultima_fsr_gather(source, p1, 1);
    vec4 ijfeB = ultima_fsr_gather(source, p1, 2);
    vec4 klhgR = ultima_fsr_gather(source, p2, 0);
    vec4 klhgG = ultima_fsr_gather(source, p2, 1);
    vec4 klhgB = ultima_fsr_gather(source, p2, 2);
    vec4 zzonR = ultima_fsr_gather(source, p3, 0);
    vec4 zzonG = ultima_fsr_gather(source, p3, 1);
    vec4 zzonB = ultima_fsr_gather(source, p3, 2);
    vec4 bczzL = bczzB * 0.5 + (bczzR * 0.5 + bczzG);
    vec4 ijfeL = ijfeB * 0.5 + (ijfeR * 0.5 + ijfeG);
    vec4 klhgL = klhgB * 0.5 + (klhgR * 0.5 + klhgG);
    vec4 zzonL = zzonB * 0.5 + (zzonR * 0.5 + zzonG);
    float bL = bczzL.x;
    float cL = bczzL.y;
    float iL = ijfeL.x;
    float jL = ijfeL.y;
    float fL = ijfeL.z;
    float eL = ijfeL.w;
    float kL = klhgL.x;
    float lL = klhgL.y;
    float hL = klhgL.z;
    float gL = klhgL.w;
    float oL = zzonL.z;
    float nL = zzonL.w;
    vec2 dir = vec2(0.0);
    float len = 0.0;
    ultima_fsr_easu_set(dir, len, pp, true, false, false, false, bL, eL, fL, gL, jL);
    ultima_fsr_easu_set(dir, len, pp, false, true, false, false, cL, fL, gL, hL, kL);
    ultima_fsr_easu_set(dir, len, pp, false, false, true, false, fL, iL, jL, kL, nL);
    ultima_fsr_easu_set(dir, len, pp, false, false, false, true, gL, jL, kL, lL, oL);
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = ultima_fsr_prx_lo_rsq(dirR);
    dirR = zro ? 1.0 : dirR;
    dir.x = zro ? 1.0 : dir.x;
    dir *= vec2(dirR);
    len = len * 0.5;
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) * ultima_fsr_prx_lo_rcp(max(abs(dir.x), abs(dir.y)));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 + (-0.5) * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = ultima_fsr_prx_lo_rcp(lob);
    vec3 min4 = min(
            min(vec3(ijfeR.z, ijfeG.z, ijfeB.z), min(vec3(klhgR.w, klhgG.w, klhgB.w), vec3(ijfeR.y, ijfeG.y, ijfeB.y))),
            vec3(klhgR.x, klhgG.x, klhgB.x));
    vec3 max4 = max(
            max(vec3(ijfeR.z, ijfeG.z, ijfeB.z), max(vec3(klhgR.w, klhgG.w, klhgB.w), vec3(ijfeR.y, ijfeG.y, ijfeB.y))),
            vec3(klhgR.x, klhgG.x, klhgB.x));
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    ultima_fsr_easu_tap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, vec3(bczzR.x, bczzG.x, bczzB.x));
    ultima_fsr_easu_tap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, vec3(bczzR.y, bczzG.y, bczzB.y));
    ultima_fsr_easu_tap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, vec3(ijfeR.x, ijfeG.x, ijfeB.x));
    ultima_fsr_easu_tap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, vec3(ijfeR.y, ijfeG.y, ijfeB.y));
    ultima_fsr_easu_tap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, vec3(ijfeR.z, ijfeG.z, ijfeB.z));
    ultima_fsr_easu_tap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, vec3(ijfeR.w, ijfeG.w, ijfeB.w));
    ultima_fsr_easu_tap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, vec3(klhgR.x, klhgG.x, klhgB.x));
    ultima_fsr_easu_tap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, vec3(klhgR.y, klhgG.y, klhgB.y));
    ultima_fsr_easu_tap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, vec3(klhgR.z, klhgG.z, klhgB.z));
    ultima_fsr_easu_tap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, vec3(klhgR.w, klhgG.w, klhgB.w));
    ultima_fsr_easu_tap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, vec3(zzonR.z, zzonG.z, zzonB.z));
    ultima_fsr_easu_tap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, vec3(zzonR.w, zzonG.w, zzonB.w));
    return min(max4, max(min4, aC * vec3(ultima_fsr_rcp(aW))));
}

vec3 ultima_fsr_rcas(sampler2D source, uvec2 ip, float sharpnessLinear) {
    ivec2 sp = ivec2(ip);
    vec3 b = ultima_fsr_load(source, sp + ivec2(0, -1)).rgb;
    vec3 d = ultima_fsr_load(source, sp + ivec2(-1, 0)).rgb;
    vec3 e = ultima_fsr_load(source, sp).rgb;
    vec3 f = ultima_fsr_load(source, sp + ivec2(1, 0)).rgb;
    vec3 h = ultima_fsr_load(source, sp + ivec2(0, 1)).rgb;
    float bR = b.r;
    float bG = b.g;
    float bB = b.b;
    float dR = d.r;
    float dG = d.g;
    float dB = d.b;
    float eR = e.r;
    float eG = e.g;
    float eB = e.b;
    float fR = f.r;
    float fG = f.g;
    float fB = f.b;
    float hR = h.r;
    float hG = h.g;
    float hB = h.b;
    float bL = bB * 0.5 + (bR * 0.5 + bG);
    float dL = dB * 0.5 + (dR * 0.5 + dG);
    float eL = eB * 0.5 + (eR * 0.5 + eG);
    float fL = fB * 0.5 + (fR * 0.5 + fG);
    float hL = hB * 0.5 + (hR * 0.5 + hG);
    float mn4R = min(ultima_fsr_min3(bR, dR, fR), hR);
    float mn4G = min(ultima_fsr_min3(bG, dG, fG), hG);
    float mn4B = min(ultima_fsr_min3(bB, dB, fB), hB);
    float mx4R = max(ultima_fsr_max3(bR, dR, fR), hR);
    float mx4G = max(ultima_fsr_max3(bG, dG, fG), hG);
    float mx4B = max(ultima_fsr_max3(bB, dB, fB), hB);
    vec2 peakC = vec2(1.0, -4.0);
    float hitMinR = min(mn4R, eR) * ultima_fsr_rcp(4.0 * mx4R);
    float hitMinG = min(mn4G, eG) * ultima_fsr_rcp(4.0 * mx4G);
    float hitMinB = min(mn4B, eB) * ultima_fsr_rcp(4.0 * mx4B);
    float hitMaxR = (peakC.x - max(mx4R, eR)) * ultima_fsr_rcp(4.0 * mn4R + peakC.y);
    float hitMaxG = (peakC.x - max(mx4G, eG)) * ultima_fsr_rcp(4.0 * mn4G + peakC.y);
    float hitMaxB = (peakC.x - max(mx4B, eB)) * ultima_fsr_rcp(4.0 * mn4B + peakC.y);
    float lobeR = max(-hitMinR, hitMaxR);
    float lobeG = max(-hitMinG, hitMaxG);
    float lobeB = max(-hitMinB, hitMaxB);
    float lobe = max(-FSR_RCAS_LIMIT, min(ultima_fsr_max3(lobeR, lobeG, lobeB), 0.0)) * sharpnessLinear;
    float rcpL = ultima_fsr_prx_med_rcp(4.0 * lobe + 1.0);
    return vec3(
            (lobe * bR + lobe * dR + lobe * hR + lobe * fR + eR) * rcpL,
            (lobe * bG + lobe * dG + lobe * hG + lobe * fG + eG) * rcpL,
            (lobe * bB + lobe * dB + lobe * hB + lobe * fB + eB) * rcpL);
}

#endif
