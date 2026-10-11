package dev.falix.studiomobile;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 2.0 viewport with the Roblox graphics pipeline. No third-party engine.
 *
 * One frame, in order:
 *  1. sky: a streamed `Sky` cubemap when all six faces loaded, otherwise a procedural gradient with
 *     the sun disc, stars, atmosphere haze/glare and drifting clouds;
 *  2. parts, shaded by the Lighting service (see {@link EngineLighting}) with a texture pass:
 *     every face samples either its `SurfaceType` texture (Studs, Inlet, Universal, Hinge, Motor,
 *     SteppingMotor, Weld, Glue) or its `Enum.Material` texture, world-aligned at Roblox-like tile
 *     sizes, and any `Texture`/`Decal`/`SurfaceAppearance.ColorMap`/`MeshPart.TextureID` asset is
 *     streamed and layered on top;
 *  3. the transform gizmo (move arrows, rotate rings, scale handles) and the selection outline;
 *  4. an optional framebuffer post pass (Blur, Bloom, SunRays, ColorCorrection, Exposure).
 *
 * A GL-thread failure is reported to the activity instead of killing the process.
 */
final class EngineRenderer implements GLSurfaceView.Renderer {
    interface ErrorReport { void failed(String stage, Throwable error); }

    private static final int MAX_LIGHTS = EngineLighting.MAX_SHADED_LIGHTS;

    private final EngineScene scene;
    private final EngineTextures textures;
    private final ErrorReport report;
    private final ArrayList<Instance> frameList = new ArrayList<>();
    private boolean stopped;

    // ---------------------------------------------------------------- shaders
    private static final String VERT =
            "uniform mat4 uMvp; uniform mat4 uModel; uniform mat3 uNormal; attribute vec3 aPos; attribute vec3 aLocal; attribute float aFace;\n" +
            "varying vec3 vNormal; varying vec3 vWorld; varying vec3 vLocal; varying float vFace;\n" +
            // The mesh is the unit cube, so the face normal is the dominant axis of the local position.
            "vec3 localNormal(vec3 p){\n" +
            "  vec3 a = abs(p);\n" +
            "  if (a.x >= a.y && a.x >= a.z) return vec3(p.x < 0.0 ? -1.0 : 1.0, 0.0, 0.0);\n" +
            "  if (a.y >= a.z) return vec3(0.0, p.y < 0.0 ? -1.0 : 1.0, 0.0);\n" +
            "  return vec3(0.0, 0.0, p.z < 0.0 ? -1.0 : 1.0);\n" +
            "}\n" +
            "void main(){ vNormal=uNormal*localNormal(aLocal); vWorld=(uModel*vec4(aPos,1.0)).xyz; vLocal=aLocal; vFace=aFace;\n" +
            "  gl_Position=uMvp*vec4(aPos,1.0); }";

    private static final String FRAG =
            "precision mediump float;\n" +
            "varying vec3 vNormal; varying vec3 vWorld; varying vec3 vLocal; varying float vFace;\n" +
            "uniform vec3 uColor; uniform float uAlpha;\n" +
            "uniform vec3 uAmbient; uniform vec3 uSunDir; uniform vec3 uSunColor; uniform float uSunGain;\n" +
            "uniform vec3 uShiftTop; uniform vec3 uShiftBottom; uniform vec3 uEye;\n" +
            "uniform float uRough; uniform float uMetal; uniform float uEmissive; uniform float uShininess;\n" +
            "uniform float uSpecular;\n" +
            "uniform vec3 uFogColor; uniform vec2 uFogRange; uniform float uFogEnabled;\n" +
            "uniform vec3 uLightPos[4]; uniform vec3 uLightColor[4]; uniform vec3 uLightDir[4]; uniform vec4 uLightParams[4];\n" +
            "uniform int uLightCount;\n" +
            "uniform sampler2D uAtlas; uniform float uAtlasStep; uniform vec4 uFaceTiles[6];\n" +
            "uniform vec4 uSpawnTile; uniform float uIsSpawn;\n" +
            "uniform sampler2D uAsset; uniform float uHasAsset; uniform vec2 uAssetTiles; uniform float uAssetFace;\n" +
            "void main(){\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  vec2 faceUv;\n" +
            "  if (abs(n.y) >= abs(n.x) && abs(n.y) >= abs(n.z)) faceUv = vLocal.xz;\n" +
            "  else if (abs(n.x) >= abs(n.z)) faceUv = vLocal.zy;\n" +
            "  else faceUv = vLocal.xy;\n" +
            "  faceUv += 0.5;\n" +
            "  int face = int(vFace + 0.5);\n" +
            "  vec4 tile = uFaceTiles[0];\n" +
            "  if (face == 1) tile = uFaceTiles[1];\n" +
            "  else if (face == 2) tile = uFaceTiles[2];\n" +
            "  else if (face == 3) tile = uFaceTiles[3];\n" +
            "  else if (face == 4) tile = uFaceTiles[4];\n" +
            "  else if (face == 5) tile = uFaceTiles[5];\n" +
            "  bool spawnFace = uIsSpawn > 0.5 && face == 2;\n" +
            "  if (spawnFace) tile = uSpawnTile;\n" +
            "  vec2 planar = abs(n.y) >= abs(n.x) && abs(n.y) >= abs(n.z) ? vWorld.xz : (abs(n.x) >= abs(n.z) ? vWorld.zy : vWorld.xy);\n" +
            "  const float EDGE = 0.0039;\n" +
            "  vec2 cell = spawnFace ? faceUv : fract(planar * tile.z);\n" +
            "  vec2 atlasUv = tile.xy * uAtlasStep + (cell * (1.0 - 2.0 * EDGE) + EDGE) * uAtlasStep;\n" +
            "  vec3 base = uColor;\n" +
            "  if (tile.w > 0.5) base *= texture2D(uAtlas, atlasUv).rgb;\n" +
            "  float assetMask = (uAssetFace < -0.5 || abs(uAssetFace - vFace) < 0.5) ? uHasAsset : 0.0;\n" +
            "  if (assetMask > 0.0) base = mix(base, texture2D(uAsset, faceUv * uAssetTiles).rgb, assetMask);\n" +
            "  vec3 ambient = mix(uAmbient * 0.55, uAmbient, clamp(n.y * 0.5 + 0.5, 0.0, 1.0));\n" +
            "  float ndl = max(dot(n, uSunDir), 0.0);\n" +
            "  vec3 light = ambient + uSunColor * ndl * uSunGain;\n" +
            "  light += uShiftTop * ndl * 0.5 + uShiftBottom * (1.0 - ndl) * 0.5;\n" +
            "  for (int i = 0; i < 4; i++) {\n" +
            "    float used = i < uLightCount ? 1.0 : 0.0;\n" +
            "    vec3 toLight = uLightPos[i] - vWorld;\n" +
            "    float dist = length(toLight);\n" +
            "    vec3 l = toLight / max(dist, 0.0001);\n" +
            "    float atten = clamp(1.0 - dist / max(uLightParams[i].x, 0.001), 0.0, 1.0);\n" +
            "    atten *= atten;\n" +
            "    float cone = 1.0;\n" +
            "    if (uLightParams[i].y < 0.999) {\n" +
            "      float cd = dot(-l, normalize(uLightDir[i]));\n" +
            "      cone = smoothstep(uLightParams[i].y, uLightParams[i].y + (1.0 - uLightParams[i].y) * 0.35, cd);\n" +
            "    }\n" +
            "    float ndlLocal = max(dot(n, l), 0.0);\n" +
            "    light += uLightColor[i] * ndlLocal * atten * cone * used;\n" +
            "    if (uMetal > 0.5) {\n" +
            "      vec3 hv = normalize(normalize(uEye - vWorld) + l);\n" +
            "      light += uLightColor[i] * pow(max(dot(n, hv), 0.0), uShininess) * atten * cone * used * 0.5;\n" +
            "    }\n" +
            "  }\n" +
            "  vec3 view = normalize(uEye - vWorld);\n" +
            "  vec3 hv = normalize(view + uSunDir);\n" +
            "  float spec = pow(max(dot(n, hv), 0.0), uShininess) * (1.0 - uRough * 0.5);\n" +
            "  vec3 specColour = mix(vec3(0.04), base, uMetal);\n" +
            "  vec3 col = base * light * (1.0 - uMetal * 0.6) + specColour * spec * uSpecular * (uSunGain * 1.1 + 0.2)\n" +
            "           + base * uEmissive * 1.35;\n" +
            "  float viewDist = length(uEye - vWorld);\n" +
            "  float fog = uFogEnabled * clamp((viewDist - uFogRange.x) / max(uFogRange.y - uFogRange.x, 0.001), 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(mix(col, uFogColor, fog), uAlpha);\n" +
            "}";

