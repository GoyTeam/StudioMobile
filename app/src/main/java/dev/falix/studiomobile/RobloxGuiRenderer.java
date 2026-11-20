package dev.falix.studiomobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.*;

/**
 * Full-screen Roblox StarterGui / PlayerGui renderer supporting UDim2 Position & Size,
 * AnchorPoint, Frame, TextLabel, TextButton, ImageLabel, ImageButton, ScrollingFrame,
 * UICorner, UIStroke, BackgroundColor3, BackgroundTransparency, TextColor3, and MouseButton1Click.
 */
final class RobloxGuiRenderer {
    static void render(Context ctx, FrameLayout host, EngineScene scene) {
        if (host == null) return;
        host.removeAllViews();
        if (scene == null || scene.root == null || !scene.playing) return;
        int pw = host.getWidth();
        int ph = host.getHeight();
        if (pw <= 0 || ph <= 0) {
            pw = ctx.getResources().getDisplayMetrics().widthPixels;
            ph = ctx.getResources().getDisplayMetrics().heightPixels;
        }
        Instance starterGui = scene.root.findFirst("StarterGui");
        if (starterGui == null) return;
        for (Instance child : starterGui.children) {
            if ("ScreenGui".equals(child.className) && child.getBool("Enabled", true)) {
                renderChildren(ctx, host, child, pw, ph, scene);
            }
        }
    }

    private static void renderChildren(Context ctx, FrameLayout parentView, Instance parentInst,
                                       int parentW, int parentH, EngineScene scene) {
        ArrayList<Instance> sorted = new ArrayList<>(parentInst.children);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.getInt("ZIndex", 1), b.getInt("ZIndex", 1)));
        float density = ctx.getResources().getDisplayMetrics().density;

        for (Instance el : sorted) {
            if (!isGuiObject(el.className)) continue;
            if (!el.getBool("Visible", true)) continue;

            float[] size = udim2(el.props.get("Size"), 0f, 160f, 0f, 44f);
            float[] pos = udim2(el.props.get("Position"), 0f, 0f, 0f, 0f);
            float[] anchor = vec2(el.props.get("AnchorPoint"), 0f, 0f);

            int w = Math.max(1, Math.round(size[0] * parentW + size[1] * density));
            int h = Math.max(1, Math.round(size[2] * parentH + size[3] * density));
            int x = Math.round(pos[0] * parentW + pos[1] * density - anchor[0] * w);
            int y = Math.round(pos[2] * parentH + pos[3] * density - anchor[1] * h);

            int bgRgb = rgbOf(el, "BackgroundColor3", "TextButton".equals(el.className) ? 0x2D56E8 : 0x1E222B);
            double bgTrans = clamp01(el.getNumber("BackgroundTransparency", "TextLabel".equals(el.className) ? 1.0 : 0.0));
            int alpha = (int) Math.round((1.0 - bgTrans) * 255.0);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor((alpha << 24) | (bgRgb & 0xFFFFFF));

            // UICorner support
            Instance corner = el.findFirst("UICorner");
            if (corner != null) {
                float[] cr = udim(corner.props.get("CornerRadius"), 0f, 8f);
                float rad = cr[0] * Math.min(w, h) + cr[1] * density;
                bg.setCornerRadius(rad);
            } else if ("TextButton".equals(el.className)) {
                bg.setCornerRadius(6f * density);
            }

            // UIStroke support
            Instance stroke = el.findFirst("UIStroke");
            if (stroke != null && stroke.getBool("Enabled", true)) {
                int sRgb = rgbOf(stroke, "Color", 0xFFFFFF);
                double sTrans = clamp01(stroke.getNumber("Transparency", 0.0));
                int sAlpha = (int) Math.round((1.0 - sTrans) * 255.0);
                int thick = Math.max(1, Math.round((float) stroke.getNumber("Thickness", 1.0) * density));
                bg.setStroke(thick, (sAlpha << 24) | (sRgb & 0xFFFFFF));
            }

            FrameLayout box = new FrameLayout(ctx);
            box.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
            lp.leftMargin = x;
            lp.topMargin = y;

            if ("TextLabel".equals(el.className) || "TextButton".equals(el.className) || "TextBox".equals(el.className)) {
                TextView tv = new TextView(ctx);
                tv.setText(el.getString("Text", el.name));
                int fgRgb = rgbOf(el, "TextColor3", 0xFFFFFF);
                double tTrans = clamp01(el.getNumber("TextTransparency", 0.0));
                int tAlpha = (int) Math.round((1.0 - tTrans) * 255.0);
                tv.setTextColor((tAlpha << 24) | (fgRgb & 0xFFFFFF));
                float tSize = (float) el.getNumber("TextSize", 14.0);
                if (el.getBool("TextScaled", false)) {
                    tSize = Math.max(10f, Math.min(28f, (h / Math.max(1f, density)) * 0.48f));
                }
                tv.setTextSize(Math.max(9f, Math.min(36f, tSize * 0.85f)));
                tv.setGravity(Gravity.CENTER);
                int pad = Math.round(4 * density);
                tv.setPadding(pad, pad / 2, pad, pad / 2);
                box.addView(tv, new FrameLayout.LayoutParams(-1, -1));
            }

            if ("TextButton".equals(el.className) || "ImageButton".equals(el.className)) {
                box.setClickable(true);
                box.setOnClickListener(v -> scene.lua().fireGuiClick(el));
            }

            parentView.addView(box, lp);
            if (!el.children.isEmpty()) {
                renderChildren(ctx, box, el, w, h, scene);
            }
        }
    }

    private static boolean isGuiObject(String cls) {
        return "Frame".equals(cls) || "TextLabel".equals(cls) || "TextButton".equals(cls)
                || "ImageLabel".equals(cls) || "ImageButton".equals(cls)
                || "TextBox".equals(cls) || "ScrollingFrame".equals(cls);
    }

    private static float[] udim2(Object v, float xs, float xo, float ys, float yo) {
        if (v instanceof double[] && ((double[]) v).length >= 4) {
            double[] a = (double[]) v;
            return new float[]{(float) a[0], (float) a[1], (float) a[2], (float) a[3]};
        }
        if (v instanceof float[] && ((float[]) v).length >= 4) {
            return (float[]) v;
        }
        return new float[]{xs, xo, ys, yo};
    }

    private static float[] udim(Object v, float s, float o) {
        if (v instanceof double[] && ((double[]) v).length >= 2) {
            double[] a = (double[]) v;
            return new float[]{(float) a[0], (float) a[1]};
        }
        return new float[]{s, o};
    }

    private static float[] vec2(Object v, float x, float y) {
        if (v instanceof double[] && ((double[]) v).length >= 2) {
            double[] a = (double[]) v;
            return new float[]{(float) a[0], (float) a[1]};
        }
        return new float[]{x, y};
    }

    private static int rgbOf(Instance inst, String key, int fallback) {
        Object v = inst.props.get(key);
        if (v instanceof int[] && ((int[]) v).length == 3) {
            int[] c = (int[]) v;
            return Color.rgb(c[0], c[1], c[2]) & 0xFFFFFF;
        }
        if (v instanceof Number) return ((Number) v).intValue() & 0xFFFFFF;
        return fallback;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
