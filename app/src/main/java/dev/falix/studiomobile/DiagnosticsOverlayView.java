package dev.falix.studiomobile;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import java.util.Locale;

/**
 * Compact Roblox Studio MicroProfiler / Diagnostics HUD showing live FPS,
 * frame time, scene part count, physics solver rate, avatar rig, and lighting technology.
 */
final class DiagnosticsOverlayView extends TextView {
    private long lastNs = 0;
    private float smoothFps = 60f;
    private float smoothMs = 16.6f;

    DiagnosticsOverlayView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        setTextSize(9.5f);
        setTextColor(0xFFDCE6F5);
        setTypeface(Typeface.MONOSPACE);
        int p = (int) (6 * d + 0.5f);
        setPadding(p + 2, p, p + 2, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xC0141820);
        bg.setCornerRadius(6f * d);
        bg.setStroke((int) Math.max(1, d), 0x553B4961);
        setBackground(bg);
    }

    void update(EngineScene scene) {
        long now = System.nanoTime();
        if (lastNs != 0) {
            float dtMs = (now - lastNs) / 1e6f;
            if (dtMs > 0.5f && dtMs < 500f) {
                smoothMs = smoothMs * 0.85f + dtMs * 0.15f;
                smoothFps = 1000f / Math.max(1f, smoothMs);
            }
        }
        lastNs = now;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        String rig = (scene.avatar != null && !scene.avatar.isR15) ? "R6" : "R15";
        String tech = scene.lighting != null ? scene.lighting.technology : "ShadowMap";
        String state = scene.playing ? scene.humanoidState : "Edit";
        setText(String.format(Locale.US,
                "FPS %2.0f (%.1fms) | Parts %d\n%s | %s | 240Hz | %s | %dMB",
                smoothFps, smoothMs, scene.renderList.size(), state, rig, tech, usedMb));
    }
}
