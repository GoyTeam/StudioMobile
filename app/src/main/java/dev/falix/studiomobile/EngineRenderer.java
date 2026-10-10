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
 * OpenGL ES 2.0 viewport. No third-party engine: one lit box mesh, one line program,
 * parts drawn from the Roblox data model with their real size/CFrame/color.
 */
final class EngineRenderer implements GLSurfaceView.Renderer {
    private final EngineScene scene;
    private final ArrayList<Instance> frameList = new ArrayList<>();

    private static final String VERT =
            "uniform mat4 uMvp; uniform mat3 uNormal; attribute vec3 aPos; attribute vec3 aNormal; varying vec3 vNormal;\n" +
            "void main(){ vNormal=uNormal*aNormal; gl_Position=uMvp*vec4(aPos,1.0); }";
    private static final String FRAG =
            "precision mediump float; uniform vec3 uColor; uniform float uAlpha; varying vec3 vNormal;\n" +
            "void main(){ vec3 n=normalize(vNormal); vec3 l1=normalize(vec3(0.55,0.9,0.35)); vec3 l2=normalize(vec3(-0.5,0.4,-0.7));\n" +
            " float d=max(dot(n,l1),0.0)*0.75+max(dot(n,l2),0.0)*0.25; vec3 c=uColor*(0.55+d); gl_FragColor=vec4(c,uAlpha); }";
    private static final String LINE_VERT = "uniform mat4 uMvp; attribute vec3 aPos; void main(){ gl_Position=uMvp*vec4(aPos,1.0); }";
    private static final String LINE_FRAG = "precision mediump float; uniform vec3 uColor; uniform float uAlpha; void main(){ gl_FragColor=vec4(uColor,uAlpha); }";

    private int program, lineProgram, aPos, aNormal, uMvp, uNormal, uColor, uAlpha;
    private int lProgram, lPos, lMvp, lColor, lAlpha;
    private FloatBuffer cubePositions, cubeNormals;
    private ShortBuffer cubeIndices;
    private FloatBuffer gridLines;
    private int gridVertices;
    private final float[] mProj = new float[16], mView = new float[16], mVp = new float[16], mModel = new float[16], mMvp = new float[16];
    private final float[] normal3 = new float[9];
    private long lastFrame;
    private int viewportWidth = 1, viewportHeight = 1;

    private static final float[] HEAD = {0.96f, 0.80f, 0.19f};
    private static final float[] TORSO = {0.05f, 0.41f, 0.82f};
    private static final float[] LIMB = {0.64f, 0.74f, 0.28f};

