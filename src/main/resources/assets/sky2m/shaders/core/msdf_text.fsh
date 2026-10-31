#version 330
#extension GL_ARB_separate_shader_objects : require

// Sky2M's MSDF text: Minecraft's core/text fragment shader, except that the texture is a multi-channel signed
// distance field atlas (msdf-atlas-gen, MTSDF) and coverage comes from the median of its channels, as Caxton does.
// The atlas is filtered bilinearly here (texelFetch), since Minecraft samples GUI text with nearest filtering.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// The atlas's distance range in pixels (msdf-atlas-gen -pxrange).
const float PX_RANGE = 4.0;

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)

    #ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    #endif

    return color;
}

vec4 sampleBilinear(vec2 uv) {
    ivec2 size = textureSize(Sampler0, 0);
    vec2 p = uv * vec2(size) - 0.5;
    ivec2 i = ivec2(floor(p));
    vec2 f = fract(p);
    ivec2 lo = ivec2(0);
    ivec2 hi = size - 1;
    vec4 a = texelFetch(Sampler0, clamp(i, lo, hi), 0);
    vec4 b = texelFetch(Sampler0, clamp(i + ivec2(1, 0), lo, hi), 0);
    vec4 c = texelFetch(Sampler0, clamp(i + ivec2(0, 1), lo, hi), 0);
    vec4 d = texelFetch(Sampler0, clamp(i + ivec2(1, 1), lo, hi), 0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec4 mtsdf = sampleBilinear(texCoord0);
    float distance = median(mtsdf.r, mtsdf.g, mtsdf.b);
    // How many screen pixels one unit of distance covers, so edges stay one pixel soft at any size.
    vec2 unitRange = vec2(PX_RANGE) / vec2(textureSize(Sampler0, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(texCoord0);
    float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);
    float opacity = clamp(screenPxRange * (distance - 0.5) + 0.5, 0.0, 1.0);

    vec4 color = vec4(1.0, 1.0, 1.0, opacity) * vertexColor * ColorModulator;

    if (color.a < 0.01) {
        discard;
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
