package dev.motionblur.app.gpu

import android.opengl.GLES30
import android.opengl.GLES31

/** Programs are compiled once and remain resident for the processor lifetime. */
internal class GlesMotionPrograms : AutoCloseable {
    val luma = compute(LUMA_COMPUTE)
    val match = compute(MATCH_COMPUTE)
    val recovery = compute(RECOVERY_COMPUTE)
    val synthesis = graphics(VERTEX_SHADER, SYNTHESIS_FRAGMENT)

    override fun close() {
        GLES30.glDeleteProgram(luma)
        GLES30.glDeleteProgram(match)
        GLES30.glDeleteProgram(recovery)
        GLES30.glDeleteProgram(synthesis)
    }

    private fun compute(source: String): Int = link(compile(GLES31.GL_COMPUTE_SHADER, source))

    private fun graphics(vertexSource: String, fragmentSource: String): Int = link(
        compile(GLES30.GL_VERTEX_SHADER, vertexSource),
        compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource),
    )

    private fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Motion blur shader compilation failed: $log")
        }
    }

    private fun link(vararg shaders: Int): Int = GLES30.glCreateProgram().also { program ->
        try {
            shaders.forEach { GLES30.glAttachShader(program, it) }
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Motion blur program link failed: ${GLES30.glGetProgramInfoLog(program)}" }
        } finally {
            shaders.forEach { GLES30.glDeleteShader(it) }
        }
    }

    companion object {
        private const val LUMA_COMPUTE = """
            #version 310 es
            precision highp float;
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(binding = 0) uniform sampler2D uPrevious;
            layout(binding = 1) uniform sampler2D uCurrent;
            layout(binding = 2) uniform sampler2D uNext;
            layout(r32f, binding = 0) writeonly uniform highp image2D oPrevious;
            layout(r32f, binding = 1) writeonly uniform highp image2D oCurrent;
            layout(r32f, binding = 2) writeonly uniform highp image2D oNext;
            layout(r32f, binding = 3) writeonly uniform highp image2D oPreviousHalf;
            layout(r32f, binding = 4) writeonly uniform highp image2D oCurrentHalf;
            layout(r32f, binding = 5) writeonly uniform highp image2D oNextHalf;
            uniform ivec2 uSize;
            uniform ivec2 uHalfSize;

            float luma(vec3 rgb) { return dot(rgb, vec3(0.2126, 0.7152, 0.0722)); }
            float fullLuma(sampler2D source, ivec2 p) {
                return luma(texelFetch(source, clamp(p, ivec2(0), uSize - 1), 0).rgb);
            }
            float halfLuma(sampler2D source, ivec2 p) {
                ivec2 q = p * 2;
                return 0.25 * (fullLuma(source, q) + fullLuma(source, q + ivec2(1, 0)) +
                    fullLuma(source, q + ivec2(0, 1)) + fullLuma(source, q + ivec2(1, 1)));
            }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (all(lessThan(p, uSize))) {
                    imageStore(oPrevious, p, vec4(fullLuma(uPrevious, p)));
                    imageStore(oCurrent, p, vec4(fullLuma(uCurrent, p)));
                    imageStore(oNext, p, vec4(fullLuma(uNext, p)));
                }
                if (all(lessThan(p, uHalfSize))) {
                    imageStore(oPreviousHalf, p, vec4(halfLuma(uPrevious, p)));
                    imageStore(oCurrentHalf, p, vec4(halfLuma(uCurrent, p)));
                    imageStore(oNextHalf, p, vec4(halfLuma(uNext, p)));
                }
            }
        """

        private const val MATCH_COMPUTE = """
            #version 310 es
            precision highp float;
            precision highp int;
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D uPreviousLuma;
            layout(binding = 1) uniform sampler2D uCurrentLuma;
            layout(binding = 2) uniform sampler2D uNextLuma;
            layout(binding = 3) uniform sampler2D uPreviousHalf;
            layout(binding = 4) uniform sampler2D uCurrentHalf;
            layout(binding = 5) uniform sampler2D uNextHalf;
            layout(rgba16f, binding = 0) writeonly uniform highp image2D oMotion;
            layout(rgba16f, binding = 1) writeonly uniform highp image2D oConfidence;
            uniform ivec2 uFrameSize;
            uniform ivec2 uGridSize;
            uniform int uBlockSize;
            uniform int uSearchRadius;
            uniform int uCoarseStep;
            uniform int uRefinementRadius;
            uniform int uPatchRadius;

            const int MAX_COARSE_SEARCH = 12;
            const int MAX_PATCH = 2;
            const int MAX_REFINE = 2;

            float sampleLuma(sampler2D image, ivec2 p) {
                ivec2 size = textureSize(image, 0);
                return texelFetch(image, clamp(p, ivec2(0), size - 1), 0).r;
            }
            float coarseSad(sampler2D neighbor, ivec2 center, ivec2 offset) {
                float sad = 0.0;
                for (int py = -1; py <= 1; ++py) {
                    for (int px = -1; px <= 1; ++px) {
                        ivec2 q = center + ivec2(px, py);
                        sad += abs(sampleLuma(uCurrentHalf, q) - sampleLuma(neighbor, q + offset));
                    }
                }
                return sad / 9.0;
            }
            float fullSad(sampler2D neighbor, ivec2 center, ivec2 offset) {
                float sad = 0.0;
                float count = 0.0;
                for (int py = -MAX_PATCH; py <= MAX_PATCH; ++py) {
                    for (int px = -MAX_PATCH; px <= MAX_PATCH; ++px) {
                        if (abs(px) > uPatchRadius || abs(py) > uPatchRadius) continue;
                        ivec2 q = center + ivec2(px, py);
                        sad += abs(sampleLuma(uCurrentLuma, q) - sampleLuma(neighbor, q + offset));
                        count += 1.0;
                    }
                }
                return sad / max(count, 1.0);
            }
            vec3 findMotion(sampler2D neighborFull, sampler2D neighborHalf, ivec2 center) {
                int coarseRadius = (uSearchRadius + 1) / 2;
                ivec2 bestHalf = ivec2(0);
                float bestCoarseSad = 2.0;
                ivec2 centerHalf = center / 2;
                for (int y = -MAX_COARSE_SEARCH; y <= MAX_COARSE_SEARCH; ++y) {
                    for (int x = -MAX_COARSE_SEARCH; x <= MAX_COARSE_SEARCH; ++x) {
                        if (abs(x) > coarseRadius || abs(y) > coarseRadius) continue;
                        if ((x % uCoarseStep) != 0 || (y % uCoarseStep) != 0) continue;
                        float sad = coarseSad(neighborHalf, centerHalf, ivec2(x, y));
                        if (sad < bestCoarseSad) { bestCoarseSad = sad; bestHalf = ivec2(x, y); }
                    }
                }
                ivec2 seed = bestHalf * 2;
                ivec2 best = seed;
                float bestSad = fullSad(neighborFull, center, seed);
                for (int y = -MAX_REFINE; y <= MAX_REFINE; ++y) {
                    for (int x = -MAX_REFINE; x <= MAX_REFINE; ++x) {
                        if (abs(x) > uRefinementRadius || abs(y) > uRefinementRadius) continue;
                        ivec2 candidate = clamp(seed + ivec2(x, y),
                            ivec2(-uSearchRadius), ivec2(uSearchRadius));
                        float sad = fullSad(neighborFull, center, candidate);
                        if (sad < bestSad) { bestSad = sad; best = candidate; }
                    }
                }
                float confidence = clamp(1.0 - bestSad * 4.0, 0.0, 1.0);
                return vec3(vec2(best), confidence);
            }
            void main() {
                ivec2 block = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(block, uGridSize))) return;
                ivec2 center = min(block * uBlockSize + ivec2(uBlockSize / 2), uFrameSize - 1);
                vec3 previous = findMotion(uPreviousLuma, uPreviousHalf, center);
                vec3 next = findMotion(uNextLuma, uNextHalf, center);
                imageStore(oMotion, block, vec4(previous.xy, next.xy));
                imageStore(oConfidence, block, vec4(previous.z, next.z, 0.0, 1.0));
            }
        """

        private const val RECOVERY_COMPUTE = """
            #version 310 es
            precision highp float;
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D uMotion;
            layout(binding = 1) uniform sampler2D uConfidence;
            layout(binding = 2) uniform sampler2D uOldRecovery;
            layout(r32f, binding = 0) writeonly uniform highp image2D oRecovery;
            uniform ivec2 uGridSize;
            uniform float uMinimumConfidence;
            uniform float uMaximumConsistencyError;
            uniform float uHardCutMinimumConfidence;
            uniform float uHardCutMaximumConsistencyError;
            uniform float uRecoveryStep;
            uniform bool uReset;
            uniform float uSearchDiameter;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(p, uGridSize))) return;
                vec4 motion = texelFetch(uMotion, p, 0);
                vec2 confidence = texelFetch(uConfidence, p, 0).rg;
                float consistency = length(motion.xy + motion.zw) / max(uSearchDiameter, 1.0);
                bool hardCut = min(confidence.x, confidence.y) < uHardCutMinimumConfidence ||
                    consistency > uHardCutMaximumConsistencyError;
                bool mismatch = hardCut || min(confidence.x, confidence.y) < uMinimumConfidence ||
                    consistency > uMaximumConsistencyError;
                float oldGate = uReset ? 1.0 : texelFetch(uOldRecovery, p, 0).r;
                float gate = mismatch ? 0.0 : min(1.0, oldGate + uRecoveryStep);
                imageStore(oRecovery, p, vec4(gate));
            }
        """

        private const val VERTEX_SHADER = """
            #version 300 es
            out vec2 vTexCoord;
            void main() {
                vec2 position = vec2(float((gl_VertexID & 1) * 2 - 1), float((gl_VertexID & 2) - 1));
                vTexCoord = position * 0.5 + 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        private const val SYNTHESIS_FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform sampler2D uCurrent;
            uniform sampler2D uMotion;
            uniform sampler2D uRecovery;
            uniform vec2 uFrameSize;
            uniform ivec2 uGridSize;
            uniform float uStrength;
            uniform int uPel;
            uniform int uPrecision;
            in vec2 vTexCoord;
            layout(location = 0) out vec4 outColor;
            const int MAX_SAMPLES = 96;
            void main() {
                // MVFlowBlur resizes its overlapping block vectors to a smooth full-frame field.
                // Linear sampling here is the GPU equivalent; nearest block lookup causes tiles.
                vec4 bidirectional = texture(uMotion, vTexCoord);
                float gate = texture(uRecovery, vTexCoord).r;
                vec2 backwardPixels = bidirectional.xy * uStrength * gate;
                vec2 forwardPixels = bidirectional.zw * uStrength * gate;
                int backwardSamples = int(floor(max(abs(backwardPixels.x), abs(backwardPixels.y)) * float(uPel) / float(uPrecision)));
                int forwardSamples = int(floor(max(abs(forwardPixels.x), abs(forwardPixels.y)) * float(uPel) / float(uPrecision)));
                backwardSamples = clamp(backwardSamples, 0, MAX_SAMPLES);
                forwardSamples = clamp(forwardSamples, 0, MAX_SAMPLES);

                vec4 sum = texture(uCurrent, vTexCoord);
                float count = 1.0;
                for (int i = 1; i <= MAX_SAMPLES; ++i) {
                    if (i <= backwardSamples) {
                        vec2 offset = backwardPixels * (float(i) / float(backwardSamples)) / uFrameSize;
                        sum += texture(uCurrent, clamp(vTexCoord + offset, vec2(0.0), vec2(1.0)));
                        count += 1.0;
                    }
                    if (i <= forwardSamples) {
                        vec2 offset = forwardPixels * (float(i) / float(forwardSamples)) / uFrameSize;
                        sum += texture(uCurrent, clamp(vTexCoord + offset, vec2(0.0), vec2(1.0)));
                        count += 1.0;
                    }
                }
                outColor = sum / count;
            }
        """
    }
}
