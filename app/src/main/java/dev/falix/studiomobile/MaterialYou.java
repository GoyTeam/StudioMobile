package dev.falix.studiomobile;

import android.content.Context;
import android.os.Build;

final class MaterialYou {
    final boolean dark;
    final int primary, onPrimary, primaryContainer, onPrimaryContainer;
    final int secondary, onSecondary, secondaryContainer, onSecondaryContainer;
    final int tertiary, onTertiary, tertiaryContainer, onTertiaryContainer;
    final int surface, surfaceLow, surfaceContainer, surfaceHigh, surfaceHighest;
    final int onSurface, onSurfaceVariant, outline, outlineVariant;
    final int error, onError, errorContainer;

    private MaterialYou(boolean dark,
                        int primary, int onPrimary, int primaryContainer, int onPrimaryContainer,
                        int secondary, int onSecondary, int secondaryContainer, int onSecondaryContainer,
                        int tertiary, int onTertiary, int tertiaryContainer, int onTertiaryContainer,
                        int surface, int surfaceLow, int surfaceContainer, int surfaceHigh, int surfaceHighest,
                        int onSurface, int onSurfaceVariant, int outline, int outlineVariant,
                        int error, int onError, int errorContainer) {
        this.dark = dark;
        this.primary = primary; this.onPrimary = onPrimary;
        this.primaryContainer = primaryContainer; this.onPrimaryContainer = onPrimaryContainer;
        this.secondary = secondary; this.onSecondary = onSecondary;
        this.secondaryContainer = secondaryContainer; this.onSecondaryContainer = onSecondaryContainer;
        this.tertiary = tertiary; this.onTertiary = onTertiary;
        this.tertiaryContainer = tertiaryContainer; this.onTertiaryContainer = onTertiaryContainer;
        this.surface = surface; this.surfaceLow = surfaceLow;
        this.surfaceContainer = surfaceContainer; this.surfaceHigh = surfaceHigh; this.surfaceHighest = surfaceHighest;
        this.onSurface = onSurface; this.onSurfaceVariant = onSurfaceVariant;
        this.outline = outline; this.outlineVariant = outlineVariant;
        this.error = error; this.onError = onError; this.errorContainer = errorContainer;
    }

    static MaterialYou resolve(Context ctx, boolean dark) {
        if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
            try {
                if (dark) {
                    return new MaterialYou(true,
                            c(ctx, android.R.color.system_accent1_200),
                            c(ctx, android.R.color.system_accent1_800),
                            c(ctx, android.R.color.system_accent1_700),
                            c(ctx, android.R.color.system_accent1_100),
                            c(ctx, android.R.color.system_accent2_200),
                            c(ctx, android.R.color.system_accent2_800),
                            c(ctx, android.R.color.system_accent2_700),
                            c(ctx, android.R.color.system_accent2_100),
                            c(ctx, android.R.color.system_accent3_200),
                            c(ctx, android.R.color.system_accent3_800),
                            c(ctx, android.R.color.system_accent3_700),
                            c(ctx, android.R.color.system_accent3_100),
                            mix(c(ctx, android.R.color.system_neutral1_900), 0xFF0A0C10, 0.35f),
                            c(ctx, android.R.color.system_neutral1_900),
                            mix(c(ctx, android.R.color.system_neutral1_900), c(ctx, android.R.color.system_neutral1_800), 0.45f),
                            c(ctx, android.R.color.system_neutral1_800),
                            mix(c(ctx, android.R.color.system_neutral1_800), c(ctx, android.R.color.system_neutral1_700), 0.45f),
                            c(ctx, android.R.color.system_neutral1_100),
                            c(ctx, android.R.color.system_neutral2_200),
                            c(ctx, android.R.color.system_neutral2_400),
                            c(ctx, android.R.color.system_neutral2_700),
                            0xFFFFB4AB, 0xFF690005, 0xFF93000A);
                } else {
                    return new MaterialYou(false,
                            c(ctx, android.R.color.system_accent1_600),
                            0xFFFFFFFF,
                            c(ctx, android.R.color.system_accent1_100),
                            c(ctx, android.R.color.system_accent1_900),
                            c(ctx, android.R.color.system_accent2_600),
                            0xFFFFFFFF,
                            c(ctx, android.R.color.system_accent2_100),
                            c(ctx, android.R.color.system_accent2_900),
                            c(ctx, android.R.color.system_accent3_600),
                            0xFFFFFFFF,
                            c(ctx, android.R.color.system_accent3_100),
                            c(ctx, android.R.color.system_accent3_900),
                            c(ctx, android.R.color.system_neutral1_10),
                            c(ctx, android.R.color.system_neutral1_50),
                            mix(c(ctx, android.R.color.system_neutral1_50), c(ctx, android.R.color.system_neutral1_100), 0.45f),
                            c(ctx, android.R.color.system_neutral1_100),
                            mix(c(ctx, android.R.color.system_neutral1_100), c(ctx, android.R.color.system_neutral1_200), 0.5f),
                            c(ctx, android.R.color.system_neutral1_900),
                            c(ctx, android.R.color.system_neutral2_700),
                            c(ctx, android.R.color.system_neutral2_500),
                            c(ctx, android.R.color.system_neutral2_200),
                            0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6);
                }
            } catch (Throwable ignored) { }
        }
        if (dark) {
            return new MaterialYou(true,
                    0xFFB4C5FF, 0xFF002A78, 0xFF1F42A3, 0xFFDBE1FF,
                    0xFFC0C6DD, 0xFF2A3042, 0xFF404659, 0xFFDCE2F9,
                    0xFFE1BBDD, 0xFF422742, 0xFF5A3D59, 0xFFFFD7F9,
                    0xFF121318, 0xFF1A1B21, 0xFF1E2027, 0xFF282A32, 0xFF33353E,
                    0xFFE3E2E9, 0xFFC5C6D0, 0xFF8F909A, 0xFF45464F,
                    0xFFFFB4AB, 0xFF690005, 0xFF93000A);
        }
        return new MaterialYou(false,
                0xFF3558C4, 0xFFFFFFFF, 0xFFDBE1FF, 0xFF00174B,
                0xFF585E72, 0xFFFFFFFF, 0xFFDCE2F9, 0xFF151B2C,
                0xFF735471, 0xFFFFFFFF, 0xFFFFD7F9, 0xFF2B122B,
                0xFFFAF8FF, 0xFFF4F3FA, 0xFFEEEDF4, 0xFFE8E7EF, 0xFFE2E2E9,
                0xFF1A1B21, 0xFF45464F, 0xFF757680, 0xFFC5C6D0,
                0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int c(Context ctx, int resId) {
        return ctx.getColor(resId);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
