package dev.falix.studiomobile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 * Decodes Roblox .mesh files (versions 1.00, 1.01, 2.00, 3.00, 4.00, 4.01, 5.00)
 * and streams MeshPart / SpecialMesh assets asynchronously for OpenGL ES 2.0 rendering.
 */
final class RbxMeshDecoder {
    static final class MeshData {
        final float[] positions; // normalized to [-0.5, +0.5] so MeshPart.Size scales cleanly
        final float[] normals;
        final float[] uvs;
        final int vertexCount;

        MeshData(float[] positions, float[] normals, float[] uvs, int vertexCount) {
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.vertexCount = vertexCount;
        }
    }

    static final class GlMesh {
        final FloatBuffer pos, norm, uv;
        final int count;

        GlMesh(MeshData d) {
            this.pos = toBuf(d.positions);
            this.norm = toBuf(d.normals);
            this.uv = toBuf(d.uvs);
            this.count = d.vertexCount;
        }

        private static FloatBuffer toBuf(float[] a) {
            FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            b.put(a).position(0);
            return b;
        }
    }

    private final File cacheDir;
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, MeshData> readyData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GlMesh> glCache = new ConcurrentHashMap<>();
    private final Set<String> requested = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile String apiKey, cookie;

    RbxMeshDecoder(File cacheDir) {
        this.cacheDir = new File(cacheDir, "rbx_meshes");
        if (!this.cacheDir.exists()) this.cacheDir.mkdirs();
    }

    void setCredentials(String apiKey, String cookie) {
        this.apiKey = apiKey;
        this.cookie = cookie;
    }

