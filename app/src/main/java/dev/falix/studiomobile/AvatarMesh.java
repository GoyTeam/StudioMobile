package dev.falix.studiomobile;

import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.net.URL;
import java.util.*;

final class AvatarMesh {
    static final int PART_HEAD        = 0;
    static final int PART_UPPER_TORSO = 1;
    static final int PART_LOWER_TORSO = 2;
    static final int PART_L_UPPER_ARM = 3;
    static final int PART_L_LOWER_ARM = 4;
    static final int PART_R_UPPER_ARM = 5;
    static final int PART_R_LOWER_ARM = 6;
    static final int PART_L_UPPER_LEG = 7;
    static final int PART_L_LOWER_LEG = 8;
    static final int PART_R_UPPER_LEG = 9;
    static final int PART_R_LOWER_LEG = 10;

    static final float[][] PART_PIVOTS = {
        { 0.00f, 4.00f, 0.00f}, // 0 HEAD
        { 0.00f, 2.55f, 0.00f}, // 1 UPPER_TORSO
        { 0.00f, 2.00f, 0.00f}, // 2 LOWER_TORSO
        {-1.50f, 3.85f, 0.00f}, // 3 L_UPPER_ARM
        {-1.50f, 2.95f, 0.00f}, // 4 L_LOWER_ARM
        { 1.50f, 3.85f, 0.00f}, // 5 R_UPPER_ARM
        { 1.50f, 2.95f, 0.00f}, // 6 R_LOWER_ARM
        {-0.50f, 1.98f, 0.00f}, // 7 L_UPPER_LEG
        {-0.50f, 1.00f, 0.00f}, // 8 L_LOWER_LEG
        { 0.50f, 1.98f, 0.00f}, // 9 R_UPPER_LEG
        { 0.50f, 1.00f, 0.00f}  // 10 R_LOWER_LEG
    };

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /** Computes smooth 2-bone Linear-Blend Skinning weights for any normalized Roblox avatar vertex (0..5.2 studs). */
    static void computeSkinWeights(float x, float y, float z, byte[] boneA, byte[] boneB, float[] wA, int idx) {
        // Head & hood/hats (smooth neck blend 3.80..4.04)
        if (y >= 3.80f && Math.abs(x) <= 1.28f) {
            float t = smoothstep(3.80f, 4.04f, y);
            boneA[idx] = PART_HEAD;
            boneB[idx] = PART_UPPER_TORSO;
            wA[idx] = t;
            return;
        }
        // Legs (y < 2.04)
        if (y < 2.04f) {
            boolean left = x <= 0f;
            int uLeg = left ? PART_L_UPPER_LEG : PART_R_UPPER_LEG;
            int lLeg = left ? PART_L_LOWER_LEG : PART_R_LOWER_LEG;
            if (y > 1.84f) {
                float hip = smoothstep(1.84f, 2.04f, y);
                boneA[idx] = PART_LOWER_TORSO;
                boneB[idx] = (byte) uLeg;
                wA[idx] = hip;
            } else {
                float knee = smoothstep(0.84f, 1.16f, y);
                boneA[idx] = (byte) uLeg;
                boneB[idx] = (byte) lLeg;
                wA[idx] = knee;
            }
            return;
        }
        // Left Arm (x < -0.94)
        if (x < -0.94f) {
            if (x > -1.12f) {
                float sh = smoothstep(-0.94f, -1.12f, x);
                boneA[idx] = PART_L_UPPER_ARM;
                boneB[idx] = PART_UPPER_TORSO;
                wA[idx] = sh;
            } else {
                float el = smoothstep(2.76f, 3.12f, y);
                boneA[idx] = PART_L_UPPER_ARM;
                boneB[idx] = PART_L_LOWER_ARM;
                wA[idx] = el;
            }
            return;
        }
        // Right Arm (x > 0.94)
        if (x > 0.94f) {
            if (x < 1.12f) {
                float sh = smoothstep(0.94f, 1.12f, x);
                boneA[idx] = PART_R_UPPER_ARM;
                boneB[idx] = PART_UPPER_TORSO;
                wA[idx] = sh;
            } else {
                float el = smoothstep(2.76f, 3.12f, y);
                boneA[idx] = PART_R_UPPER_ARM;
                boneB[idx] = PART_R_LOWER_ARM;
                wA[idx] = el;
            }
            return;
        }
        // Torso & Backpack (smooth waist blend 2.32..2.76)
        float waist = smoothstep(2.32f, 2.76f, y);
        boneA[idx] = PART_UPPER_TORSO;
        boneB[idx] = PART_LOWER_TORSO;
        wA[idx] = waist;
    }

