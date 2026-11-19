package dev.falix.studiomobile;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * Roblox Studio top-right 3D View Selector Cube (Front / Back / Left / Right / Top)
 * with colored X/Y/Z orientation axes. Tapping cycles standard Studio camera presets.
 */
final class ViewCubeView extends View {
    private float yaw = 0f, pitch = 0.35f;
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private static final float[][] VERTS = {
        {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
        {-1, -1,  1}, {1, -1,  1}, {1, 1,  1}, {-1, 1,  1}
    };
    private static final int[][] FACES = {
        {0, 1, 2, 3}, // Front (-Z)
        {5, 4, 7, 6}, // Back (+Z)
        {4, 0, 3, 7}, // Left (-X)
        {1, 5, 6, 2}, // Right (+X)
        {3, 2, 6, 7}, // Top (+Y)
        {4, 5, 1, 0}  // Bottom (-Y)
    };
    private static final String[] LABELS = {"Front", "Back", "Left", "Right", "Top", "Bottom"};
    private static final float[][] NORMALS = {
        {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}
    };

    ViewCubeView(Context context) {
        super(context);
        facePaint.setStyle(Paint.Style.FILL);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.5f);
        edgePaint.setColor(0xFF9EA6B2);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setColor(0xFF474E59);
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(2.4f);
        axisPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setAngles(double yawRad, double pitchRad) {
        float y = (float) yawRad, p = (float) pitchRad;
        if (Math.abs(y - yaw) > 0.01f || Math.abs(p - pitch) > 0.01f) {
            yaw = y;
            pitch = p;
            invalidate();
        }
    }

    private float[] rot(float x, float y, float z) {
        float cy = (float) Math.cos(-yaw), sy = (float) Math.sin(-yaw);
        float x1 = x * cy - z * sy;
        float z1 = x * sy + z * cy;
        float cp = (float) Math.cos(-pitch), sp = (float) Math.sin(-pitch);
        float y2 = y * cp - z1 * sp;
        float z2 = y * sp + z1 * cp;
        return new float[]{x1, y2, z2};
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w * 0.5f, cy = h * 0.5f;
        float r = Math.min(w, h) * 0.27f;

        // Draw RGB X/Y/Z axis triad from back-bottom-left corner like Roblox Studio
        float[] o = rot(-1.25f, -1.25f, -1.25f);
        drawAxis(canvas, cx, cy, r, o, rot(0.35f, -1.25f, -1.25f), 0xFFE34B4B, "X");
        drawAxis(canvas, cx, cy, r, o, rot(-1.25f, 0.35f, -1.25f), 0xFF39C45B, "Y");
        drawAxis(canvas, cx, cy, r, o, rot(-1.25f, -1.25f, 0.35f), 0xFF3B82F6, "Z");

        float[][] proj = new float[8][3];
        for (int i = 0; i < 8; i++) {
            float[] v = rot(VERTS[i][0], VERTS[i][1], VERTS[i][2]);
            proj[i][0] = cx + v[0] * r;
            proj[i][1] = cy - v[1] * r;
            proj[i][2] = v[2];
        }

        textPaint.setTextSize(Math.max(10f, r * 0.42f));
        for (int f = 0; f < 6; f++) {
            float[] rn = rot(NORMALS[f][0], NORMALS[f][1], NORMALS[f][2]);
            if (rn[2] <= 0.02f) continue;
            int[] idx = FACES[f];
            path.reset();
            path.moveTo(proj[idx[0]][0], proj[idx[0]][1]);
            for (int k = 1; k < 4; k++) path.lineTo(proj[idx[k]][0], proj[idx[k]][1]);
            path.close();

            int shade = Math.min(255, Math.max(185, (int) (198 + rn[2] * 48 + rn[1] * 14)));
            facePaint.setColor(Color.rgb(shade, shade + 2, Math.min(255, shade + 6)));
            canvas.drawPath(path, facePaint);
            canvas.drawPath(path, edgePaint);

            float mx = (proj[idx[0]][0] + proj[idx[1]][0] + proj[idx[2]][0] + proj[idx[3]][0]) * 0.25f;
            float my = (proj[idx[0]][1] + proj[idx[1]][1] + proj[idx[2]][1] + proj[idx[3]][1]) * 0.25f;
            canvas.drawText(LABELS[f], mx, my + textPaint.getTextSize() * 0.35f, textPaint);
        }
    }

    private void drawAxis(Canvas canvas, float cx, float cy, float r, float[] a, float[] b, int color, String label) {
        axisPaint.setColor(color);
        float x0 = cx + a[0] * r, y0 = cy - a[1] * r;
        float x1 = cx + b[0] * r, y1 = cy - b[1] * r;
        canvas.drawLine(x0, y0, x1, y1, axisPaint);
        Paint lp = new Paint(textPaint);
        lp.setColor(color);
        lp.setTextSize(Math.max(9f, r * 0.38f));
        canvas.drawText(label, x1, y1 - 2f, lp);
    }
}
