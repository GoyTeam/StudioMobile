package dev.falix.studiomobile;

import android.graphics.Bitmap;
import org.json.*;
import java.util.*;

final class AvatarProfile {
    boolean isR15 = true;
    float[] headColor = {0.96f, 0.80f, 0.19f};
    float[] torsoColor = {0.05f, 0.41f, 0.82f};
    float[] leftArmColor = {0.96f, 0.80f, 0.19f};
    float[] rightArmColor = {0.96f, 0.80f, 0.19f};
    float[] leftLegColor = {0.64f, 0.74f, 0.28f};
    float[] rightLegColor = {0.64f, 0.74f, 0.28f};
    float[] shirtColor = null;
    float[] pantsColor = null;
    float[] hairColor = null;
    boolean hasHair = false;
    float heightScale = 1.0f;
    float widthScale = 1.0f;
    float headScale = 1.0f;
    float depthScale = 1.0f;
    String username = "Player";
    boolean loadedFromRoblox = false;

    static AvatarProfile defaultAvatar() {
        return new AvatarProfile();
    }

    float[] effectiveTorsoColor() { return shirtColor != null ? shirtColor : torsoColor; }
    float[] effectiveLeftArmColor() { return shirtColor != null ? blend(shirtColor, leftArmColor, 0.35f) : leftArmColor; }
    float[] effectiveRightArmColor() { return shirtColor != null ? blend(shirtColor, rightArmColor, 0.35f) : rightArmColor; }
    float[] effectiveLeftLegColor() { return pantsColor != null ? pantsColor : leftLegColor; }
    float[] effectiveRightLegColor() { return pantsColor != null ? pantsColor : rightLegColor; }

    private static float[] blend(float[] a, float[] b, float t) {
        return new float[]{a[0] * (1 - t) + b[0] * t, a[1] * (1 - t) + b[1] * t, a[2] * (1 - t) + b[2] * t};
    }