    static final class SubMesh {
        final String materialName;
        final float[] positions;
        final float[] normals;
        final float[] uvs;
        final byte[] boneA;
        final byte[] boneB;
        final float[] weightA;
        final int vertexCount;
        final Bitmap texture;

        SubMesh(String materialName, float[] positions, float[] normals, float[] uvs,
                byte[] boneA, byte[] boneB, float[] weightA, int vertexCount, Bitmap texture) {
            this.materialName = materialName;
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.boneA = boneA;
            this.boneB = boneB;
            this.weightA = weightA;
            this.vertexCount = vertexCount;
            this.texture = texture;
        }
    }

    final ArrayList<SubMesh> subMeshes;
    final int totalVertices;
    final String debugInfo;

    AvatarMesh(ArrayList<SubMesh> subMeshes, int totalVertices, String debugInfo) {
        this.subMeshes = subMeshes;
        this.totalVertices = totalVertices;
        this.debugInfo = debugInfo;
    }

    static String rbxCdnUrl(String hash) {
        int i = 31;
        for (int t = 0; t < hash.length(); t++) i ^= hash.charAt(t);
        return "https://t" + (i & 7) + ".rbxcdn.com/" + hash;
    }

    static AvatarMesh fetch(RobloxApi api, String apiKey, long userId, StringBuilder diag) {
        if (api == null || userId <= 0) return null;
        try {
            String manifestUrl = null;
            String lastState = "none";
            for (int attempt = 0; attempt < 5; attempt++) {
                JSONObject resp = fetch3dThumbnailJson(api, apiKey, userId);
                if (resp == null) break;
                lastState = resp.optString("state", "");
                String url = resp.optString("imageUrl", "");
                if ("Completed".equalsIgnoreCase(lastState) && !url.isEmpty()) {
                    manifestUrl = url;
                    break;
                }
                if (!"Pending".equalsIgnoreCase(lastState) && !"InReview".equalsIgnoreCase(lastState)) break;
                Thread.sleep(750);
            }
            if (manifestUrl == null) {
                if (diag != null) diag.append("3D state=").append(lastState);
                return null;
            }

            JSONObject manifest = api.json(manifestUrl, null, null);
            String objHash = manifest.optString("obj", "");
            String mtlHash = manifest.optString("mtl", "");
            if (objHash.isEmpty()) {
                if (diag != null) diag.append("3D no obj hash");
                return null;
            }

            String objText = downloadCdnText(objHash);
            if (objText == null || objText.isEmpty()) {
                if (diag != null) diag.append("3D obj download failed");
                return null;
            }

            HashMap<String, String> mtlTexHashes = new HashMap<>();
            if (!mtlHash.isEmpty()) {
                String mtlText = downloadCdnText(mtlHash);
                if (mtlText != null) parseMtl(mtlText, mtlTexHashes);
            }

            ArrayList<String> fallbackTexHashes = new ArrayList<>();
            JSONArray textures = manifest.optJSONArray("textures");
            if (textures != null) {
                for (int i = 0; i < textures.length(); i++) {
                    String th = textures.optString(i, "");
                    if (!th.isEmpty()) fallbackTexHashes.add(th);
                }
            }

            HashMap<String, Bitmap> texCache = new HashMap<>();
            Bitmap defaultTex = null;
            for (String h : fallbackTexHashes) {
                defaultTex = downloadCdnBitmap(api, h);
                if (defaultTex != null) {
                    texCache.put(h, defaultTex);
                    break;
                }
            }

            return parseObj(api, objText, mtlTexHashes, texCache, defaultTex);
        } catch (Throwable err) {
            if (diag != null) {
                String msg = err.getMessage() == null ? "" : err.getMessage();
                if (msg.contains("403") || msg.contains("401")) {
                    diag.append("3D needs 'thumbnails:read' on API key");
                } else {
                    diag.append("3D err: ").append(err.getClass().getSimpleName());
                }
            }
            return null;
        }
    }