    private static final String LINE_VERT = "uniform mat4 uMvp; attribute vec3 aPos; void main(){ gl_Position=uMvp*vec4(aPos,1.0); }";
    private static final String LINE_FRAG = "precision mediump float; uniform vec3 uColor; uniform float uAlpha; void main(){ gl_FragColor=vec4(uColor,uAlpha); }";

    private static final String QUAD_VERT = "attribute vec2 aPos; varying vec2 vUv; void main(){ vUv=aPos*0.5+0.5; gl_Position=vec4(aPos,0.0,1.0); }";

    private static final String SKY_FRAG =
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform vec3 uRayTL, uRayTR, uRayBL, uRayBR;\n" +
            "uniform vec3 uSunDir, uSunColor, uSkyTop, uSkyHorizon, uSkyGround, uAtmosphereColor, uCloudColor;\n" +
            "uniform float uSunAngular, uStarCount, uCelestial, uDayFactor;\n" +
            "uniform float uAtmoDensity, uAtmoHaze, uAtmoGlare, uAtmoOffset, uAtmoDecay;\n" +
            "uniform float uCloudCover, uCloudDensity, uCloudTime;\n" +
            "uniform samplerCube uSkyCube; uniform float uUseSkyCube;\n" +
            "float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }\n" +
            "float noise(vec2 p){ vec2 i=floor(p), f=fract(p); vec2 u=f*f*(3.0-2.0*f);\n" +
            "  return mix(mix(hash(i), hash(i+vec2(1.0,0.0)), u.x), mix(hash(i+vec2(0.0,1.0)), hash(i+vec2(1.0,1.0)), u.x), u.y); }\n" +
            "float fbm(vec2 p){ float v=0.0, a=0.5; for (int i=0;i<4;i++){ v+=a*noise(p); p*=2.03; a*=0.5; } return v; }\n" +
            "void main(){\n" +
            "  vec3 dir = normalize(mix(mix(uRayBL, uRayBR, vUv.x), mix(uRayTL, uRayTR, vUv.x), vUv.y));\n" +
            "  float h = dir.y;\n" +
            "  vec3 col = h >= 0.0 ? mix(uSkyHorizon, uSkyTop, pow(clamp(h, 0.0, 1.0), 0.55))\n" +
            "                      : mix(uSkyHorizon, uSkyGround, clamp(-h * 3.5, 0.0, 1.0));\n" +
            "  if (uUseSkyCube > 0.5) col = textureCube(uSkyCube, dir).rgb;\n" +
            "  float cs = max(dot(dir, uSunDir), 0.0);\n" +
            "  float disc = uSunAngular > 0.01 ? smoothstep(cos(radians(uSunAngular)), cos(radians(uSunAngular * 0.72)), cs) : 0.0;\n" +
            "  col += uSunColor * (disc * 1.6 + pow(cs, 90.0) * 0.5);\n" +
            "  float haze = clamp(uAtmoHaze, 0.0, 3.0) * uAtmoDensity;\n" +
            "  float band = clamp(1.0 - abs(h) * (1.6 + uAtmoOffset), 0.0, 1.0);\n" +
            "  col = mix(col, uAtmosphereColor * (0.55 + 0.45 * max(h, 0.0)), clamp(haze * band * 0.9, 0.0, 1.0));\n" +
            "  col += uAtmosphereColor * pow(cs, 8.0) * uAtmoGlare * haze * 0.8;\n" +
            "  col = mix(col, col * uAtmosphereColor * (1.0 + uAtmoDecay), clamp((1.0 - cs) * haze * 0.15, 0.0, 0.6));\n" +
            "  if (uCloudCover > 0.001 && h > 0.0) {\n" +
            "    vec2 uv = dir.xz / max(h, 0.12) * 0.16 + vec2(uCloudTime * 0.006, uCloudTime * 0.004);\n" +
            "    float mask = smoothstep(1.0 - uCloudCover, 1.0 - uCloudCover + 0.22, fbm(uv * 1.4)) * smoothstep(0.0, 0.16, h);\n" +
            "    col = mix(col, uCloudColor * (0.72 + 0.28 * fbm(uv * 1.9)), mask * uCloudDensity);\n" +
            "  }\n" +
            "  if (uUseSkyCube < 0.5 && uCelestial > 0.5 && h > 0.01 && uStarCount > 0.0) {\n" +
            "    float s = hash(floor(dir.xz / max(h, 0.08) * 60.0));\n" +
            "    col += vec3(step(1.0 - uStarCount / 400000.0, s) * (1.0 - uDayFactor) * smoothstep(0.0, 0.2, h) * 0.9);\n" +
            "  }\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}";

    private static final String BRIGHT_FRAG =
            "precision mediump float; varying vec2 vUv; uniform sampler2D uScene; uniform float uThreshold;\n" +
            "void main(){ vec3 c = texture2D(uScene, vUv).rgb; float l = dot(c, vec3(0.2126,0.7152,0.0722));\n" +
            "  gl_FragColor = vec4(c * (max(l - uThreshold, 0.0) / max(l, 0.0001)), 1.0); }";

    private static final String BLUR_FRAG =
            "precision mediump float; varying vec2 vUv; uniform sampler2D uSrc; uniform vec2 uStep;\n" +
            "void main(){ vec3 c = texture2D(uSrc, vUv).rgb * 0.227027;\n" +
            "  c += (texture2D(uSrc, vUv + uStep * 1.384615).rgb + texture2D(uSrc, vUv - uStep * 1.384615).rgb) * 0.3162162;\n" +
            "  c += (texture2D(uSrc, vUv + uStep * 3.230769).rgb + texture2D(uSrc, vUv - uStep * 3.230769).rgb) * 0.0702702;\n" +
            "  gl_FragColor = vec4(c, 1.0); }";

    private static final String RAYS_FRAG =
            "precision mediump float; varying vec2 vUv; uniform sampler2D uScene; uniform vec2 uSunUv;\n" +
            "uniform float uSpread, uIntensity;\n" +
            "void main(){ vec2 delta = (uSunUv - vUv) * (0.35 + uSpread * 0.65); vec2 uv = vUv;\n" +
            "  vec3 sum = vec3(0.0);\n" +
            "  for (int i = 0; i < 20; i++) { uv += delta / 20.0; vec3 s = texture2D(uScene, uv).rgb;\n" +
            "    sum += s * smoothstep(0.45, 1.0, dot(s, vec3(0.2126,0.7152,0.0722))); }\n" +
            "  gl_FragColor = vec4(sum / 20.0 * uIntensity * 2.0, 1.0); }";

    private static final String POST_FRAG =
            "precision mediump float; varying vec2 vUv;\n" +
            "uniform sampler2D uScene, uBloom, uRays;\n" +
            "uniform float uBloomAmount, uRaysAmount, uExposure, uCcBrightness, uCcContrast, uCcSaturation;\n" +
            "uniform vec3 uTint;\n" +
            "void main(){ vec3 c = texture2D(uScene, vUv).rgb;\n" +
            "  c += texture2D(uBloom, vUv).rgb * uBloomAmount;\n" +
            "  c += texture2D(uRays, vUv).rgb * uRaysAmount;\n" +
            "  c *= pow(2.0, uExposure);\n" +
            "  c = (c - 0.5) * (1.0 + uCcContrast) + 0.5 + uCcBrightness;\n" +
            "  float l = dot(c, vec3(0.2126,0.7152,0.0722));\n" +
            "  c = mix(vec3(l), c, 1.0 + uCcSaturation) * uTint;\n" +
            "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0); }";