    static AvatarProfile fetchForUser(RobloxApi api, long userId, String username) {
        AvatarProfile p = new AvatarProfile();
        if (username != null && !username.isEmpty()) p.username = username;
        if (userId <= 0 || api == null) return p;
        try {
            JSONObject data = api.json("https://avatar.roblox.com/v1/users/" + userId + "/avatar", null, null);
            p.isR15 = !"R6".equalsIgnoreCase(data.optString("playerAvatarType", "R15"));
            JSONObject scales = data.optJSONObject("scales");
            if (scales != null) {
                p.heightScale = clampScale((float) scales.optDouble("height", 1.0), 0.85f, 1.15f);
                p.widthScale = clampScale((float) scales.optDouble("width", 1.0), 0.75f, 1.15f);
                p.headScale = clampScale((float) scales.optDouble("head", 1.0), 0.85f, 1.15f);
                p.depthScale = clampScale((float) scales.optDouble("depth", 1.0), 0.75f, 1.15f);
            }
            JSONObject bc = data.optJSONObject("bodyColors");
            if (bc != null) {
                p.headColor = brickRgb(bc.optInt("headColorId", 24), p.headColor);
                p.torsoColor = brickRgb(bc.optInt("torsoColorId", 23), p.torsoColor);
                p.leftArmColor = brickRgb(bc.optInt("leftArmColorId", 24), p.leftArmColor);
                p.rightArmColor = brickRgb(bc.optInt("rightArmColorId", 24), p.rightArmColor);
                p.leftLegColor = brickRgb(bc.optInt("leftLegColorId", 119), p.leftLegColor);
                p.rightLegColor = brickRgb(bc.optInt("rightLegColorId", 119), p.rightLegColor);
            }
            JSONObject bc3 = data.optJSONObject("bodyColor3s");
            if (bc3 != null) {
                p.headColor = hexRgb(bc3.optString("headColor3", ""), p.headColor);
                p.torsoColor = hexRgb(bc3.optString("torsoColor3", ""), p.torsoColor);
                p.leftArmColor = hexRgb(bc3.optString("leftArmColor3", ""), p.leftArmColor);
                p.rightArmColor = hexRgb(bc3.optString("rightArmColor3", ""), p.rightArmColor);
                p.leftLegColor = hexRgb(bc3.optString("leftLegColor3", ""), p.leftLegColor);
                p.rightLegColor = hexRgb(bc3.optString("rightLegColor3", ""), p.rightLegColor);
            }

            // Inspect equipped clothing & hair assets so clothed avatars don't look like plain skin mannequins
            long shirtId = 0, pantsId = 0, hairId = 0;
            JSONArray assets = data.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    JSONObject at = a.optJSONObject("assetType");
                    String typeName = at == null ? "" : at.optString("name", "");
                    long id = a.optLong("id", 0);
                    if (id <= 0) continue;
                    if (("Shirt".equalsIgnoreCase(typeName) || "ShirtAccessory".equalsIgnoreCase(typeName) || "TShirt".equalsIgnoreCase(typeName)) && shirtId == 0) {
                        shirtId = id;
                    } else if (("Pants".equalsIgnoreCase(typeName) || "PantsAccessory".equalsIgnoreCase(typeName)) && pantsId == 0) {
                        pantsId = id;
                    } else if (("HairAccessory".equalsIgnoreCase(typeName) || "Hat".equalsIgnoreCase(typeName)) && hairId == 0) {
                        hairId = id;
                    }
                }
            }
            if (shirtId > 0) p.shirtColor = sampleAssetColor(api, shirtId);
            if (pantsId > 0) p.pantsColor = sampleAssetColor(api, pantsId);
            if (hairId > 0) {
                p.hairColor = sampleAssetColor(api, hairId);
                p.hasHair = p.hairColor != null;
            }
            p.loadedFromRoblox = true;
        } catch (Throwable ignored) { }
        return p;
    }

    private static float[] sampleAssetColor(RobloxApi api, long assetId) {
        try {
            JSONArray data = api.json("https://thumbnails.roblox.com/v1/assets?assetIds=" + assetId + "&size=150x150&format=Png&isCircular=false", null, null).optJSONArray("data");
            if (data == null || data.length() == 0) return null;
            JSONObject item = data.getJSONObject(0);
            String url = item.optString("imageUrl", "");
            if (url.isEmpty() || !"Completed".equals(item.optString("state"))) return null;
            Bitmap bmp = api.image(url, 64);
            if (bmp == null) return null;
            long rSum = 0, gSum = 0, bSum = 0, count = 0;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int stepX = Math.max(1, w / 16), stepY = Math.max(1, h / 16);
            for (int y = h / 6; y < h * 5 / 6; y += stepY) {
                for (int x = w / 6; x < w * 5 / 6; x += stepX) {
                    int px = bmp.getPixel(x, y);
                    int alpha = (px >>> 24) & 0xFF;
                    if (alpha < 180) continue;
                    rSum += (px >> 16) & 0xFF;
                    gSum += (px >> 8) & 0xFF;
                    bSum += px & 0xFF;
                    count++;
                }
            }
            bmp.recycle();
            if (count == 0) return null;
            return new float[]{(rSum / (float) count) / 255f, (gSum / (float) count) / 255f, (bSum / (float) count) / 255f};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float clampScale(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float[] hexRgb(String hex, float[] fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        if (clean.length() != 6) return fallback;
        try {
            int c = Integer.parseInt(clean, 16);
            return new float[]{((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f};
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final HashMap<Integer, Integer> BRICK_COLORS = new HashMap<>();
    static {
        BRICK_COLORS.put(1, 0xF2F3F3);
        BRICK_COLORS.put(5, 0xD7C59A);
        BRICK_COLORS.put(9, 0xE8BAC8);
        BRICK_COLORS.put(11, 0x80BBDB);
        BRICK_COLORS.put(18, 0xCC8E69);
        BRICK_COLORS.put(21, 0xC4281C);
        BRICK_COLORS.put(23, 0x0D69AC);
        BRICK_COLORS.put(24, 0xF5CD30);
        BRICK_COLORS.put(26, 0x1B2A35);
        BRICK_COLORS.put(28, 0x287F47);
        BRICK_COLORS.put(37, 0x4B974B);
        BRICK_COLORS.put(38, 0xA05F35);
        BRICK_COLORS.put(101, 0xDA867A);
        BRICK_COLORS.put(102, 0x6E99CA);
        BRICK_COLORS.put(104, 0x6B327C);
        BRICK_COLORS.put(105, 0xE29B40);
        BRICK_COLORS.put(106, 0xDA8541);
        BRICK_COLORS.put(119, 0xA4BD47);
        BRICK_COLORS.put(125, 0xEAB892);
        BRICK_COLORS.put(135, 0x74869D);
        BRICK_COLORS.put(141, 0x27462D);
        BRICK_COLORS.put(151, 0x789082);
        BRICK_COLORS.put(153, 0x957977);
        BRICK_COLORS.put(192, 0x694028);
        BRICK_COLORS.put(194, 0xA3A2A5);
        BRICK_COLORS.put(199, 0x635F62);
        BRICK_COLORS.put(208, 0xE5E4DF);
        BRICK_COLORS.put(217, 0x7C5C46);
        BRICK_COLORS.put(226, 0xFDEA8D);
        BRICK_COLORS.put(301, 0x506D54);
        BRICK_COLORS.put(305, 0x527CAE);
        BRICK_COLORS.put(317, 0x7C9C6B);
        BRICK_COLORS.put(330, 0xFF98DC);
        BRICK_COLORS.put(351, 0xBC9B5D);
        BRICK_COLORS.put(364, 0x5A4C42);
        BRICK_COLORS.put(1001, 0xF8F8F8);
        BRICK_COLORS.put(1002, 0xCDCDCD);
        BRICK_COLORS.put(1003, 0x111111);
        BRICK_COLORS.put(1004, 0xFF0000);
        BRICK_COLORS.put(1006, 0xB480FF);
        BRICK_COLORS.put(1008, 0xC1BE42);
        BRICK_COLORS.put(1009, 0xFFFF00);
        BRICK_COLORS.put(1010, 0x0000FF);
        BRICK_COLORS.put(1011, 0x002060);
        BRICK_COLORS.put(1012, 0x2154B9);
        BRICK_COLORS.put(1013, 0x04AFEC);
        BRICK_COLORS.put(1014, 0xAA5500);
        BRICK_COLORS.put(1015, 0xAA00AA);
        BRICK_COLORS.put(1016, 0xFF66CC);
        BRICK_COLORS.put(1017, 0xFFAF00);
        BRICK_COLORS.put(1018, 0x12EED4);
        BRICK_COLORS.put(1019, 0x00FFFF);
        BRICK_COLORS.put(1020, 0x00FF00);
        BRICK_COLORS.put(1024, 0xAFDDFF);
        BRICK_COLORS.put(1025, 0xFFC9C9);
        BRICK_COLORS.put(1026, 0xB1A7FF);
        BRICK_COLORS.put(1027, 0x9FF3E9);
        BRICK_COLORS.put(1028, 0xCCFFCC);
        BRICK_COLORS.put(1029, 0xFFFFCC);
        BRICK_COLORS.put(1030, 0xFFCC99);
        BRICK_COLORS.put(1031, 0x6225D1);
        BRICK_COLORS.put(1032, 0xFF00BF);
    }

    private static float[] brickRgb(int id, float[] fallback) {
        Integer c = BRICK_COLORS.get(id);
        if (c == null) return fallback;
        return new float[]{((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f};
    }
}