    private static JSONObject fetch3dThumbnailJson(RobloxApi api, String apiKey, long userId) throws Exception {
        // Since March 2026 Roblox requires an Open Cloud API key (thumbnails:read) for avatar-3d
        if (apiKey != null && !apiKey.isEmpty()) {
            try {
                return api.json("https://apis.roblox.com/thumbnails/v1/users/avatar-3d?userId=" + userId, apiKey, null);
            } catch (Throwable ignored) { }
            // Fallback: send x-api-key directly to thumbnails.roblox.com
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url("https://thumbnails.roblox.com/v1/users/avatar-3d?userId=" + userId)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .header("x-api-key", apiKey)
                    .get().build();
            try (okhttp3.Response res = NetworkRuntime.client().newCall(req).execute()) {
                if (res.isSuccessful() && res.body() != null) {
                    return new JSONObject(res.body().string());
                }
                if (res.code() == 401 || res.code() == 403) {
                    throw new RobloxApi.ApiException(res.code(), "HTTP " + res.code());
                }
            }
        }
        return api.json("https://thumbnails.roblox.com/v1/users/avatar-3d?userId=" + userId, null, null);
    }

    private static void parseMtl(String mtlText, HashMap<String, String> out) {
        String current = null;
        BufferedReader br = new BufferedReader(new StringReader(mtlText));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("newmtl ")) {
                    current = line.substring(7).trim();
                } else if (current != null && line.startsWith("map_Kd ")) {
                    String hash = line.substring(7).trim();
                    int slash = hash.lastIndexOf('/');
                    if (slash >= 0) hash = hash.substring(slash + 1);
                    if (!hash.isEmpty()) out.put(current, hash);
                }
            }
        } catch (Exception ignored) { }
    }

    private static String downloadCdnText(String hash) {
        if (hash.startsWith("http://") || hash.startsWith("https://")) {
            return tryDownloadText(hash);
        }
        String primary = rbxCdnUrl(hash);
        String txt = tryDownloadText(primary);
        if (txt != null) return txt;
        txt = tryDownloadText("https://tr.rbxcdn.com/" + hash);
        if (txt != null) return txt;
        for (int s = 0; s < 8; s++) {
            String url = "https://t" + s + ".rbxcdn.com/" + hash;
            if (url.equals(primary)) continue;
            txt = tryDownloadText(url);
            if (txt != null) return txt;
        }
        return null;
    }

    private static Bitmap downloadCdnBitmap(RobloxApi api, String hash) {
        if (hash.startsWith("http://") || hash.startsWith("https://")) {
            try { return api.image(hash, 512); } catch (Throwable ignored) { return null; }
        }
        String primary = rbxCdnUrl(hash);
        try {
            Bitmap b = api.image(primary, 512);
            if (b != null) return b;
        } catch (Throwable ignored) { }
        try {
            Bitmap b = api.image("https://tr.rbxcdn.com/" + hash, 512);
            if (b != null) return b;
        } catch (Throwable ignored) { }
        for (int s = 0; s < 8; s++) {
            String url = "https://t" + s + ".rbxcdn.com/" + hash;
            if (url.equals(primary)) continue;
            try {
                Bitmap b = api.image(url, 512);
                if (b != null) return b;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static String tryDownloadText(String location) {
        try {
            for (int r = 0; r < 5; r++) {
                URL u = new URL(location);
                if (!RobloxApi.imageHost(u)) return null;
                okhttp3.Request req = new okhttp3.Request.Builder().url(location).header("User-Agent", "Mozilla/5.0").get().build();
                try (okhttp3.Response res = NetworkRuntime.client().newCall(req).execute()) {
                    if (res.code() >= 300 && res.code() < 400) {
                        String next = res.header("Location");
                        if (next == null) return null;
                        location = new URL(u, next).toString();
                        continue;
                    }
                    if (!res.isSuccessful() || res.body() == null) return null;
                    byte[] bytes;
                    try (InputStream in = res.body().byteStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n; long total = 0;
                        while ((n = in.read(buf)) != -1) {
                            total += n;
                            if (total > 10 * 1024 * 1024) return null;
                            out.write(buf, 0, n);
                        }
                        bytes = out.toByteArray();
                    }
                    return new String(bytes, "UTF-8");
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static final class Bucket {
        final ArrayList<Float> pos = new ArrayList<>();
        final ArrayList<Float> norm = new ArrayList<>();
        final ArrayList<Float> uv = new ArrayList<>();
    }

    private static AvatarMesh parseObj(RobloxApi api, String objText, HashMap<String, String> mtlTexHashes,
                                       HashMap<String, Bitmap> texCache, Bitmap defaultTex) {
        ArrayList<Float> vs = new ArrayList<>();
        ArrayList<Float> vts = new ArrayList<>();
        ArrayList<Float> vns = new ArrayList<>();
        LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>();
        String curMtl = "default";
        buckets.put(curMtl, new Bucket());

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        BufferedReader br = new BufferedReader(new StringReader(objText));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("usemtl ")) {
                    curMtl = line.substring(7).trim();
                    if (!buckets.containsKey(curMtl)) buckets.put(curMtl, new Bucket());
                } else if (line.startsWith("v ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 4) {
                        float x = Float.parseFloat(p[1]);
                        float y = Float.parseFloat(p[2]);
                        float z = Float.parseFloat(p[3]);
                        vs.add(x); vs.add(y); vs.add(z);
                        if (x < minX) minX = x; if (x > maxX) maxX = x;
                        if (y < minY) minY = y; if (y > maxY) maxY = y;
                        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
                    }
                } else if (line.startsWith("vt ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 3) {
                        vts.add(Float.parseFloat(p[1]));
                        vts.add(1.0f - Float.parseFloat(p[2]));
                    }
                } else if (line.startsWith("vn ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 4) {
                        vns.add(Float.parseFloat(p[1]));
                        vns.add(Float.parseFloat(p[2]));
                        vns.add(Float.parseFloat(p[3]));
                    }
                } else if (line.startsWith("f ")) {
                    String[] p = line.split("\\s+");
                    if (p.length < 4) continue;
                    Bucket b = buckets.get(curMtl);
                    int count = p.length - 1;
                    int[] vi = new int[count], ti = new int[count], ni = new int[count];
                    for (int k = 0; k < count; k++) {
                        String[] idx = p[k + 1].split("/");
                        vi[k] = parseIndex(idx[0], vs.size() / 3);
                        ti[k] = idx.length > 1 && !idx[1].isEmpty() ? parseIndex(idx[1], vts.size() / 2) : -1;
                        ni[k] = idx.length > 2 && !idx[2].isEmpty() ? parseIndex(idx[2], vns.size() / 3) : -1;
                    }
                    for (int k = 1; k + 1 < count; k++) {
                        emitVert(vs, vts, vns, vi[0], ti[0], ni[0], b);
                        emitVert(vs, vts, vns, vi[k], ti[k], ni[k], b);
                        emitVert(vs, vts, vns, vi[k + 1], ti[k + 1], ni[k + 1], b);
                    }
                }
            }
        } catch (Exception ignored) { }

        if (maxY <= minY) return null;
        float cx = (minX + maxX) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float height = maxY - minY;
        float scale = 5.2f / Math.max(0.5f, height);

        ArrayList<SubMesh> subMeshes = new ArrayList<>();
        int totalVerts = 0;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket b = entry.getValue();
            int vc = b.pos.size() / 3;
            if (vc < 3) continue;
            Bitmap tex = defaultTex;
            String hash = mtlTexHashes.get(entry.getKey());
            if (hash != null && !hash.isEmpty()) {
                Bitmap cached = texCache.get(hash);
                if (cached == null) {
                    cached = downloadCdnBitmap(api, hash);
                    if (cached != null) texCache.put(hash, cached);
                }
                if (cached != null) tex = cached;
            }
            // Keep mesh 100% watertight and compute smooth per-vertex 2-bone skinning weights
            float[] posArr = new float[b.pos.size()];
            float[] normArr = new float[b.norm.size()];
            float[] uvArr = new float[b.uv.size()];
            byte[] boneA = new byte[vc];
            byte[] boneB = new byte[vc];
            float[] weightA = new float[vc];
            for (int i = 0; i < vc; i++) {
                float vx = (b.pos.get(i * 3) - cx) * scale;
                float vy = (b.pos.get(i * 3 + 1) - minY) * scale;
                float vz = (b.pos.get(i * 3 + 2) - cz) * scale;
                posArr[i * 3] = vx;
                posArr[i * 3 + 1] = vy;
                posArr[i * 3 + 2] = vz;
                normArr[i * 3] = b.norm.get(i * 3);
                normArr[i * 3 + 1] = b.norm.get(i * 3 + 1);
                normArr[i * 3 + 2] = b.norm.get(i * 3 + 2);
                uvArr[i * 2] = b.uv.get(i * 2);
                uvArr[i * 2 + 1] = b.uv.get(i * 2 + 1);
                computeSkinWeights(vx, vy, vz, boneA, boneB, weightA, i);
            }
            subMeshes.add(new SubMesh(entry.getKey(), posArr, normArr, uvArr, boneA, boneB, weightA, vc, tex));
            totalVerts += vc;
        }
        if (subMeshes.isEmpty()) return null;
        return new AvatarMesh(subMeshes, totalVerts, subMeshes.size() + " mats, " + totalVerts + " verts");
    }

    private static int parseIndex(String token, int total) {
        int v = Integer.parseInt(token);
        return v < 0 ? total + v : v - 1;
    }

    private static void emitVert(ArrayList<Float> vs, ArrayList<Float> vts, ArrayList<Float> vns,
                                 int vi, int ti, int ni, Bucket b) {
        if (vi < 0 || vi * 3 + 2 >= vs.size()) {
            b.pos.add(0f); b.pos.add(0f); b.pos.add(0f);
        } else {
            b.pos.add(vs.get(vi * 3)); b.pos.add(vs.get(vi * 3 + 1)); b.pos.add(vs.get(vi * 3 + 2));
        }
        if (ni >= 0 && ni * 3 + 2 < vns.size()) {
            b.norm.add(vns.get(ni * 3)); b.norm.add(vns.get(ni * 3 + 1)); b.norm.add(vns.get(ni * 3 + 2));
        } else {
            b.norm.add(0f); b.norm.add(1f); b.norm.add(0f);
        }
        if (ti >= 0 && ti * 2 + 1 < vts.size()) {
            b.uv.add(vts.get(ti * 2)); b.uv.add(vts.get(ti * 2 + 1));
        } else {
            b.uv.add(0f); b.uv.add(0f);
        }
    }
}