    // ---------------------------------------------------------------- programs
    private int program, lineProgram, skyProgram, brightProgram, blurProgram, raysProgram, postProgram;
    private int aPos, aLocal, aFace, uMvp, uModel, uNormal, uColor, uAlpha;
    private int uAmbient, uSunDir, uSunColor, uSunGain, uShiftTop, uShiftBottom, uEye, uRough, uMetal, uEmissive, uShininess, uSpecular;
    private int uFogColor, uFogRange, uFogEnabled, uLightPos, uLightColor, uLightDir, uLightParams, uLightCount;
    private int uAtlas, uAtlasStep, uFaceTiles, uSpawnTile, uIsSpawn, uAsset, uHasAsset, uAssetTiles, uAssetFace;
    private int lPos, lMvp, lColor, lAlpha;
    private int qPos, brightPos, blurPos, raysPos, postPos;
    private int sRayTL, sRayTR, sRayBL, sRayBR, sSunDir, sSunColor, sSkyTop, sSkyHorizon, sSkyGround, sAtmosphere, sCloudColor, sSkyCube, sUseSkyCube;
    private int sSunAngular, sStarCount, sCelestial, sDayFactor, sAtmoDensity, sAtmoHaze, sAtmoGlare, sAtmoOffset, sAtmoDecay, sCloudCover, sCloudDensity, sCloudTime;
    private int bScene, bThreshold, ublSrc, ublStep, rScene, rSunUv, rSpread, rIntensity, pScene, pBloom, pRays, pBloomAmount, pRaysAmount, pExposure, pCcBrightness, pCcContrast, pCcSaturation, pTint;

    // ---------------------------------------------------------------- geometry
    private FloatBuffer cubePositions, cubeNormals, faceBuffer, gridLines, quadBuffer, gizmoBuffer;
    private ShortBuffer cubeIndices;
    private int gridVertices;
    /** 3 rotate rings x 48 segments x 2 vertices; the move axes only need 6. */
    private static final int GIZMO_LINE_VERTICES = 3 * 48 * 2;
    private final float[] gizmoScratch = new float[GIZMO_LINE_VERTICES * 3];

    private final float[] mProj = new float[16], mView = new float[16], mVp = new float[16], mModel = new float[16], mMvp = new float[16], mInvVp = new float[16];
    private final float[] normal3 = new float[9];
    private final float[] lightPos = new float[MAX_LIGHTS * 3], lightColor = new float[MAX_LIGHTS * 3];
    private final float[] lightDir = new float[MAX_LIGHTS * 3], lightParams = new float[MAX_LIGHTS * 4];
    private final float[] faceTiles = new float[6 * 4];
    private final float[] sunUv = new float[2];
    private long lastFrame;
    private int viewportWidth = 1, viewportHeight = 1;
    private volatile String postNote = "";
    private boolean postAvailable;
    private int targetWidth, targetHeight;
    private int sceneFbo, sceneTex, sceneDepth, scratchFbo, scratchTex, halfFbo, halfTex, halfFbo2, halfTex2;
    private boolean skyRequested;

    private static final float[] HEAD = {0.96f, 0.80f, 0.19f};
    private static final float[] TORSO = {0.05f, 0.41f, 0.82f};
    private static final float[] LIMB = {0.64f, 0.74f, 0.28f};

    EngineRenderer(EngineScene scene, EngineTextures textures, ErrorReport report) {
        this.scene = scene;
        this.textures = textures;
        this.report = report;
    }