    /** Returns a GL-ready mesh for contentId, or null while streaming in background. */
    GlMesh getGlMesh(String contentId) {
        if (contentId == null || contentId.isEmpty()) return null;
        String idStr = EngineTextures.assetIdOf(contentId);
        if (idStr == null) return null;
        GlMesh existing = glCache.get(idStr);
        if (existing != null) return existing;
        MeshData data = readyData.remove(idStr);
        if (data != null) {
            GlMesh gm = new GlMesh(data);
            glCache.put(idStr, gm);
            return gm;
        }
        if (requested.add(idStr)) {
            final long assetId;
            try { assetId = Long.parseLong(idStr); } catch (Exception e) { return null; }
            loader.execute(() -> {
                try {
                    File cached = new File(cacheDir, idStr + ".mesh");
                    if (!cached.exists() || cached.length() < 24) {
                        RobloxApi api = new RobloxApi();
                        File dl = api.downloadAssetWithAuth(assetId, apiKey, cookie, cacheDir);
                        if (dl != null && dl.exists()) {
                            if (cached.exists()) cached.delete();
                            dl.renameTo(cached);
                        }
                    }
                    if (cached.exists() && cached.length() >= 24) {
                        byte[] bytes = readAll(cached);
                        MeshData parsed = decode(bytes);
                        if (parsed != null && parsed.vertexCount >= 3) {
                            readyData.put(idStr, parsed);
                        }
                    }
                } catch (Throwable ignored) { }
            });
        }
        return null;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Decodes Roblox .mesh v1.00–v5.00 bytes into triangle arrays normalized to [-0.5, +0.5]. */
    static MeshData decode(byte[] raw) {
        if (raw == null || raw.length < 24) return null;
        try {
            String magic = new String(raw, 0, Math.min(12, raw.length), "US-ASCII");
            if (!magic.startsWith("version ")) return null;
            String ver = magic.substring(8, 12);
            if ("1.00".equals(ver) || "1.01".equals(ver)) {
                return decodeV1(raw, "1.00".equals(ver) ? 0.5f : 1.0f);
            }
            if (ver.startsWith("6.") || ver.startsWith("7.")) {
                return decodeChunked(raw);
            }
            return decodeBinary(raw, ver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MeshData decodeV1(byte[] raw, float scaleMult) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(raw), "US-ASCII"));
        br.readLine(); // version
        String cntLine = br.readLine();
        String dataLine = br.readLine();
        if (cntLine == null || dataLine == null) return null;
        int faceCount = Integer.parseInt(cntLine.trim());
        String body = dataLine.trim();
        if (body.length() < 4) return null;
        String[] vecs = body.substring(1, body.length() - 1).split("\\]\\[");
        if (vecs.length < faceCount * 9) return null;

        int vc = faceCount * 3;
        float[] pos = new float[vc * 3];
        float[] norm = new float[vc * 3];
        float[] uv = new float[vc * 2];
        for (int f = 0; f < faceCount; f++) {
            for (int k = 0; k < 3; k++) {
                int base = f * 9 + k * 3;
                int vi = f * 3 + k;
                String[] p = vecs[base].split(",");
                String[] n = vecs[base + 1].split(",");
                String[] t = vecs[base + 2].split(",");
                pos[vi * 3] = Float.parseFloat(p[0].trim()) * scaleMult;
                pos[vi * 3 + 1] = Float.parseFloat(p[1].trim()) * scaleMult;
                pos[vi * 3 + 2] = Float.parseFloat(p[2].trim()) * scaleMult;
                norm[vi * 3] = Float.parseFloat(n[0].trim());
                norm[vi * 3 + 1] = Float.parseFloat(n[1].trim());
                norm[vi * 3 + 2] = Float.parseFloat(n[2].trim());
                uv[vi * 2] = Float.parseFloat(t[0].trim());
                uv[vi * 2 + 1] = 1f - Float.parseFloat(t[1].trim());
            }
        }
        return normalize(pos, norm, uv, vc);
    }

    private static MeshData decodeBinary(byte[] raw, String ver) {
        int off = 12;
        if (off < raw.length && raw[off] == '\r') off++;
        if (off < raw.length && raw[off] == '\n') off++;
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(off);

        int headerStart = off;
        int headerSize = bb.getShort() & 0xFFFF;
        int vertexSize = 40;
        int faceSize = 12;
        int lodCount = 0;
        int vertexCount;
        int faceCount;
        int boneCount = 0;

        if (ver.startsWith("2.")) {
            vertexSize = bb.get() & 0xFF;
            faceSize = bb.get() & 0xFF;
            vertexCount = bb.getInt();
            faceCount = bb.getInt();
        } else if (ver.startsWith("3.")) {
            vertexSize = bb.get() & 0xFF;
            faceSize = bb.get() & 0xFF;
            bb.getShort(); // lodSize
            lodCount = bb.getShort() & 0xFFFF;
            vertexCount = bb.getInt();
            faceCount = bb.getInt();
        } else if (ver.startsWith("4.") || ver.startsWith("5.")) {
            bb.getShort(); // lodType
            vertexCount = bb.getInt();
            faceCount = bb.getInt();
            lodCount = bb.getShort() & 0xFFFF;
            boneCount = bb.getShort() & 0xFFFF;
            vertexSize = 40;
        } else {
            return null;
        }

        if (vertexCount <= 0 || faceCount <= 0 || vertexCount > 250000 || faceCount > 250000) return null;
        if (vertexSize < 36 || faceSize < 12) return null;

        int posVert = headerStart + headerSize;
        if (posVert + (long) vertexCount * vertexSize > raw.length) return null;

        float[] vPos = new float[vertexCount * 3];
        float[] vNorm = new float[vertexCount * 3];
        float[] vUv = new float[vertexCount * 2];
        for (int i = 0; i < vertexCount; i++) {
            bb.position(posVert + i * vertexSize);
            vPos[i * 3] = bb.getFloat();
            vPos[i * 3 + 1] = bb.getFloat();
            vPos[i * 3 + 2] = bb.getFloat();
            vNorm[i * 3] = bb.getFloat();
            vNorm[i * 3 + 1] = bb.getFloat();
            vNorm[i * 3 + 2] = bb.getFloat();
            vUv[i * 2] = bb.getFloat();
            vUv[i * 2 + 1] = 1f - bb.getFloat();
        }

        int posFaces = posVert + vertexCount * vertexSize;
        if (boneCount > 0) posFaces += vertexCount * 8; // 4 bone indices + 4 bone weights per vertex
        if (posFaces + (long) faceCount * faceSize > raw.length) return null;

        // If LODs are present, use LOD 0 face range (avoids drawing all LODs stacked on top of each other)
        int drawFaces = faceCount;
        int posLods = posFaces + faceCount * faceSize;
        if (lodCount >= 2 && posLods + 8 <= raw.length) {
            bb.position(posLods);
            int lod0Start = bb.getInt();
            int lod0End = bb.getInt();
            if (lod0Start == 0 && lod0End > 0 && lod0End <= faceCount) {
                drawFaces = lod0End;
            }
        }
        // Cap triangle count for mobile GLES2 frame budget
        if (drawFaces > 30000) drawFaces = 30000;

        int outVerts = drawFaces * 3;
        float[] outPos = new float[outVerts * 3];
        float[] outNorm = new float[outVerts * 3];
        float[] outUv = new float[outVerts * 2];
        for (int f = 0; f < drawFaces; f++) {
            bb.position(posFaces + f * faceSize);
            int a = bb.getInt(), b = bb.getInt(), c = bb.getInt();
            if (a < 0 || a >= vertexCount || b < 0 || b >= vertexCount || c < 0 || c >= vertexCount) continue;
            int[] tri = {a, b, c};
            for (int k = 0; k < 3; k++) {
                int srcI = tri[k];
                int dstI = f * 3 + k;
                outPos[dstI * 3] = vPos[srcI * 3];
                outPos[dstI * 3 + 1] = vPos[srcI * 3 + 1];
                outPos[dstI * 3 + 2] = vPos[srcI * 3 + 2];
                outNorm[dstI * 3] = vNorm[srcI * 3];
                outNorm[dstI * 3 + 1] = vNorm[srcI * 3 + 1];
                outNorm[dstI * 3 + 2] = vNorm[srcI * 3 + 2];
                outUv[dstI * 2] = vUv[srcI * 2];
                outUv[dstI * 2 + 1] = vUv[srcI * 2 + 1];
            }
        }
        return normalize(outPos, outNorm, outUv, outVerts);
    }

    /** Parses Roblox .mesh v6.00 / v7.00 tagged chunk stream (COREMESH v1 + LODS). */
    private static MeshData decodeChunked(byte[] raw) {
        int off = 12;
        if (off < raw.length && raw[off] == '\r') off++;
        if (off < raw.length && raw[off] == '\n') off++;
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(off);

        float[] vPos = null, vNorm = null, vUv = null;
        int[] faces = null;
        int vertexCount = 0, faceCount = 0, lod0End = -1;

        while (bb.remaining() >= 16) {
            byte[] tagBytes = new byte[8];
            bb.get(tagBytes);
            String tag = new String(tagBytes).trim();
            int chunkVer = bb.getInt();
            int chunkSize = bb.getInt();
            if (chunkSize < 0 || chunkSize > bb.remaining()) break;
            int nextPos = bb.position() + chunkSize;

            if ("COREMESH".equals(tag) && chunkVer == 1 && chunkSize >= 8) {
                vertexCount = bb.getInt();
                if (vertexCount <= 0 || vertexCount > 250000 || bb.remaining() < vertexCount * 40) break;
                vPos = new float[vertexCount * 3];
                vNorm = new float[vertexCount * 3];
                vUv = new float[vertexCount * 2];
                for (int i = 0; i < vertexCount; i++) {
                    int base = bb.position();
                    vPos[i * 3] = bb.getFloat();
                    vPos[i * 3 + 1] = bb.getFloat();
                    vPos[i * 3 + 2] = bb.getFloat();
                    vNorm[i * 3] = bb.getFloat();
                    vNorm[i * 3 + 1] = bb.getFloat();
                    vNorm[i * 3 + 2] = bb.getFloat();
                    vUv[i * 2] = bb.getFloat();
                    vUv[i * 2 + 1] = 1f - bb.getFloat();
                    bb.position(base + 40);
                }
                if (bb.remaining() >= 4) {
                    faceCount = bb.getInt();
                    if (faceCount > 0 && faceCount <= 250000 && bb.remaining() >= faceCount * 12) {
                        faces = new int[faceCount * 3];
                        for (int f = 0; f < faceCount * 3; f++) faces[f] = bb.getInt();
                    }
                }
            } else if ("LODS".equals(tag) && chunkVer == 1 && chunkSize >= 16) {
                bb.getShort(); // lodType
                bb.get();      // numHighQualityLods
                int count = bb.getInt();
                if (count >= 2 && bb.remaining() >= 8) {
                    int s0 = bb.getInt();
                    int e0 = bb.getInt();
                    if (s0 == 0 && e0 > 0) lod0End = e0;
                }
            }
            bb.position(nextPos);
        }
        if (vPos == null || faces == null || faceCount <= 0) return null;
        int drawFaces = (lod0End > 0 && lod0End <= faceCount) ? lod0End : faceCount;
        if (drawFaces > 30000) drawFaces = 30000;
        int outVerts = drawFaces * 3;
        float[] outPos = new float[outVerts * 3];
        float[] outNorm = new float[outVerts * 3];
        float[] outUv = new float[outVerts * 2];
        for (int f = 0; f < drawFaces; f++) {
            int a = faces[f * 3], b = faces[f * 3 + 1], c = faces[f * 3 + 2];
            if (a < 0 || a >= vertexCount || b < 0 || b >= vertexCount || c < 0 || c >= vertexCount) continue;
            int[] tri = {a, b, c};
            for (int k = 0; k < 3; k++) {
                int sI = tri[k], dI = f * 3 + k;
                outPos[dI * 3] = vPos[sI * 3]; outPos[dI * 3 + 1] = vPos[sI * 3 + 1]; outPos[dI * 3 + 2] = vPos[sI * 3 + 2];
                outNorm[dI * 3] = vNorm[sI * 3]; outNorm[dI * 3 + 1] = vNorm[sI * 3 + 1]; outNorm[dI * 3 + 2] = vNorm[sI * 3 + 2];
                outUv[dI * 2] = vUv[sI * 2]; outUv[dI * 2 + 1] = vUv[sI * 2 + 1];
            }
        }
        return normalize(outPos, outNorm, outUv, outVerts);
    }

    private static MeshData normalize(float[] pos, float[] norm, float[] uv, int vc) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < vc; i++) {
            float x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        float sx = Math.max(1e-4f, maxX - minX);
        float sy = Math.max(1e-4f, maxY - minY);
        float sz = Math.max(1e-4f, maxZ - minZ);
        for (int i = 0; i < vc; i++) {
            pos[i * 3] = (pos[i * 3] - cx) / sx;
            pos[i * 3 + 1] = (pos[i * 3 + 1] - cy) / sy;
            pos[i * 3 + 2] = (pos[i * 3 + 2] - cz) / sz;
        }
        return new MeshData(pos, norm, uv, vc);
    }
}