    EngineRenderer(EngineScene scene) { this.scene = scene; }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0.62f, 0.76f, 0.92f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        program = build(VERT, FRAG);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uNormal = GLES20.glGetUniformLocation(program, "uNormal");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
        lineProgram = build(LINE_VERT, LINE_FRAG);
        lPos = GLES20.glGetAttribLocation(lineProgram, "aPos");
        lMvp = GLES20.glGetUniformLocation(lineProgram, "uMvp");
        lColor = GLES20.glGetUniformLocation(lineProgram, "uColor");
        lAlpha = GLES20.glGetUniformLocation(lineProgram, "uAlpha");
        buildCube();
        buildGrid();
    }

    private static int build(String vertex, String fragment) {
        int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vs, vertex); GLES20.glCompileShader(vs);
        int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fs, fragment); GLES20.glCompileShader(fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p);
        return p;
    }

    private void buildCube() {
        float[] p = new float[24 * 3], n = new float[24 * 3];
        float[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int v = 0;
        for (float[] f : faces) {
            float[] u = Math.abs(f[0]) > 0.5f ? new float[]{0, 0, 1} : new float[]{1, 0, 0};
            float[] w = cross(f, u);
            for (int i = 0; i < 4; i++) {
                float su = (i == 0 || i == 3) ? -0.5f : 0.5f;
                float sw = (i < 2) ? -0.5f : 0.5f;
                p[v * 3] = f[0] * 0.5f + u[0] * su + w[0] * sw;
                p[v * 3 + 1] = f[1] * 0.5f + u[1] * su + w[1] * sw;
                p[v * 3 + 2] = f[2] * 0.5f + u[2] * su + w[2] * sw;
                n[v * 3] = f[0]; n[v * 3 + 1] = f[1]; n[v * 3 + 2] = f[2];
                v++;
            }
        }
        short[] idx = {0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 8, 9, 10, 8, 10, 11, 12, 14, 13, 12, 15, 14, 16, 17, 18, 16, 18, 19, 20, 22, 21, 20, 23, 22};
        cubePositions = floatBuffer(p);
        cubeNormals = floatBuffer(n);
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
    }

    @Override public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e9;
        lastFrame = now;
        if (scene.playing) scene.step(dt);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        scene.updateEye();
        float aspect = (float) viewportWidth / viewportHeight;
        Matrix.perspectiveM(mProj, 0, 60f, aspect, 0.1f, 4000f);
        Matrix.setLookAtM(mView, 0, (float) scene.camEye[0], (float) scene.camEye[1], (float) scene.camEye[2],
                (float) scene.camTarget[0], (float) scene.camTarget[1], (float) scene.camTarget[2], 0, 1, 0);
        Matrix.multiplyMM(mVp, 0, mProj, 0, mView, 0);

        synchronized (scene) {
            if (scene.cacheVersion != scene.version) scene.refreshCache();
            frameList.clear();
            frameList.addAll(scene.renderList);
        }

        drawGrid();
        for (Instance part : frameList) drawPart(part);
        Instance selected = scene.selection;
        if (selected != null && selected.isRenderable()) drawOutline(selected);
        if (scene.playing) drawCharacter();
    }

    private void drawGrid() {
        GLES20.glUseProgram(lineProgram);
        GLES20.glUniformMatrix4fv(lMvp, 1, false, mVp, 0);
        GLES20.glUniform3f(lColor, 0.42f, 0.46f, 0.52f);
        GLES20.glUniform1f(lAlpha, 0.4f);
        GLES20.glEnableVertexAttribArray(lPos);
        GLES20.glVertexAttribPointer(lPos, 3, GLES20.GL_FLOAT, false, 0, gridLines);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertices);
        GLES20.glDisableVertexAttribArray(lPos);
    }

    private void drawPart(Instance part) {
        double[] c = part.cframe();
        double[] s = part.size();
        float sx = (float) Math.max(0.02, Math.abs(s[0])), sy = (float) Math.max(0.02, Math.abs(s[1])), sz = (float) Math.max(0.02, Math.abs(s[2]));
        setModel(c, sx, sy, sz);
        int color = part.getColor();
        float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        float alpha = (float) (1.0 - Math.max(0, Math.min(1, part.getNumber("Transparency", 0))));
        if (part.className.equals("SpawnLocation")) { r = 0.13f; g = 0.62f; b = 0.29f; alpha = Math.max(alpha, 0.92f); }
        if (alpha < 0.03f) return;
        boolean hot = part == scene.selection;
        drawBox(hot ? Math.min(1, r * 1.25f + 0.05f) : r, hot ? Math.min(1, g * 1.25f + 0.05f) : g, hot ? Math.min(1, b * 1.25f + 0.05f) : b, hot ? Math.max(alpha, 0.85f) : alpha);
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
        normal3[0] = r00; normal3[1] = r10; normal3[2] = r20;
        normal3[3] = r01; normal3[4] = r11; normal3[5] = r21;
        normal3[6] = r02; normal3[7] = r12; normal3[8] = r22;
    }

    private void drawBox(float r, float g, float b, float alpha) {
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mMvp, 0);
        GLES20.glUniformMatrix3fv(uNormal, 1, false, normal3, 0);
        GLES20.glUniform3f(uColor, r, g, b);
        GLES20.glUniform1f(uAlpha, alpha);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, cubePositions);
        GLES20.glEnableVertexAttribArray(aNormal);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, cubeNormals);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndices);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aNormal);
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

    private void drawCharacter() {
        double[] p = scene.charPos;
        double yaw = scene.charYaw;
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        charBox(p, cy, sy, 0, 3f, 0, 2, 2, 1, TORSO);        // torso
        charBox(p, cy, sy, 0, 4.63f, 0, 1.25f, 1.25f, 1.25f, HEAD);
        charBox(p, cy, sy, -1.5f, 3f, 0, 1, 2, 1, LIMB);     // arms
        charBox(p, cy, sy, 1.5f, 3f, 0, 1, 2, 1, LIMB);
        charBox(p, cy, sy, -0.55f, 1f, 0, 1, 2, 1, LIMB);    // legs
        charBox(p, cy, sy, 0.55f, 1f, 0, 1, 2, 1, LIMB);
    }

    private void charBox(double[] origin, float cy, float sy, float ox, float oyAboveFeet, float oz, float w, float h, float d, float[] color) {
        double wx = origin[0] + (ox * cy + oz * sy);
        double wz = origin[2] + (-ox * sy + oz * cy);
        double wy = origin[1] - scene.charHy + oyAboveFeet;
        double[] c = {wx, wy, wz, cy, 0, sy, 0, 1, 0, -sy, 0, cy};
        setModel(c, w, h, d);
        drawBox(color[0], color[1], color[2], 1f);
    }
}