    private void fail(String stage, Throwable error) {
        if (stopped) return;
        stopped = true;
        android.util.Log.e("StudioMobile", "Renderer failed while " + stage, error instanceof Exception ? (Exception) error : new RuntimeException(error));
        if (report != null) report.failed(stage, error);
    }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            GLES20.glClearColor(0.62f, 0.76f, 0.92f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glCullFace(GLES20.GL_BACK);
            GLES20.glEnable(GLES20.GL_CULL_FACE);

            program = build(shadeVertexSource(), FRAG);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aLocal = GLES20.glGetAttribLocation(program, "aLocal");
            aFace = GLES20.glGetAttribLocation(program, "aFace");
            uMvp = GLES20.glGetUniformLocation(program, "uMvp");
            uModel = GLES20.glGetUniformLocation(program, "uModel");
            uNormal = GLES20.glGetUniformLocation(program, "uNormal");
            uColor = GLES20.glGetUniformLocation(program, "uColor");
            uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
            uAmbient = GLES20.glGetUniformLocation(program, "uAmbient");
            uSunDir = GLES20.glGetUniformLocation(program, "uSunDir");
            uSunColor = GLES20.glGetUniformLocation(program, "uSunColor");
            uSunGain = GLES20.glGetUniformLocation(program, "uSunGain");
            uShiftTop = GLES20.glGetUniformLocation(program, "uShiftTop");
            uShiftBottom = GLES20.glGetUniformLocation(program, "uShiftBottom");
            uEye = GLES20.glGetUniformLocation(program, "uEye");
            uRough = GLES20.glGetUniformLocation(program, "uRough");
            uMetal = GLES20.glGetUniformLocation(program, "uMetal");
            uEmissive = GLES20.glGetUniformLocation(program, "uEmissive");
            uShininess = GLES20.glGetUniformLocation(program, "uShininess");
            uSpecular = GLES20.glGetUniformLocation(program, "uSpecular");
            uFogColor = GLES20.glGetUniformLocation(program, "uFogColor");
            uFogRange = GLES20.glGetUniformLocation(program, "uFogRange");
            uFogEnabled = GLES20.glGetUniformLocation(program, "uFogEnabled");
            uLightPos = GLES20.glGetUniformLocation(program, "uLightPos[0]");
            uLightColor = GLES20.glGetUniformLocation(program, "uLightColor[0]");
            uLightDir = GLES20.glGetUniformLocation(program, "uLightDir[0]");
            uLightParams = GLES20.glGetUniformLocation(program, "uLightParams[0]");
            uLightCount = GLES20.glGetUniformLocation(program, "uLightCount");
            uAtlas = GLES20.glGetUniformLocation(program, "uAtlas");
            uAtlasStep = GLES20.glGetUniformLocation(program, "uAtlasStep");
            uFaceTiles = GLES20.glGetUniformLocation(program, "uFaceTiles[0]");
            uSpawnTile = GLES20.glGetUniformLocation(program, "uSpawnTile");
            uIsSpawn = GLES20.glGetUniformLocation(program, "uIsSpawn");
            uAsset = GLES20.glGetUniformLocation(program, "uAsset");
            uHasAsset = GLES20.glGetUniformLocation(program, "uHasAsset");
            uAssetTiles = GLES20.glGetUniformLocation(program, "uAssetTiles");
            uAssetFace = GLES20.glGetUniformLocation(program, "uAssetFace");

            lineProgram = build(LINE_VERT, LINE_FRAG);
            lPos = GLES20.glGetAttribLocation(lineProgram, "aPos");
            lMvp = GLES20.glGetUniformLocation(lineProgram, "uMvp");
            lColor = GLES20.glGetUniformLocation(lineProgram, "uColor");
            lAlpha = GLES20.glGetUniformLocation(lineProgram, "uAlpha");

            skyProgram = build(QUAD_VERT, SKY_FRAG);
            qPos = GLES20.glGetAttribLocation(skyProgram, "aPos");
            sRayTL = GLES20.glGetUniformLocation(skyProgram, "uRayTL");
            sRayTR = GLES20.glGetUniformLocation(skyProgram, "uRayTR");
            sRayBL = GLES20.glGetUniformLocation(skyProgram, "uRayBL");
            sRayBR = GLES20.glGetUniformLocation(skyProgram, "uRayBR");
            sSunDir = GLES20.glGetUniformLocation(skyProgram, "uSunDir");
            sSunColor = GLES20.glGetUniformLocation(skyProgram, "uSunColor");
            sSkyTop = GLES20.glGetUniformLocation(skyProgram, "uSkyTop");
            sSkyHorizon = GLES20.glGetUniformLocation(skyProgram, "uSkyHorizon");
            sSkyGround = GLES20.glGetUniformLocation(skyProgram, "uSkyGround");
            sAtmosphere = GLES20.glGetUniformLocation(skyProgram, "uAtmosphereColor");
            sCloudColor = GLES20.glGetUniformLocation(skyProgram, "uCloudColor");
            sSkyCube = GLES20.glGetUniformLocation(skyProgram, "uSkyCube");
            sUseSkyCube = GLES20.glGetUniformLocation(skyProgram, "uUseSkyCube");
            sSunAngular = GLES20.glGetUniformLocation(skyProgram, "uSunAngular");
            sStarCount = GLES20.glGetUniformLocation(skyProgram, "uStarCount");
            sCelestial = GLES20.glGetUniformLocation(skyProgram, "uCelestial");
            sDayFactor = GLES20.glGetUniformLocation(skyProgram, "uDayFactor");
            sAtmoDensity = GLES20.glGetUniformLocation(skyProgram, "uAtmoDensity");
            sAtmoHaze = GLES20.glGetUniformLocation(skyProgram, "uAtmoHaze");
            sAtmoGlare = GLES20.glGetUniformLocation(skyProgram, "uAtmoGlare");
            sAtmoOffset = GLES20.glGetUniformLocation(skyProgram, "uAtmoOffset");
            sAtmoDecay = GLES20.glGetUniformLocation(skyProgram, "uAtmoDecay");
            sCloudCover = GLES20.glGetUniformLocation(skyProgram, "uCloudCover");
            sCloudDensity = GLES20.glGetUniformLocation(skyProgram, "uCloudDensity");
            sCloudTime = GLES20.glGetUniformLocation(skyProgram, "uCloudTime");

            brightProgram = build(QUAD_VERT, BRIGHT_FRAG);
            brightPos = GLES20.glGetAttribLocation(brightProgram, "aPos");
            bScene = GLES20.glGetUniformLocation(brightProgram, "uScene");
            bThreshold = GLES20.glGetUniformLocation(brightProgram, "uThreshold");
            blurProgram = build(QUAD_VERT, BLUR_FRAG);
            blurPos = GLES20.glGetAttribLocation(blurProgram, "aPos");
            ublSrc = GLES20.glGetUniformLocation(blurProgram, "uSrc");
            ublStep = GLES20.glGetUniformLocation(blurProgram, "uStep");
            raysProgram = build(QUAD_VERT, RAYS_FRAG);
            raysPos = GLES20.glGetAttribLocation(raysProgram, "aPos");
            rScene = GLES20.glGetUniformLocation(raysProgram, "uScene");
            rSunUv = GLES20.glGetUniformLocation(raysProgram, "uSunUv");
            rSpread = GLES20.glGetUniformLocation(raysProgram, "uSpread");
            rIntensity = GLES20.glGetUniformLocation(raysProgram, "uIntensity");
            postProgram = build(QUAD_VERT, POST_FRAG);
            postPos = GLES20.glGetAttribLocation(postProgram, "aPos");
            pScene = GLES20.glGetUniformLocation(postProgram, "uScene");
            pBloom = GLES20.glGetUniformLocation(postProgram, "uBloom");
            pRays = GLES20.glGetUniformLocation(postProgram, "uRays");
            pBloomAmount = GLES20.glGetUniformLocation(postProgram, "uBloomAmount");
            pRaysAmount = GLES20.glGetUniformLocation(postProgram, "uRaysAmount");
            pExposure = GLES20.glGetUniformLocation(postProgram, "uExposure");
            pCcBrightness = GLES20.glGetUniformLocation(postProgram, "uCcBrightness");
            pCcContrast = GLES20.glGetUniformLocation(postProgram, "uCcContrast");
            pCcSaturation = GLES20.glGetUniformLocation(postProgram, "uCcSaturation");
            pTint = GLES20.glGetUniformLocation(postProgram, "uTint");

            buildCube();
            buildGrid();
            buildQuad();
            textures.buildAtlas();
            releaseTargets();
        } catch (Throwable t) {
            fail("creating the GL pipeline", t);
        }
    }

    /** The part shader needs the face normal of the unit cube, which is its normalised position. */
    private static String shadeVertexSource() { return VERT; }

    private static int build(String vertex, String fragment) {
        int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vs, vertex); GLES20.glCompileShader(vs);
        int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fs, fragment); GLES20.glCompileShader(fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private void buildCube() {
        float[] p = new float[24 * 3], f = new float[24];
        float[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int v = 0;
        for (int faceIndex = 0; faceIndex < 6; faceIndex++) {
            float[] normal = faces[faceIndex];
            float[] u = Math.abs(normal[0]) > 0.5f ? new float[]{0, 0, 1} : new float[]{1, 0, 0};
            float[] w = cross(normal, u);
            for (int i = 0; i < 4; i++) {
                float su = (i == 0 || i == 3) ? -0.5f : 0.5f;
                float sw = (i < 2) ? -0.5f : 0.5f;
                p[v * 3] = normal[0] * 0.5f + u[0] * su + w[0] * sw;
                p[v * 3 + 1] = normal[1] * 0.5f + u[1] * su + w[1] * sw;
                p[v * 3 + 2] = normal[2] * 0.5f + u[2] * su + w[2] * sw;
                f[v] = faceIndex;
                v++;
            }
        }
        short[] idx = {0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 8, 9, 10, 8, 10, 11, 12, 14, 13, 12, 15, 14, 16, 17, 18, 16, 18, 19, 20, 22, 21, 20, 23, 22};
        cubePositions = floatBuffer(p);
        faceBuffer = floatBuffer(f);
        cubeIndices = shortBuffer(idx);
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private void buildGrid() {
        int extent = 256, step = 4;
        int lines = (extent * 2 / step) + 1;
        float[] out = new float[lines * 4 * 3];
        int i = 0;
        for (int v = -extent; v <= extent; v += step) {
            out[i++] = v; out[i++] = 0; out[i++] = -extent; out[i++] = v; out[i++] = 0; out[i++] = extent;
            out[i++] = -extent; out[i++] = 0; out[i++] = v; out[i++] = extent; out[i++] = 0; out[i++] = v;
        }
        gridVertices = i / 3;
        gridLines = floatBuffer(java.util.Arrays.copyOf(out, i));
    }

    private void buildQuad() {
        quadBuffer = floatBuffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
        gizmoBuffer = ByteBuffer.allocateDirect(gizmoScratch.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static FloatBuffer floatBuffer(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }
    private static ShortBuffer shortBuffer(short[] data) {
        ShortBuffer b = ByteBuffer.allocateDirect(data.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        b.put(data).position(0);
        return b;
    }

    @Override public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewportWidth = Math.max(1, width);
        viewportHeight = Math.max(1, height);
        GLES20.glViewport(0, 0, width, height);
        releaseTargets();
    }

    // ---------------------------------------------------------------- frame
    @Override public void onDrawFrame(GL10 unused) {
        if (stopped) return;
        try {
            long now = System.nanoTime();
            double dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e9;
            lastFrame = now;
            if (scene.playing) scene.step(dt);
            scene.flyEditCamera(dt);

            EngineLighting light;
            synchronized (scene) {
                if (scene.cacheVersion != scene.version) scene.refreshCache();
                light = scene.lighting;
                frameList.clear();
                frameList.addAll(scene.renderList);
            }
            if (light == null) light = EngineLighting.from(null);

            textures.uploadPending();
            if (!skyRequested && light.skyTextures != null) {
                textures.requestSky(light.skyTextures);
                skyRequested = true;
            }

            scene.updateEye();
            float aspect = (float) viewportWidth / viewportHeight;
            Matrix.perspectiveM(mProj, 0, 60f, aspect, 0.1f, 4000f);
            Matrix.setLookAtM(mView, 0, (float) scene.camEye[0], (float) scene.camEye[1], (float) scene.camEye[2],
                    (float) scene.camTarget[0], (float) scene.camTarget[1], (float) scene.camTarget[2], 0, 1, 0);
            Matrix.multiplyMM(mVp, 0, mProj, 0, mView, 0);
            synchronized (scene) { System.arraycopy(mVp, 0, scene.viewProjection, 0, 16); }

            boolean post = light.needsPost() && ensureTargets(viewportWidth, viewportHeight);
            if (post) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo);
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            }
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
            GLES20.glClearColor((float) light.skyHorizon[0], (float) light.skyHorizon[1], (float) light.skyHorizon[2], 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            drawSky(light);
            drawGrid(light);

            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            setGlobalLighting(light);
            bindAtlas();
            for (Instance part : frameList) drawPart(part, light);
            Instance selected = scene.selection;
            if (selected != null && selected.isRenderable()) drawOutline(selected);
            if (scene.playing) drawCharacter(light);
            drawGizmo(light);

            if (post) composite(light);
        } catch (Throwable t) {
            fail("drawing a frame", t);
        }
    }

    /** Uploads everything from the Lighting service that is the same for every part this frame. */
    private void setGlobalLighting(EngineLighting light) {
        GLES20.glUseProgram(program);
        GLES20.glUniform3f(uAmbient, (float) light.ambientDiffuse[0], (float) light.ambientDiffuse[1], (float) light.ambientDiffuse[2]);
        GLES20.glUniform3f(uSunDir, (float) light.sunDirection[0], (float) light.sunDirection[1], (float) light.sunDirection[2]);
        GLES20.glUniform3f(uSunColor, (float) light.sunColor[0], (float) light.sunColor[1], (float) light.sunColor[2]);
        GLES20.glUniform1f(uSunGain, (float) light.sunGain);
        GLES20.glUniform3f(uShiftTop, (float) light.colorShiftTop[0], (float) light.colorShiftTop[1], (float) light.colorShiftTop[2]);
        GLES20.glUniform3f(uShiftBottom, (float) light.colorShiftBottom[0], (float) light.colorShiftBottom[1], (float) light.colorShiftBottom[2]);
        GLES20.glUniform3f(uEye, (float) scene.camEye[0], (float) scene.camEye[1], (float) scene.camEye[2]);
        GLES20.glUniform3f(uFogColor, (float) light.fogTint[0], (float) light.fogTint[1], (float) light.fogTint[2]);
        GLES20.glUniform2f(uFogRange, (float) light.fogStart, (float) light.fogEnd);
        GLES20.glUniform1f(uFogEnabled, light.fogEnabled ? 1f : 0f);

        int count = 0;
        for (EngineLighting.Light local : light.lights) {
            if (count >= MAX_LIGHTS) break;
            lightPos[count * 3] = (float) local.position[0];
            lightPos[count * 3 + 1] = (float) local.position[1];
            lightPos[count * 3 + 2] = (float) local.position[2];
            lightColor[count * 3] = (float) (local.color[0] * local.brightness);
            lightColor[count * 3 + 1] = (float) (local.color[1] * local.brightness);
            lightColor[count * 3 + 2] = (float) (local.color[2] * local.brightness);
            lightDir[count * 3] = (float) local.direction[0];
            lightDir[count * 3 + 1] = (float) local.direction[1];
            lightDir[count * 3 + 2] = (float) local.direction[2];
            lightParams[count * 4] = (float) local.range;
            lightParams[count * 4 + 1] = (float) local.cosAngle;
            lightParams[count * 4 + 2] = local.kind;
            lightParams[count * 4 + 3] = 1f;
            count++;
        }
        GLES20.glUniform3fv(uLightPos, MAX_LIGHTS, lightPos, 0);
        GLES20.glUniform3fv(uLightColor, MAX_LIGHTS, lightColor, 0);
        GLES20.glUniform3fv(uLightDir, MAX_LIGHTS, lightDir, 0);
        GLES20.glUniform4fv(uLightParams, MAX_LIGHTS, lightParams, 0);
        GLES20.glUniform1i(uLightCount, count);
    }

    private void bindAtlas() {
        GLES20.glUniform1i(uAtlas, 0);
        GLES20.glUniform1f(uAtlasStep, 1f / EngineTextures.ATLAS_TILES);
        bindTexture(0, textures.atlasTexture());
    }

    // ---------------------------------------------------------------- sky
    private void drawSky(EngineLighting light) {
        if (!Matrix.invertM(mInvVp, 0, mVp, 0)) return;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(skyProgram);
        float[][] corners = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
        int[] locations = {sRayBL, sRayBR, sRayTL, sRayTR};
        for (int i = 0; i < 4; i++) {
            float[] ray = unproject(corners[i][0], corners[i][1]);
            GLES20.glUniform3f(locations[i], ray[0], ray[1], ray[2]);
        }
        GLES20.glUniform3f(sSunDir, (float) light.sunDirection[0], (float) light.sunDirection[1], (float) light.sunDirection[2]);
        GLES20.glUniform3f(sSunColor, (float) light.sunColor[0], (float) light.sunColor[1], (float) light.sunColor[2]);
        GLES20.glUniform3f(sSkyTop, (float) light.skyTop[0], (float) light.skyTop[1], (float) light.skyTop[2]);
        GLES20.glUniform3f(sSkyHorizon, (float) light.skyHorizon[0], (float) light.skyHorizon[1], (float) light.skyHorizon[2]);
        GLES20.glUniform3f(sSkyGround, (float) light.skyGround[0], (float) light.skyGround[1], (float) light.skyGround[2]);
        GLES20.glUniform3f(sAtmosphere, (float) light.atmosphereColor[0], (float) light.atmosphereColor[1], (float) light.atmosphereColor[2]);
        GLES20.glUniform3f(sCloudColor, (float) light.cloudColor[0], (float) light.cloudColor[1], (float) light.cloudColor[2]);
        int cube = textures.skyCubeTexture();
        GLES20.glUniform1i(sSkyCube, 2);
        if (cube != 0) bindTextureTarget(2, GLES20.GL_TEXTURE_CUBE_MAP, cube);
        else bindTexture(2, textures.atlasTexture());
        GLES20.glUniform1f(sUseSkyCube, cube != 0 ? 1f : 0f);
        GLES20.glUniform1f(sSunAngular, (float) (light.celestialBodiesShown ? light.sunAngularSize : 0));
        GLES20.glUniform1f(sStarCount, (float) light.starCount);
        GLES20.glUniform1f(sCelestial, light.celestialBodiesShown ? 1f : 0f);
        GLES20.glUniform1f(sDayFactor, (float) light.dayFactor);
        GLES20.glUniform1f(sAtmoDensity, (float) (light.atmosphere ? light.atmosphereDensity : 0));
        GLES20.glUniform1f(sAtmoHaze, (float) light.atmosphereHaze);
        GLES20.glUniform1f(sAtmoGlare, (float) light.atmosphereGlare);
        GLES20.glUniform1f(sAtmoOffset, (float) light.atmosphereOffset);
        GLES20.glUniform1f(sAtmoDecay, (float) light.atmosphereDecay);
        GLES20.glUniform1f(sCloudCover, light.clouds ? (float) light.cloudCover : 0f);
        GLES20.glUniform1f(sCloudDensity, (float) light.cloudDensity);
        GLES20.glUniform1f(sCloudTime, (float) ((System.nanoTime() / 1e9) % 3600));
        GLES20.glEnableVertexAttribArray(qPos);
        GLES20.glVertexAttribPointer(qPos, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(qPos);
        GLES20.glDepthMask(true);
    }

    /** World-space ray through a normalized device point on the far plane. */
    private float[] unproject(float ndcX, float ndcY) {
        float[] p = new float[4];
        p[0] = mInvVp[0] * ndcX + mInvVp[4] * ndcY + mInvVp[8] + mInvVp[12];
        p[1] = mInvVp[1] * ndcX + mInvVp[5] * ndcY + mInvVp[9] + mInvVp[13];
        p[2] = mInvVp[2] * ndcX + mInvVp[6] * ndcY + mInvVp[10] + mInvVp[14];
        p[3] = mInvVp[3] * ndcX + mInvVp[7] * ndcY + mInvVp[11] + mInvVp[15];
        float w = Math.abs(p[3]) < 1e-6f ? 1e-6f : p[3];
        float x = p[0] / w - (float) scene.camEye[0];
        float y = p[1] / w - (float) scene.camEye[1];
        float z = p[2] / w - (float) scene.camEye[2];
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6f) return new float[]{0, 1, 0};
        return new float[]{x / len, y / len, z / len};
    }

    private void drawGrid(EngineLighting light) {
        GLES20.glUseProgram(lineProgram);
        GLES20.glUniformMatrix4fv(lMvp, 1, false, mVp, 0);
        float tone = (float) (0.14 + 0.30 * light.dayFactor);
        GLES20.glUniform3f(lColor, tone, tone * 1.06f, tone * 1.22f);
        GLES20.glUniform1f(lAlpha, 0.36f);
        GLES20.glEnableVertexAttribArray(lPos);
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 0, gridLines);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertices);
        GLES20.glDisableVertexAttribArray(lPos);
    }

    // ---------------------------------------------------------------- parts
    private String assetContent;
    private int assetFaceIndex = -1;
    private double assetTilesU = 1, assetTilesV = 1;

    /**
     * Resolves the asset texture a part shows: `SurfaceAppearance.ColorMap`, `MeshPart.TextureID`,
     * then the first `Texture`/`Decal` child. Roblox's built-in `rbxasset://` textures are skipped
     * because they belong to the client, not to the place.
     */
    private boolean resolveAsset(Instance part) {
        assetContent = null;
        assetFaceIndex = -1;
        assetTilesU = assetTilesV = 1;
        Instance appearance = null, decal = null, texture = null;
        for (Instance child : part.children) {
            switch (child.className) {
                case "SurfaceAppearance": if (appearance == null) appearance = child; break;
                case "Texture": if (texture == null) texture = child; break;
                case "Decal": if (decal == null) decal = child; break;
                default: break;
            }
        }
        String content = null;
        if (appearance != null) content = content(appearance, "ColorMap");
        if (content == null) content = content(part, "TextureID");
        if (content == null) content = content(part, "TextureId");
        if (content == null && texture != null) {
            content = content(texture, "Texture");
            if (content != null) {
                assetFaceIndex = faceOfNormalId(texture.getInt("Face", 5));
                double[] face = faceExtent(part, assetFaceIndex);
                double studsU = Math.max(0.05, texture.getNumber("StudsPerTileU", 1));
                double studsV = Math.max(0.05, texture.getNumber("StudsPerTileV", 1));
                assetTilesU = Math.max(1, Math.round(face[0] / studsU));
                assetTilesV = Math.max(1, Math.round(face[1] / studsV));
            }
        }
        if (content == null && decal != null) {
            content = content(decal, "Texture");
            if (content != null) assetFaceIndex = faceOfNormalId(decal.getInt("Face", 5));
        }
        if (content == null || content.isEmpty()) return false;
        assetContent = content;
        return true;
    }

    /**
     * Roblox stores a Texture/Decal face as Enum.NormalId: Right 0, Top 1, Back 2, Left 3,
     * Bottom 4, Front 5. The cube mesh numbers its faces +X, -X, +Y, -Y, +Z, -Z, so the value has
     * to be remapped or a texture lands on the wrong side of the part.
     */
    static int faceOfNormalId(int normalId) {
        switch (normalId) {
            case 0: return 0;    // Right  -> +X
            case 1: return 2;    // Top    -> +Y
            case 2: return 4;    // Back   -> +Z
            case 3: return 1;    // Left   -> -X
            case 4: return 3;    // Bottom -> -Y
            default: return 5;   // Front  -> -Z
        }
    }

    private static String content(Instance owner, String key) {
        Object value = owner.props.get(key);
        return value instanceof String ? (String) value : null;
    }

    /** Studs across a face, ordered the way the shader derives its face UV. */
    private static double[] faceExtent(Instance part, int face) {
        double[] s = part.size();
        switch (face) {
            case 0: case 3: return new double[]{Math.abs(s[2]), Math.abs(s[1])};
            case 2: case 4: return new double[]{Math.abs(s[0]), Math.abs(s[1])};
            default: return new double[]{Math.abs(s[0]), Math.abs(s[2])};
        }
    }

    private void drawPart(Instance part, EngineLighting light) {
        double[] c = part.cframe();
        double[] s = part.size();
        float sx = (float) Math.max(0.02, Math.abs(s[0])), sy = (float) Math.max(0.02, Math.abs(s[1])), sz = (float) Math.max(0.02, Math.abs(s[2]));
        setModel(c, sx, sy, sz);
        int color = part.getColor();
        float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        float alpha = (float) (1.0 - Math.max(0, Math.min(1, part.getNumber("Transparency", 0))));
        boolean spawn = part.className.equals("SpawnLocation");
        if (alpha < 0.03f) return;
        boolean hot = part == scene.selection;
        if (hot) { r = Math.min(1, r * 1.25f + 0.05f); g = Math.min(1, g * 1.25f + 0.05f); b = Math.min(1, b * 1.25f + 0.05f); alpha = Math.max(alpha, 0.85f); }
        EngineMaterial material = EngineMaterial.of(part.getInt("Material", 256));
        double reflectance = Math.max(0, Math.min(1, part.getNumber("Reflectance", 0))) + material.reflectance;
        boolean hasAsset = resolveAsset(part);
        int assetTexture = hasAsset ? textures.assetTexture(assetContent) : 0;
        int atlas = textures.atlasTexture();
        boolean sampleAtlas = atlas != 0;
        // Face order matches the cube geometry: +X, -X, +Y, -Y, +Z, -Z.
        setFaceTiles(part, spawn, sampleAtlas);
        if (spawn) {
            int tile = textures.spawnTile();
            setTileUniform(uSpawnTile, tile, textures.tileStuds(tile));
        }
        drawBox(r, g, b, alpha, material, light.specularScale + reflectance, spawn ? 1f : 0f,
                assetTexture != 0 ? assetTexture : (sampleAtlas ? atlas : 0),
                assetTexture != 0 ? 1f : 0f, assetFaceIndex);
    }

    /** Per-face tile: the surface type wins over the material, exactly like Roblox. */
    private void setFaceTiles(Instance part, boolean spawn, boolean sampleAtlas) {
        int materialTile = textures.materialTile(part.getInt("Material", 256));
        String[] keys = {"RightSurface", "LeftSurface", "TopSurface", "BottomSurface", "BackSurface", "FrontSurface"};
        for (int face = 0; face < 6; face++) {
            int surface = part.getInt(keys[face], 0);
            int tile = surface == 0 ? materialTile : textures.surfaceTile(surface);
            float studs = textures.tileStuds(tile);
            faceTiles[face * 4] = (tile % EngineTextures.ATLAS_TILES) / (float) EngineTextures.ATLAS_TILES;
            faceTiles[face * 4 + 1] = (tile / EngineTextures.ATLAS_TILES) / (float) EngineTextures.ATLAS_TILES;
            faceTiles[face * 4 + 2] = 1f / studs;
            faceTiles[face * 4 + 3] = sampleAtlas ? 1f : 0f;
        }
        GLES20.glUniform4fv(uFaceTiles, 6, faceTiles, 0);
    }

    private void setTileUniform(int location, int tile, float studs) {
        GLES20.glUniform4f(location,
                (tile % EngineTextures.ATLAS_TILES) / (float) EngineTextures.ATLAS_TILES,
                (tile / EngineTextures.ATLAS_TILES) / (float) EngineTextures.ATLAS_TILES,
                1f / studs, 1f);
    }

    private void setModel(double[] c, float sx, float sy, float sz) {
        float r00 = (float) c[3], r01 = (float) c[4], r02 = (float) c[5];
        float r10 = (float) c[6], r11 = (float) c[7], r12 = (float) c[8];
        float r20 = (float) c[9], r21 = (float) c[10], r22 = (float) c[11];
        mModel[0] = r00 * sx; mModel[1] = r10 * sx; mModel[2] = r20 * sx; mModel[3] = 0;
        mModel[4] = r01 * sy; mModel[5] = r11 * sy; mModel[6] = r21 * sy; mModel[7] = 0;
        mModel[8] = r02 * sz; mModel[9] = r12 * sz; mModel[10] = r22 * sz; mModel[11] = 0;
        mModel[12] = (float) c[0]; mModel[13] = (float) c[1]; mModel[14] = (float) c[2]; mModel[15] = 1;
        Matrix.multiplyMM(mMvp, 0, mVp, 0, mModel, 0);
        // Rotation only: the shader derives the face normal from the unit-cube position, so a
        // non-uniform scale must not leak in here or the axes would fight each other.
        normal3[0] = r00; normal3[1] = r10; normal3[2] = r20;
        normal3[3] = r01; normal3[4] = r11; normal3[5] = r21;
        normal3[6] = r02; normal3[7] = r12; normal3[8] = r22;
    }

    private void drawBox(float r, float g, float b, float alpha, EngineMaterial material, double specular,
                         float isSpawn, int assetTexture, float hasAsset, int assetFace) {
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mMvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, mModel, 0);
        GLES20.glUniformMatrix3fv(uNormal, 1, false, normal3, 0);
        GLES20.glUniform3f(uColor, r, g, b);
        GLES20.glUniform1f(uAlpha, alpha);
        GLES20.glUniform1f(uRough, (float) material.roughness);
        GLES20.glUniform1f(uMetal, (float) material.metalness);
        GLES20.glUniform1f(uEmissive, (float) material.emissive);
        GLES20.glUniform1f(uShininess, (float) material.shininess());
        GLES20.glUniform1f(uSpecular, (float) specular);
        GLES20.glUniform1f(uIsSpawn, isSpawn);
        GLES20.glUniform1f(uHasAsset, hasAsset);
        GLES20.glUniform2f(uAssetTiles, (float) assetTilesU, (float) assetTilesV);
        GLES20.glUniform1f(uAssetFace, hasAsset > 0 ? assetFace : -1f);
        GLES20.glUniform1i(uAsset, 1);
        bindTexture(1, assetTexture != 0 ? assetTexture : textures.atlasTexture());
        GLES20.glUniform1i(uAtlas, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, cubePositions);
        if (aLocal >= 0) {
            GLES20.glEnableVertexAttribArray(aLocal);
            GLES20.glVertexAttribPointer(aLocal, 3, GLES20.GL_FLOAT, false, 0, cubePositions);
        }
        if (aFace >= 0) {
            GLES20.glEnableVertexAttribArray(aFace);
            GLES20.glVertexAttribPointer(aFace, 1, GLES20.GL_FLOAT, false, 0, faceBuffer);
        }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices);
        GLES20.glDisableVertexAttribArray(aPos);
        if (aLocal >= 0) GLES20.glDisableVertexAttribArray(aLocal);
        if (aFace >= 0) GLES20.glDisableVertexAttribArray(aFace);
    }

    private void drawOutline(Instance part) {
        double[] c = part.cframe();
        double[] s = part.size();
        float hx = (float) Math.abs(s[0]) / 2 + 0.02f, hy = (float) Math.abs(s[1]) / 2 + 0.02f, hz = (float) Math.abs(s[2]) / 2 + 0.02f;
        float[] pts = {
                -hx, -hy, -hz, hx, -hy, -hz, hx, -hy, -hz, hx, -hy, hz, hx, -hy, hz, -hx, -hy, hz, -hx, -hy, hz, -hx, -hy, -hz,
                -hx, hy, -hz, hx, hy, -hz, hx, hy, -hz, hx, hy, hz, hx, hy, hz, -hx, hy, hz, -hx, hy, hz, -hx, hy, -hz,
                -hx, -hy, -hz, -hx, hy, -hz, hx, -hy, -hz, hx, hy, -hz, hx, -hy, hz, hx, hy, hz, -hx, -hy, hz, -hx, hy, hz
        };
        setModel(c, 1, 1, 1);
        FloatBuffer buf = floatBuffer(pts);
        GLES20.glUseProgram(lineProgram);
        GLES20.glUniformMatrix4fv(lMvp, 1, false, mMvp, 0);
        GLES20.glUniform3f(lColor, 0.25f, 0.55f, 1f);
        GLES20.glUniform1f(lAlpha, 1f);
        GLES20.glLineWidth(2.5f);
        GLES20.glEnableVertexAttribArray(lPos);
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 0, buf);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 24);
        GLES20.glDisableVertexAttribArray(lPos);
        GLES20.glLineWidth(1f);
    }

    private void drawCharacter(EngineLighting light) {
        double[] p = scene.charPos;
        double yaw = scene.charYaw;
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        EngineMaterial skin = EngineMaterial.of(256);
        charBox(p, cy, sy, 0, 3f, 0, 2, 2, 1, TORSO, skin, light);
        charBox(p, cy, sy, 0, 4.63f, 0, 1.25f, 1.25f, 1.25f, HEAD, skin, light);
        charBox(p, cy, sy, -1.5f, 3f, 0, 1, 2, 1, LIMB, skin, light);
        charBox(p, cy, sy, 1.5f, 3f, 0, 1, 2, 1, LIMB, skin, light);
        charBox(p, cy, sy, -0.55f, 1f, 0, 1, 2, 1, LIMB, skin, light);
        charBox(p, cy, sy, 0.55f, 1f, 0, 1, 2, 1, LIMB, skin, light);
    }

    private void charBox(double[] origin, float cy, float sy, float ox, float oyAboveFeet, float oz, float w, float h, float d, float[] color, EngineMaterial material, EngineLighting light) {
        double wx = origin[0] + (ox * cy + oz * sy);
        double wz = origin[2] + (-ox * sy + oz * cy);
        double wy = origin[1] - scene.charHy + oyAboveFeet;
        double[] c = {wx, wy, wz, cy, 0, sy, 0, 1, 0, -sy, 0, cy};
        setModel(c, w, h, d);
        int materialTile = textures.materialTile(256);
        FloatBuffer previous = null;
        for (int face = 0; face < 6; face++) {
            faceTiles[face * 4] = (materialTile % EngineTextures.ATLAS_TILES) / (float) EngineTextures.ATLAS_TILES;
            faceTiles[face * 4 + 1] = (materialTile / EngineTextures.ATLAS_TILES) / (float) EngineTextures.ATLAS_TILES;
            faceTiles[face * 4 + 2] = 1f / textures.tileStuds(materialTile);
            faceTiles[face * 4 + 3] = textures.atlasTexture() != 0 ? 1f : 0f;
        }
        GLES20.glUniform4fv(uFaceTiles, 6, faceTiles, 0);
        drawBox(color[0], color[1], color[2], 1f, material, light.specularScale, 0f,
                textures.atlasTexture(), 0f, -1);
    }

    // ---------------------------------------------------------------- gizmo
    /** Draws the Studio-style move/rotate/scale handles for the current selection. */
    private void drawGizmo(EngineLighting light) {
        Instance selection = scene.selection;
        if (selection == null || !selection.isRenderable() || scene.playing) return;
        int tool = scene.gizmoTool;
        if (tool == EngineGizmo.GRAB) return;                       // grab has no handles
        double[] c = selection.cframe();
        double[] center = {c[0], c[1], c[2]};
        double distance = Math.hypot(center[0] - scene.camEye[0], Math.hypot(center[1] - scene.camEye[1], center[2] - scene.camEye[2]));
        double length = EngineGizmo.handleLength(distance, 60, viewportHeight, 1);
        int total = 0;
        if (tool == EngineGizmo.ROTATE) {
            double radius = length * 0.9;
            for (int axis = 0; axis < 3 && total + 96 <= GIZMO_LINE_VERTICES; axis++) {
                double[] n = EngineGizmo.axis(axis);
                double[] u = Math.abs(n[0]) > 0.5 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
                double[] v = EngineGizmo.cross(n, u);
                int steps = 48;
                for (int i = 0; i < steps; i++) {
                    double t0 = i * 2 * Math.PI / steps, t1 = (i + 1) * 2 * Math.PI / steps;
                    total = addLine(total, center, u, v, radius, t0, t1);
                }
            }
            drawGizmoLines(total, false);
        } else {
            for (int axis = 0; axis < 3; axis++) {
                double[] d = EngineGizmo.axis(axis);
                if (total + 4 <= GIZMO_LINE_VERTICES) {
                    put(total++, (float) center[0], (float) center[1], (float) center[2]);
                    put(total++, (float) (center[0] + d[0] * length), (float) (center[1] + d[1] * length), (float) (center[2] + d[2] * length));
                }
            }
            drawGizmoLines(total, true);
        }
    }

    private int addLine(int total, double[] center, double[] u, double[] v, double radius, double t0, double t1) {
        if (total + 6 > GIZMO_LINE_VERTICES) return total;
        put(total++, (float) (center[0] + (u[0] * Math.cos(t0) + v[0] * Math.sin(t0)) * radius),
                (float) (center[1] + (u[1] * Math.cos(t0) + v[1] * Math.sin(t0)) * radius),
                (float) (center[2] + (u[2] * Math.cos(t0) + v[2] * Math.sin(t0)) * radius));
        put(total++, (float) (center[0] + (u[0] * Math.cos(t1) + v[0] * Math.sin(t1)) * radius),
                (float) (center[1] + (u[1] * Math.cos(t1) + v[1] * Math.sin(t1)) * radius),
                (float) (center[2] + (u[2] * Math.cos(t1) + v[2] * Math.sin(t1)) * radius));
        return total;
    }

    private void put(int index, float x, float y, float z) {
        if (index * 3 + 2 >= gizmoScratch.length) return;
        gizmoScratch[index * 3] = x;
        gizmoScratch[index * 3 + 1] = y;
        gizmoScratch[index * 3 + 2] = z;
    }

    /** Handles are drawn per axis so each one is red/green/blue like Studio's gizmo. */
    private void drawGizmoLines(int vertices, boolean axes) {
        if (vertices == 0) return;
        gizmoBuffer.clear();
        gizmoBuffer.put(gizmoScratch, 0, vertices * 3);
        gizmoBuffer.position(0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(lineProgram);
        GLES20.glUniformMatrix4fv(lMvp, 1, false, mVp, 0);
        GLES20.glLineWidth(4f);
        GLES20.glUniform1f(lAlpha, 1f);
        GLES20.glEnableVertexAttribArray(lPos);
        if (axes) {
            float[][] colours = {{0.95f, 0.25f, 0.25f}, {0.30f, 0.85f, 0.30f}, {0.35f, 0.50f, 1f}};
            for (int axis = 0; axis < 3; axis++) {
                GLES20.glUniform3f(lColor, colours[axis][0], colours[axis][1], colours[axis][2]);
                GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 0, gizmoBuffer.position(axis * 2 * 3));
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2);
            }
        } else {
            float[][] colours = {{0.95f, 0.25f, 0.25f}, {0.30f, 0.85f, 0.30f}, {0.35f, 0.50f, 1f}};
            int perAxis = 96;                          // 48 segments per ring, 2 vertices each
            for (int axis = 0; axis < 3; axis++) {
                GLES20.glUniform3f(lColor, colours[axis][0], colours[axis][1], colours[axis][2]);
                int start = axis * perAxis;
                int count = Math.min(perAxis, Math.max(0, vertices - start));
                if (count == 0) continue;
                GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 0, gizmoBuffer.position(start * 3));
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, count);
            }
        }
        GLES20.glDisableVertexAttribArray(lPos);
        GLES20.glLineWidth(1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    // ---------------------------------------------------------------- post pass
    private void deleteTargets() {
        if (sceneTex != 0) {
            GLES20.glDeleteTextures(4, new int[]{sceneTex, scratchTex, halfTex, halfTex2}, 0);
            GLES20.glDeleteFramebuffers(4, new int[]{sceneFbo, scratchFbo, halfFbo, halfFbo2}, 0);
            GLES20.glDeleteRenderbuffers(1, new int[]{sceneDepth}, 0);
        }
        sceneFbo = scratchFbo = halfFbo = halfFbo2 = 0;
        sceneTex = scratchTex = halfTex = halfTex2 = sceneDepth = 0;
    }

    private void releaseTargets() {
        deleteTargets();
        targetWidth = targetHeight = 0;
        postAvailable = false;
    }

    private boolean ensureTargets(int width, int height) {
        if (targetWidth == width && targetHeight == height) return postAvailable;
        releaseTargets();
        targetWidth = width;
        targetHeight = height;
        int halfW = Math.max(1, width / 2), halfH = Math.max(1, height / 2);
        try {
            sceneTex = texture(width, height);
            scratchTex = texture(width, height);
            halfTex = texture(halfW, halfH);
            halfTex2 = texture(halfW, halfH);
            sceneFbo = framebuffer(sceneTex);
            scratchFbo = framebuffer(scratchTex);
            halfFbo = framebuffer(halfTex);
            halfFbo2 = framebuffer(halfTex2);
            int[] rbs = new int[1];
            GLES20.glGenRenderbuffers(1, rbs, 0);
            sceneDepth = rbs[0];
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, sceneDepth);
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo);
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, sceneDepth);
            postAvailable = complete(sceneFbo) && complete(scratchFbo) && complete(halfFbo) && complete(halfFbo2);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        } catch (Throwable t) {
            postNote = "post-processing unavailable on this device: " + t.getClass().getSimpleName();
            deleteTargets();
            return false;
        }
        if (!postAvailable) {
            postNote = "post-processing unavailable on this GPU; effects stored in Lighting are not applied.";
            deleteTargets();
        }
        return postAvailable;
    }

    private static int texture(int width, int height) {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return ids[0];
    }

    private static int framebuffer(int tex) {
        int[] ids = new int[1];
        GLES20.glGenFramebuffers(1, ids, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0);
        return ids[0];
    }

    private static boolean complete(int fbo) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        return GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
    }

    private void drawQuad(int attrib) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnableVertexAttribArray(attrib);
        GLES20.glVertexAttribPointer(attrib, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(attrib);
    }

    private static void bindTexture(int unit, int tex) {
        bindTextureTarget(unit, GLES20.GL_TEXTURE_2D, tex);
    }

    /** Cubemaps must be bound to GL_TEXTURE_CUBE_MAP; binding them as 2D shows nothing. */
    private static void bindTextureTarget(int unit, int target, int tex) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(target, tex);
    }

    private void blurPass(int sourceTex, int destFbo, int width, int height, float dx, float dy) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, destFbo);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(blurProgram);
        GLES20.glUniform1i(ublSrc, 0);
        bindTexture(0, sourceTex);
        GLES20.glUniform2f(ublStep, dx, dy);
        drawQuad(blurPos);
    }

    private void composite(EngineLighting light) {
        int width = viewportWidth, height = viewportHeight;
        int halfW = Math.max(1, width / 2), halfH = Math.max(1, height / 2);

        if (light.blur && light.blurSize > 0.5) {
            float radius = (float) Math.max(1, Math.min(56, light.blurSize)) * 0.5f;
            blurPass(sceneTex, scratchFbo, width, height, radius / width, 0);
            blurPass(scratchTex, sceneFbo, width, height, 0, radius / height);
            if (light.blurSize > 24) {
                radius *= 0.5;
                blurPass(sceneTex, scratchFbo, width, height, radius / width, 0);
                blurPass(scratchTex, sceneFbo, width, height, 0, radius / height);
            }
        }

        boolean bloomWanted = light.bloom && light.bloomIntensity > 0.001;
        boolean raysWanted = light.sunRays && light.sunRaysIntensity > 0.001 && light.sunUp > 0.05 && sunOnScreen(light);
        if (bloomWanted) {
            float threshold = (float) Math.max(0.25, Math.min(0.95, light.bloomThreshold / 2.2));
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halfFbo);
            GLES20.glViewport(0, 0, halfW, halfH);
            GLES20.glUseProgram(brightProgram);
            GLES20.glUniform1i(bScene, 0);
            bindTexture(0, sceneTex);
            GLES20.glUniform1f(bThreshold, threshold);
            drawQuad(brightPos);
            float radius = (float) Math.max(1, Math.min(56, light.bloomSize)) * 0.35f;
            blurPass(halfTex, halfFbo2, halfW, halfH, radius / halfW, 0);
            blurPass(halfTex2, halfFbo, halfW, halfH, 0, radius / halfH);
        }
        if (raysWanted) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halfFbo2);
            GLES20.glViewport(0, 0, halfW, halfH);
            GLES20.glUseProgram(raysProgram);
            GLES20.glUniform1i(rScene, 0);
            bindTexture(0, sceneTex);
            GLES20.glUniform2f(rSunUv, sunUv[0], sunUv[1]);
            GLES20.glUniform1f(rSpread, (float) light.sunRaysSpread);
            GLES20.glUniform1f(rIntensity, (float) light.sunRaysIntensity);
            drawQuad(raysPos);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(postProgram);
        GLES20.glUniform1i(pScene, 0);
        GLES20.glUniform1i(pBloom, 1);
        GLES20.glUniform1i(pRays, 2);
        bindTexture(0, sceneTex);
        bindTexture(1, halfTex);
        bindTexture(2, halfTex2);
        GLES20.glUniform1f(pBloomAmount, bloomWanted ? (float) light.bloomIntensity : 0f);
        GLES20.glUniform1f(pRaysAmount, raysWanted ? (float) light.sunRaysIntensity : 0f);
        GLES20.glUniform1f(pExposure, (float) light.exposureCompensation);
        boolean cc = light.colorCorrection && light.ccEnabled;
        GLES20.glUniform1f(pCcBrightness, cc ? (float) light.ccBrightness : 0f);
        GLES20.glUniform1f(pCcContrast, cc ? (float) light.ccContrast : 0f);
        GLES20.glUniform1f(pCcSaturation, cc ? (float) light.ccSaturation : 0f);
        GLES20.glUniform3f(pTint, cc ? (float) light.ccTint[0] : 1f, cc ? (float) light.ccTint[1] : 1f, cc ? (float) light.ccTint[2] : 1f);
        drawQuad(postPos);
    }

    private boolean sunOnScreen(EngineLighting light) {
        double[] eye = scene.camEye;
        double px = eye[0] + light.sunDirection[0] * 2000, py = eye[1] + light.sunDirection[1] * 2000, pz = eye[2] + light.sunDirection[2] * 2000;
        float cx = mVp[0] * (float) px + mVp[4] * (float) py + mVp[8] * (float) pz + mVp[12];
        float cy = mVp[1] * (float) px + mVp[5] * (float) py + mVp[9] * (float) pz + mVp[13];
        float cw = mVp[3] * (float) px + mVp[7] * (float) py + mVp[11] * (float) pz + mVp[15];
        if (cw <= 0.001f) return false;
        float u = cx / cw * 0.5f + 0.5f, v = cy / cw * 0.5f + 0.5f;
        sunUv[0] = u;
        sunUv[1] = v;
        return u > -0.35f && u < 1.35f && v > -0.35f && v < 1.35f;
    }

    /** Reason the post pass is unavailable, or an empty string when it works. */
    String postNote() { return postNote; }
}
