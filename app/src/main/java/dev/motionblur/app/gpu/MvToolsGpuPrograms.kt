package dev.motionblur.app.gpu

import android.opengl.GLES30
import android.opengl.GLES31

/** GLES 3.1 programs for the experimental MVTools-style processor. */
internal class MvToolsGpuPrograms : AutoCloseable {
    val luma = compute(LUMA)
    val downsample = compute(DOWNSAMPLE)
    val search = compute(SEARCH)
    val validate = compute(VALIDATE)
    val sceneGate = compute(SCENE_GATE)
    val synthesis = graphics(VERTEX, SYNTHESIS)

    override fun close() {
        intArrayOf(luma, downsample, search, validate, sceneGate, synthesis).forEach(GLES30::glDeleteProgram)
    }

    private fun compute(source: String) = link(compile(GLES31.GL_COMPUTE_SHADER, source))
    private fun graphics(vertex: String, fragment: String) =
        link(compile(GLES30.GL_VERTEX_SHADER, vertex), compile(GLES30.GL_FRAGMENT_SHADER, fragment))

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("MVTools shader compilation failed: $log")
        }
        return shader
    }

    private fun link(vararg shaders: Int): Int {
        val program = GLES30.glCreateProgram()
        try {
            shaders.forEach { GLES30.glAttachShader(program, it) }
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "MVTools program link failed: ${GLES30.glGetProgramInfoLog(program)}" }
        } finally {
            shaders.forEach(GLES30::glDeleteShader)
        }
        return program
    }

    companion object {
        private const val LUMA = """
            #version 310 es
            precision highp float;
            layout(local_size_x=16, local_size_y=16) in;
            layout(binding=0) uniform sampler2D uSource;
            layout(r32f,binding=0) writeonly uniform highp image2D oLuma;
            uniform ivec2 uSize;
            void main() {
                ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,uSize))) return;
                vec3 c=texelFetch(uSource,p,0).rgb;
                imageStore(oLuma,p,vec4(dot(c,vec3(.2126,.7152,.0722))));
            }
        """

        private const val DOWNSAMPLE = """
            #version 310 es
            precision highp float;
            layout(local_size_x=16, local_size_y=16) in;
            layout(binding=0) uniform sampler2D uFine;
            layout(r32f,binding=0) writeonly uniform highp image2D oCoarse;
            uniform ivec2 uSize;
            float s(ivec2 p){ivec2 z=textureSize(uFine,0);return texelFetch(uFine,clamp(p,ivec2(0),z-1),0).r;}
            void main(){
                ivec2 p=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(p,uSize))) return;
                ivec2 q=p*2;
                // Separable 1-2-1 bilinear Super-style reduction; odd edges are clamped.
                float v=s(q)*4.0+(s(q+ivec2(-1,0))+s(q+ivec2(1,0))+s(q+ivec2(0,-1))+s(q+ivec2(0,1)))*2.0+
                    s(q+ivec2(-1,-1))+s(q+ivec2(1,-1))+s(q+ivec2(-1,1))+s(q+ivec2(1,1));
                imageStore(oCoarse,p,vec4(v/16.0));
            }
        """

        private const val SEARCH = """
            #version 310 es
            precision highp float; precision highp int;
            layout(local_size_x=8,local_size_y=8) in;
            layout(binding=0) uniform sampler2D uCurrent;
            layout(binding=1) uniform sampler2D uNeighbor;
            layout(binding=2) uniform sampler2D uSeed;
            layout(rgba32f,binding=0) writeonly uniform highp image2D oVector;
            uniform ivec2 uImageSize; uniform ivec2 uGridSize; uniform ivec2 uSeedGridSize;
            uniform int uRadius; uniform int uBlockHalfSize; uniform bool uHasSeed;
            float px(sampler2D t,ivec2 p){return texelFetch(t,clamp(p,ivec2(0),uImageSize-1),0).r;}
            float sad(ivec2 center,ivec2 d){
                float e=0.0; float samples=0.0;
                // Ultra uses a 10×10 patch; established tiers retain the accepted 8×8 footprint.
                for(int y=-5;y<5;y++) for(int x=-5;x<5;x++) {
                    if(x < -uBlockHalfSize||x >= uBlockHalfSize||y < -uBlockHalfSize||y >= uBlockHalfSize) continue;
                    e+=abs(px(uCurrent,center+ivec2(x,y))-px(uNeighbor,center+ivec2(x,y)+d));
                    samples+=1.0;
                }
                return e/max(samples,1.0);
            }
            void main(){
                ivec2 g=ivec2(gl_GlobalInvocationID.xy); if(any(greaterThanEqual(g,uGridSize))) return;
                ivec2 center=g*4+ivec2(4);
                ivec2 seedCoord=clamp((g-ivec2(1))/2,ivec2(0),uSeedGridSize-1);
                ivec2 seed=uHasSeed?ivec2(round(texelFetch(uSeed,seedCoord,0).xy*2.0)):ivec2(0);
                ivec2 best=seed; float first=sad(center,seed),bestCost=first,second=2.0;
                for(int y=-4;y<=4;y++) for(int x=-4;x<=4;x++){
                    if(abs(x)>uRadius||abs(y)>uRadius) continue;
                    ivec2 d=seed+ivec2(x,y); float cost=sad(center,d)+.00015*length(vec2(d));
                    if(cost<bestCost){second=bestCost;bestCost=cost;best=d;} else if(cost<second) second=cost;
                }
                // z/w are unavailable in RG; confidence is recomputed by validation at full resolution.
                imageStore(oVector,g,vec4(vec2(best),0,1));
            }
        """

        private const val VALIDATE = """
            #version 310 es
            precision highp float;
            layout(local_size_x=8,local_size_y=8) in;
            layout(binding=0) uniform sampler2D uBackward;
            layout(binding=1) uniform sampler2D uForward;
            layout(binding=2) uniform sampler2D uCurrent;
            layout(binding=3) uniform sampler2D uPrevious;
            layout(binding=4) uniform sampler2D uNext;
            layout(rgba16f,binding=0) writeonly uniform highp image2D oField;
            layout(rgba32f,binding=1) writeonly uniform highp image2D oEvidence;
            uniform ivec2 uGridSize; uniform ivec2 uFrameSize; uniform float uSearchDiameter;
            float l(sampler2D t,ivec2 p){return texelFetch(t,clamp(p,ivec2(0),uFrameSize-1),0).r;}
            float errorFor(sampler2D n,ivec2 c,ivec2 d){float e=0.;for(int y=-4;y<4;y++)for(int x=-4;x<4;x++)e+=abs(l(uCurrent,c+ivec2(x,y))-l(n,c+ivec2(x,y)+d));return e/64.;}
            void main(){
                ivec2 g=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(g,uGridSize)))return;
                vec2 b=texelFetch(uBackward,g,0).xy,f=texelFetch(uForward,g,0).xy;ivec2 c=g*4+ivec2(4);
                float eb=errorFor(uPrevious,c,ivec2(round(b))),ef=errorFor(uNext,c,ivec2(round(f)));
                float confidence=clamp(1.0-2.5*(eb+ef),0.,1.);
                float consistency=length(b+f)/max(uSearchDiameter,1.);
                imageStore(oField,g,vec4(b,f)); imageStore(oEvidence,g,vec4(confidence,consistency,0,1));
            }
        """

        private const val SCENE_GATE = """
            #version 310 es
            precision highp float;
            layout(local_size_x=1,local_size_y=1) in;
            layout(binding=0) uniform sampler2D uEvidence;
            layout(r32f,binding=0) writeonly uniform highp image2D oGate;
            uniform ivec2 uGridSize;
            uniform float uHardCutMinimumConfidence;
            uniform float uHardCutMaximumConsistency;
            void main(){
                float total=float(uGridSize.x*uGridSize.y);
                int stride=max(1,int(ceil(sqrt(total/4096.0))));
                float bad=0.,confidence=0.,count=0.;
                for(int y=0;y<uGridSize.y;y+=stride)for(int x=0;x<uGridSize.x;x+=stride){
                    vec2 e=texelFetch(uEvidence,ivec2(x,y),0).rg;confidence+=e.x;
                    if(e.x<uHardCutMinimumConfidence||e.y>uHardCutMaximumConsistency)bad+=1.;
                    count+=1.;
                }
                float fraction=bad/max(count,1.);float mean=confidence/max(count,1.);
                float gate=(fraction>=.75||(fraction>=.50&&mean<.12))?0.:1.;
                imageStore(oGate,ivec2(0),vec4(gate));
            }
        """

        private const val VERTEX = """
            #version 300 es
            out vec2 vUv;
            void main(){vec2 p=vec2(float((gl_VertexID&1)*2-1),float((gl_VertexID&2)-1));vUv=p*.5+.5;gl_Position=vec4(p,0,1);}
        """

        private const val SYNTHESIS = """
            #version 300 es
            precision highp float;
            uniform sampler2D uCurrent; uniform sampler2D uField; uniform sampler2D uEvidence; uniform sampler2D uSceneGate;
            uniform vec2 uFrameSize; uniform ivec2 uGridSize; uniform int uBlur256; uniform int uPel; uniform int uPrec;
            uniform float uMinimumConfidence; uniform float uMaximumConsistency; uniform int uDynamicBlur;
            in vec2 vUv; layout(location=0) out vec4 outColor; const int MAX_SAMPLES=96;
            vec4 smoothField(vec2 pixel){
                // Fixed-point MVTools expansion is smooth but must not introduce Catmull-Rom overshoot.
                // Linear interpolation between overlapping block centres removes block seams while
                // keeping every dense vector inside the range of its four source vectors.
                vec2 uv=(pixel/4.0)/vec2(uGridSize);
                vec2 halfTexel=0.5/vec2(uGridSize);
                return texture(uField,clamp(uv,halfTexel,vec2(1.0)-halfTexel));
            }
            void main(){
                vec4 base=texture(uCurrent,vUv);
                if((uDynamicBlur!=0&&texelFetch(uSceneGate,ivec2(0),0).r<.5)||uBlur256==0){outColor=base;return;}
                vec4 field=smoothField(vUv*uFrameSize);vec2 gridUv=(vUv*uFrameSize/4.)/vec2(uGridSize);
                vec2 evidence=texture(uEvidence,gridUv).rg;
                float confidenceGate=smoothstep(uMinimumConfidence*.5,uMinimumConfidence,evidence.x);
                float consistencyGate=1.-smoothstep(uMaximumConsistency,uMaximumConsistency*1.25,evidence.y);
                float localGate=uDynamicBlur!=0?confidenceGate*consistencyGate:1.0;
                float strength=float(uBlur256)/256.;vec2 back=field.xy*strength*localGate,forward=field.zw*strength*localGate;
                int bn=clamp(int(floor(max(abs(back.x),abs(back.y))*float(uPel)/float(uPrec))),0,MAX_SAMPLES);
                int fn=clamp(int(floor(max(abs(forward.x),abs(forward.y))*float(uPel)/float(uPrec))),0,MAX_SAMPLES);
                vec4 sum=base;float count=1.;
                for(int i=1;i<=MAX_SAMPLES;i++){
                    if(i<=bn){sum+=texture(uCurrent,clamp(vUv+back*(float(i)/float(max(bn,1)))/uFrameSize,vec2(0),vec2(1)));count++;}
                    if(i<=fn){sum+=texture(uCurrent,clamp(vUv+forward*(float(i)/float(max(fn,1)))/uFrameSize,vec2(0),vec2(1)));count++;}
                }
                outColor=sum/count;
            }
        """
    }
}
