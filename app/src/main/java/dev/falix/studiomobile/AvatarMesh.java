package dev.falix.studiomobile;

import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.net.URL;
import java.nio.*;
import java.util.*;

final class AvatarMesh {
    final float[] positions;
    final float[] normals;
    final float[] uvs;
    final int vertexCount;
    final Bitmap textureBitmap;

    AvatarMesh(float[] positions, float[] normals, float[] uvs, int vertexCount, Bitmap textureBitmap) {
        this.positions = positions;
        this.normals = normals;
        this.uvs = uvs;
        this.vertexCount = vertexCount;
        this.textureBitmap = textureBitmap;
    }

    static String rbxCdnUrl(String hash) {
        int i = 31;
        for (int t = 0; t < hash.length(); t++) i ^= hash.charAt(t);
        return "https://t" + (i & 7) + ".rbxcdn.com/" + hash;
    }

    static AvatarMesh fetch(RobloxApi api, long userId) {
        if (api == null || userId <= 0) return null;
        try {
            String manifestUrl = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                JSONObject resp = api.json("https://thumbnails.roblox.com/v1/users/avatar-3d?userId=" + userId, null, null);
                String state = resp.optString("state", "");
                String url = resp.optString("imageUrl", "");
                if ("Completed".equalsIgnoreCase(state) && !url.isEmpty()) {
                    manifestUrl = url;
                    break;
                }
                if (!"Pending".equalsIgnoreCase(state) && !"InReview".equalsIgnoreCase(state)) break;
                Thread.sleep(700);
            }
            if (manifestUrl == null) return null;

            JSONObject manifest = api.json(manifestUrl, null, null);
            String objHash = manifest.optString("obj", "");
            if (objHash.isEmpty()) return null;

            String objUrl = rbxCdnUrl(objHash);
            String objText = downloadText(objUrl);
            if (objText == null || objText.isEmpty()) return null;

            Bitmap tex = null;
            JSONArray textures = manifest.optJSONArray("textures");
            if (textures != null && textures.length() > 0) {
                for (int i = 0; i < textures.length(); i++) {
                    String th = textures.optString(i, "");
                    if (th.isEmpty()) continue;
                    try {
                        tex = api.image(rbxCdnUrl(th), 512);
                        if (tex != null) break;
                    } catch (Throwable ignored) { }
                }
            }
            return parseObj(objText, tex);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String downloadText(String location) throws Exception {
        for (int r = 0; r < 5; r++) {
            URL u = new URL(location);
            if (!RobloxApi.imageHost(u)) return null;
            okhttp3.Request req = new okhttp3.Request.Builder().url(location).get().build();
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
                        if (total > 8 * 1024 * 1024) return null;
                        out.write(buf, 0, n);
                    }
                    bytes = out.toByteArray();
                }
                return new String(bytes, "UTF-8");
            }
        }
        return null;
    }

    static AvatarMesh parseObj(String objText, Bitmap tex) {
        ArrayList<Float> vs = new ArrayList<>();
        ArrayList<Float> vts = new ArrayList<>();
        ArrayList<Float> vns = new ArrayList<>();
        ArrayList<Float> outPos = new ArrayList<>();
        ArrayList<Float> outNorm = new ArrayList<>();
        ArrayList<Float> outUv = new ArrayList<>();

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        BufferedReader br = new BufferedReader(new StringReader(objText));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("v ")) {
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
                    int count = p.length - 1;
                    int[] vi = new int[count], ti = new int[count], ni = new int[count];
                    for (int k = 0; k < count; k++) {
                        String[] idx = p[k + 1].split("/");
                        vi[k] = parseIndex(idx[0], vs.size() / 3);
                        ti[k] = idx.length > 1 && !idx[1].isEmpty() ? parseIndex(idx[1], vts.size() / 2) : -1;
                        ni[k] = idx.length > 2 && !idx[2].isEmpty() ? parseIndex(idx[2], vns.size() / 3) : -1;
                    }
                    for (int k = 1; k + 1 < count; k++) {
                        emitVert(vs, vts, vns, vi[0], ti[0], ni[0], outPos, outNorm, outUv);
                        emitVert(vs, vts, vns, vi[k], ti[k], ni[k], outPos, outNorm, outUv);
                        emitVert(vs, vts, vns, vi[k + 1], ti[k + 1], ni[k + 1], outPos, outNorm, outUv);
                    }
                }
            }
        } catch (Exception ignored) { }

        int vertCount = outPos.size() / 3;
        if (vertCount < 3 || maxY <= minY) return null;

        float cx = (minX + maxX) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float height = maxY - minY;
        float scale = 5.2f / Math.max(0.5f, height);

        float[] posArr = new float[outPos.size()];
        float[] normArr = new float[outNorm.size()];
        float[] uvArr = new float[outUv.size()];
        for (int i = 0; i < vertCount; i++) {
            posArr[i * 3] = (outPos.get(i * 3) - cx) * scale;
            posArr[i * 3 + 1] = (outPos.get(i * 3 + 1) - minY) * scale;
            posArr[i * 3 + 2] = (outPos.get(i * 3 + 2) - cz) * scale;
            normArr[i * 3] = outNorm.get(i * 3);
            normArr[i * 3 + 1] = outNorm.get(i * 3 + 1);
            normArr[i * 3 + 2] = outNorm.get(i * 3 + 2);
            uvArr[i * 2] = outUv.get(i * 2);
            uvArr[i * 2 + 1] = outUv.get(i * 2 + 1);
        }
        return new AvatarMesh(posArr, normArr, uvArr, vertCount, tex);
    }

    private static int parseIndex(String token, int total) {
        int v = Integer.parseInt(token);
        return v < 0 ? total + v : v - 1;
    }

    private static void emitVert(ArrayList<Float> vs, ArrayList<Float> vts, ArrayList<Float> vns,
                                 int vi, int ti, int ni,
                                 ArrayList<Float> outPos, ArrayList<Float> outNorm, ArrayList<Float> outUv) {
        if (vi < 0 || vi * 3 + 2 >= vs.size()) {
            outPos.add(0f); outPos.add(0f); outPos.add(0f);
        } else {
            outPos.add(vs.get(vi * 3)); outPos.add(vs.get(vi * 3 + 1)); outPos.add(vs.get(vi * 3 + 2));
        }
        if (ni >= 0 && ni * 3 + 2 < vns.size()) {
            outNorm.add(vns.get(ni * 3)); outNorm.add(vns.get(ni * 3 + 1)); outNorm.add(vns.get(ni * 3 + 2));
        } else {
            outNorm.add(0f); outNorm.add(1f); outNorm.add(0f);
        }
        if (ti >= 0 && ti * 2 + 1 < vts.size()) {
            outUv.add(vts.get(ti * 2)); outUv.add(vts.get(ti * 2 + 1));
        } else {
            outUv.add(0f); outUv.add(0f);
        }
    }
}
