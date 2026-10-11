package dev.falix.studiomobile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Textures for the viewport.
 *
 * Roblox bakes its material textures into the client (they are not downloadable assets), so this
 * build generates the same *kind* of surface detail procedurally: a greyscale atlas that the part
 * colour is multiplied by, exactly like Roblox multiplies its material maps by the part colour.
 * The atlas holds one tile per {@link EngineMaterial}, one per {@link #SURFACE_TILES surface type}
 * (Studs, Inlet, Universal, Hinge, Motor, SteppingMotor, Weld, Glue) and the spawn pad.
 *
 * Real assets *are* streamed: `Texture`, `Decal` and `SurfaceAppearance.ColorMap` content ids,
 * `MeshPart.TextureID` and the six `Sky` faces. Content is resolved with
 * `rbxassetid://<id>`/`<id>`/URL, fetched (Open Cloud asset delivery when a creator key is present,
 * otherwise Roblox's public thumbnail CDN), decoded once and cached on disk. Fetches happen off the
 * GL thread; {@link #uploadPending()} turns finished bitmaps into GL textures on the GL thread.
 */
final class EngineTextures {
    /** Materials and surfaces share one 8x8 atlas of 128 px tiles. */
    static final int TILE_SIZE = 128;
    static final int ATLAS_TILES = 8;
    static final int ATLAS_SIZE = TILE_SIZE * ATLAS_TILES;

    /** SurfaceType enum order: Smooth, Glue, Weld, Studs, Inlet, Universal, Hinge, Motor, SteppingMotor. */
    static final String[] SURFACE_TILES = {"Smooth", "Glue", "Weld", "Studs", "Inlet", "Universal", "Hinge", "Motor", "SteppingMotor"};

    private static final String[] TILE_NAMES = {
            "Flat", "Plastic", "SmoothPlastic", "Neon", "Wood", "WoodPlanks", "Marble", "Slate",
            "Concrete", "Granite", "Brick", "Pebble", "Cobblestone", "Rock", "Sandstone", "Basalt",
            "CrackedLava", "Limestone", "Pavement", "CorrodedMetal", "DiamondPlate", "Foil", "Metal",
            "Grass", "LeafyGrass", "Sand", "Fabric", "Snow", "Mud", "Ground", "Asphalt", "Salt",
            "Ice", "Glacier", "Glass", "ForceField", "Cardboard", "Carpet", "CeramicTiles",
            "ClayRoofTiles", "RoofShingles", "Leather", "Plaster", "Rubber", "Water",
            "Studs", "Inlet", "Universal", "Hinge", "Motor", "SteppingMotor", "Weld", "Glue", "SpawnPad"
    };

    private final File cacheDir;
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final Object lock = new Object();
    private final HashMap<String, Integer> assetTextures = new HashMap<>();     // content -> GL texture (0 = loading)
    private final HashSet<String> requested = new HashSet<>();
    private final ArrayDeque<Object[]> pending = new ArrayDeque<>();            // {content, Bitmap}
    private final HashMap<String, Integer> tileIndex = new HashMap<>();
    private final HashMap<Integer, Integer> materialTile = new HashMap<>();
    private final float[] tileStuds = new float[TILE_NAMES.length];
    private int atlasTexture;
    private int skyCube;
    private int skyFaces;
    private volatile String apiKey;

    EngineTextures(File cacheDir) {
        this.cacheDir = cacheDir;
        for (int i = 0; i < TILE_NAMES.length; i++) tileIndex.put(TILE_NAMES[i], i);
        // Defer the very first Paint so the JVM test shim (which bumps into android.graphics stubs)
        // never trips on the first static in a class the runner only interrogates symbolically.

        for (int id : EngineMaterial.PICKER_IDS) materialTile.put(id, tileOf(EngineMaterial.nameOf(id)));
        materialTile.put(1792, tileOf("Flat"));
        materialTile.put(2048, tileOf("Water"));
    }

    private static boolean isSurface(String name) {
        for (String surface : SURFACE_TILES) if (surface.equals(name)) return true;
        return false;
    }

    /**
     * Studs covered by one tile, which is how Roblox tiles its textures: material maps repeat every
     * four studs, surface types (Studs, Inlet, ...) once per stud, and a spawn pad covers its face.
     */
    static float studsForName(String name) {
        return name.equals("SpawnPad") ? 6 : isSurface(name) ? 1 : 4;
    }

    /** Open Cloud asset delivery needs the creator key; without one the public CDN is used. */
    void setApiKey(String key) { this.apiKey = key; }

    int tileOf(String name) {
        Integer index = tileIndex.get(name);
        return index == null ? 0 : index;
    }

    /** Atlas tile for an `Enum.Material` value (unknown values fall back to Plastic, like Roblox). */
    int materialTile(int material) {
        Integer tile = materialTile.get(material);
        if (tile != null) return tile;
        return tileOf(EngineMaterial.nameOf(material));
    }

    /** Atlas tile for a `SurfaceType` value; 0 (Smooth) is the flat tile. */
    int surfaceTile(int surfaceType) {
        if (surfaceType < 0 || surfaceType >= SURFACE_TILES.length) return 0;
        return tileOf(SURFACE_TILES[surfaceType]);
    }

    int spawnTile() { return tileOf("SpawnPad"); }

    float tileStuds(int tile) {
        return tile >= 0 && tile < tileStuds.length && tileStuds[tile] > 0 ? tileStuds[tile] : 4;
    }

    int atlasTexture() { return atlasTexture; }
    int skyCubeTexture() { return skyCube; }

    /** Human-readable pipeline state for the editor's engine dialog. */
    String note() {
        int ready = 0, loading = 0;
        synchronized (lock) {
            for (Integer id : assetTextures.values()) {
                if (id != null && id != 0) ready++;
                else loading++;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Materials: ").append(TILE_NAMES.length).append(" surface tiles (Enum.Material and SurfaceType) baked into one ")
                .append(ATLAS_SIZE).append("px atlas, world aligned per face like Roblox tiles them.");
        if (ready + loading > 0) {
            out.append(" Streamed asset textures: ").append(ready).append(" ready");
            if (loading > 0) out.append(", ").append(loading).append(" still downloading");
            out.append('.');
        } else {
            out.append(" No Texture/Decal/SurfaceAppearance assets in this place yet.");
        }
        if (skyCube != 0) out.append(" Sky cubemap applied from the place's Sky.Skybox* assets.");
        else if (skyRequested) out.append(" Sky cubemap is waiting for all six Skybox faces.");
        return out.toString();
    }

    // ------------------------------------------------------------------ atlas (GL thread)
    /** Builds the procedural atlas. Called once when the GL context is created. */
    void buildAtlas() {
        Bitmap atlas = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(atlas);
        canvas.drawColor(Color.WHITE);
        for (int i = 0; i < TILE_NAMES.length; i++) {
            canvas.save();
            canvas.translate((i % ATLAS_TILES) * TILE_SIZE, (i / ATLAS_TILES) * TILE_SIZE);
            canvas.clipRect(0, 0, TILE_SIZE, TILE_SIZE);
            drawTile(canvas, TILE_NAMES[i], i);
            canvas.restore();
        }
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        atlasTexture = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, atlas, 0);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        atlas.recycle();
    }

    // ------------------------------------------------------------------ asset streaming
    /** `rbxassetid://123`, `rbxasset://`, a bare id or a URL -> the asset id, or null for a URL. */
    static String assetIdOf(String content) {
        if (content == null) return null;
        String value = content.trim();
        if (value.isEmpty()) return null;
        int marker = value.toLowerCase(Locale.US).indexOf("rbxassetid://");
        if (marker >= 0) value = value.substring(marker + 13);
        else if (isRemoteUrl(value)) {
            // Older places store `http://www.roblox.com/asset/?id=77`; the id still streams the image.
            int query = value.toLowerCase(Locale.US).indexOf("id=");
            if (query < 0) return null;
            value = value.substring(query + 3);
        }
        int cut = 0;
        while (cut < value.length() && Character.isDigit(value.charAt(cut))) cut++;
        if (cut == 0) return null;
        return value.substring(0, cut);
    }

    static boolean isRemoteUrl(String content) {
        return content != null && (content.startsWith("http://") || content.startsWith("https://"));
    }

    /**
     * GL texture for a content string, or 0 while it is still loading (or unavailable).
     * Safe to call every frame: each content is requested once.
     */
    int assetTexture(String content) {
        if (content == null || content.isEmpty()) return 0;
        synchronized (lock) {
            Integer id = assetTextures.get(content);
            if (id != null) return id;
            if (requested.add(content)) {
                assetTextures.put(content, 0);
                loader.execute(() -> {
                    try {
                        byte[] bytes = fetch(content);
                        if (bytes == null) return;
                        Bitmap bitmap = decode(bytes);
                        if (bitmap == null) return;
                        synchronized (lock) { pending.add(new Object[]{content, bitmap}); }
                    } catch (Throwable ignored) { }
                });
            }
            return 0;
        }
    }

    /** Uploads finished bitmaps and, once all six faces are in, the sky cubemap. GL thread only. */
    void uploadPending() {
        ArrayDeque<Object[]> ready = new ArrayDeque<>();
        synchronized (lock) {
            while (!pending.isEmpty()) ready.add(pending.poll());
        }
        for (Object[] entry : ready) {
            String content = (String) entry[0];
            Bitmap bitmap = (Bitmap) entry[1];
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
            int skyFace = skyFaceOf(content);
            if (skyFace >= 0) skyBitmaps[skyFace] = bitmap;      // still needed for the cubemap
            else bitmap.recycle();
            synchronized (lock) { assetTextures.put(content, ids[0]); }
        }
        buildSkyCube();
    }

    private int skyFaceOf(String content) {
        for (int i = 0; i < 6; i++) if (content.equals(skyContent[i])) return i;
        return -1;
    }

    /** Builds the cubemap once all six Sky faces have decoded; a half-textured sky is never shown. */
    private void buildSkyCube() {
        if (skyCube != 0 || !skyRequested) return;
        for (int i = 0; i < 6; i++) if (skyBitmaps[i] == null) return;
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, ids[0]);
        for (int i = 0; i < 6; i++) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, skyBitmaps[i], 0);
            skyBitmaps[i].recycle();
            skyBitmaps[i] = null;
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        skyCube = ids[0];
    }

    private final String[] skyContent = new String[6];
    private final Bitmap[] skyBitmaps = new Bitmap[6];
    private boolean skyRequested;

    /**
     * Requests the six `Sky` faces in GL cube order: +X, -X, +Y, -Y, +Z, -Z, which is Roblox's
     * Right, Left, Up, Down, Front(Ft) and Back(Bk). Until every face is decoded the procedural sky
     * is drawn instead.
     */
    void requestSky(String[] facesInGlOrder) {
        if (facesInGlOrder == null || facesInGlOrder.length < 6) return;
        boolean any = false;
        for (int i = 0; i < 6; i++) {
            skyContent[i] = facesInGlOrder[i];
            if (facesInGlOrder[i] != null && !facesInGlOrder[i].isEmpty()) { any = true; assetTexture(facesInGlOrder[i]); }
        }
        skyRequested = skyRequested || any;
    }

    // ------------------------------------------------------------------ fetching
    private byte[] fetch(String content) throws Exception {
        String id = assetIdOf(content);
        if (id == null) {
            if (!isRemoteUrl(content)) return null;
            return http(content, 3);
        }
        byte[] cached = readCache(id);
        if (cached != null) return cached;
        if (apiKey != null && !apiKey.isEmpty()) {
            try {
                byte[] direct = assetDelivery(id);
                if (direct != null) { writeCache(id, direct); return direct; }
            } catch (Throwable ignored) { }
        }
        String thumbnail = thumbnailUrl(id);
        if (thumbnail == null) return null;
        byte[] bytes = http(thumbnail, 3);
        if (bytes != null) writeCache(id, bytes);
        return bytes;
    }

    /** Open Cloud asset delivery: returns a signed location which still has to be downloaded. */
    private byte[] assetDelivery(String id) throws Exception {
        String body = new String(http("https://apis.roblox.com/asset-delivery-api/v1/assetId/" + id, 0), "UTF-8");
        JSONObject json = new JSONObject(body);
        String location = json.optString("location", "");
        if (location.isEmpty()) return null;
        return http(location, 3);
    }

    /** Public thumbnail CDN: works without credentials, which keeps textures available offline-of-a-key. */
    private String thumbnailUrl(String id) throws Exception {
        String body = new String(http("https://thumbnails.roblox.com/v1/assets?assetIds=" + id + "&size=420x420&format=Png&isCircular=false", 0), "UTF-8");
        JSONArray data = new JSONObject(body).optJSONArray("data");
        if (data == null || data.length() == 0) return null;
        JSONObject entry = data.getJSONObject(0);
        if (!"Completed".equals(entry.optString("state"))) return null;
        String url = entry.optString("imageUrl", "");
        return url.isEmpty() ? null : url;
    }

    private byte[] http(String url, int followRedirects) throws Exception {
        for (int attempt = 0; attempt <= followRedirects; attempt++) {
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url)
                    .header("User-Agent", "StudioMobile/0.6 (+https://github.com/GoyTeam/StudioMobile)");
            if (apiKey != null && !apiKey.isEmpty()) builder.header("x-api-key", apiKey);
            okhttp3.Response response = NetworkRuntime.client().newCall(builder.build()).execute();
            try {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location == null) return null;
                    url = location.startsWith("http") ? location : new java.net.URL(new java.net.URL(url), location).toString();
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) return null;
                long length = response.body().contentLength();
                if (length > 24L * 1024 * 1024) return null;
                try (InputStream in = response.body().byteStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int n; long total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > 24L * 1024 * 1024) return null;
                        out.write(buffer, 0, n);
                    }
                    return out.toByteArray();
                }
            } finally {
                response.close();
            }
        }
        return null;
    }

    /** Decodes with a memory cap: textures are downscaled to at most 1024 px. */
    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private File cacheFile(String id) {
        File dir = new File(cacheDir, "textures");
        return new File(dir, id + ".png");
    }

    private byte[] readCache(String id) {
        File file = cacheFile(id);
        if (!file.isFile() || file.length() == 0) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (Exception ignored) { return null; }
    }

    private void writeCache(String id, byte[] bytes) {
        File file = cacheFile(id);
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;
        try (OutputStream out = new FileOutputStream(file)) { out.write(bytes); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ procedural tiles

    private static Paint PAINT;
    private static void ensurePaint() {
        if (PAINT == null) PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    private static void fill(Canvas c, int grey) {
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(Color.rgb(grey, grey, grey));
        c.drawRect(0, 0, TILE_SIZE, TILE_SIZE, PAINT);
    }

    private static void dot(Canvas c, float x, float y, float r, int grey) {
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(Color.rgb(grey, grey, grey));
        c.drawCircle(x, y, r, PAINT);
    }

    private static void line(Canvas c, float x1, float y1, float x2, float y2, float width, int grey) {
        PAINT.setStyle(Paint.Style.STROKE);
        PAINT.setStrokeWidth(width);
        PAINT.setColor(Color.rgb(grey, grey, grey));
        c.drawLine(x1, y1, x2, y2, PAINT);
    }

    private static void rect(Canvas c, float l, float t, float r, float b, int grey) {
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(Color.rgb(grey, grey, grey));
        c.drawRect(l, t, r, b, PAINT);
    }

    /** Deterministic noise so every device draws the same atlas. */
    static float random(long seed) {
        long value = seed * 6364136223846793005L + 1442695040888963407L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (value >>> 11) / (float) (1L << 53);
    }

    private static void grain(Canvas c, int seed, int base, int spread, int count) {
        for (int i = 0; i < count; i++) {
            float x = random(seed + i) * TILE_SIZE, y = random(seed + i * 31 + 7) * TILE_SIZE;
            int grey = clampByte(base + (int) ((random(seed + i * 17 + 3) - 0.5f) * spread));
            dot(c, x, y, 1 + random(seed + i * 5) * 2.2f, grey);
        }
    }

    private static int clampByte(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    void drawTile(Canvas c, String name, int seed) {
        ensurePaint();
        switch (name) {
            case "Flat": {
                fill(c, 244);
                break;
            }
            case "Plastic": {
                fill(c, 238);
                break;
            }
            case "SmoothPlastic": {
                fill(c, 244);
                break;
            }
            case "Neon": {
                fill(c, 255);
                break;
            }
            case "Wood": {
                fill(c, 200);
                for (int row = 0; row < 4; row++) {
                    float y = row * 32;
                    float offset = (row % 2) == 0 ? 0 : 32;
                    for (int col = -1; col < 3; col++) {
                        float x = col * 64 + offset;
                        rect(c, x + 1.5f, y + 1.5f, x + 62.5f, y + 30.5f, 188 + (int) (random(seed + row * 7 + col) * 16));
                        line(c, x + 1.5f, y + 1.5f, x + 62.5f, y + 1.5f, 1.4f, 156);
                    }
                    line(c, 0, y + 31, TILE_SIZE, y + 31, 2f, 150);
                }
                break;
            }
            case "WoodPlanks": {
                fill(c, 190);
                for (int row = 0; row < 3; row++) {
                    float y = row * 42;
                    rect(c, 2, y + 2, TILE_SIZE - 2, y + 40, 178 + (int) (random(seed + row) * 12));
                    line(c, 2, y + 2, TILE_SIZE - 2, y + 2, 1.3f, 148);
                    line(c, 2, y + 40, TILE_SIZE - 2, y + 40, 1.3f, 148);
                }
                line(c, 0, 0, 0, TILE_SIZE, 4f, 140);
                line(c, TILE_SIZE, 0, TILE_SIZE, TILE_SIZE, 4f, 140);
                break;
            }
            case "Marble": {
                int base = 246;
                fill(c, base);
                grain(c, 77 + seed, base - 8, 26, 300);
                for (int i = 0; i < 7; i++) {
                    float y = random(seed + i * 9) * TILE_SIZE;
                    line(c, 0, y, TILE_SIZE, y + (random(i + seed) - 0.5f) * 26, 1 + random(i) * 2f, base - 26);
                }
                break;
            }
            case "Slate": {
                int base = 214;
                fill(c, base);
                grain(c, 101 + seed, base - 6, 34, 520);
                break;
            }
            case "Concrete": {
                int base = 214;
                fill(c, base);
                grain(c, 101 + seed, base - 6, 34, 520);
                break;
            }
            case "Granite": {
                int base = 206;
                fill(c, base);
                grain(c, 77 + seed, base - 8, 26, 300);
                for (int i = 0; i < 7; i++) {
                    float y = random(seed + i * 9) * TILE_SIZE;
                    line(c, 0, y, TILE_SIZE, y + (random(i + seed) - 0.5f) * 26, 1 + random(i) * 2f, base - 26);
                }
                break;
            }
            case "Brick": {
                fill(c, 232);
                for (int row = 0; row < 4; row++) {
                    float y = row * 32;
                    float offset = (row % 2) == 0 ? 0 : 32;
                    for (int col = -1; col < 3; col++) {
                        float x = col * 64 + offset;
                        rect(c, x + 1.5f, y + 1.5f, x + 62.5f, y + 30.5f, 226 + (int) (random(seed + row * 7 + col) * 16));
                        line(c, x + 1.5f, y + 1.5f, x + 62.5f, y + 1.5f, 1.4f, 196);
                    }
                    line(c, 0, y + 31, TILE_SIZE, y + 31, 2f, 190);
                }
                break;
            }
            case "Pebble": {
                fill(c, 208);
                for (int i = 0; i < 26; i++) {
                    float x = random(seed + i) * TILE_SIZE, y = random(seed + i * 13 + 5) * TILE_SIZE;
                    float w = 14 + random(seed + i * 3) * 24, h = 12 + random(seed + i * 11) * 20;
                    PAINT.setStyle(Paint.Style.FILL);
                    PAINT.setColor(Color.rgb(200 + (int) (random(i + seed) * 44), 200 + (int) (random(i) * 40), 200 + (int) (random(i * 2 + seed) * 36)));
                    c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 6, 6, PAINT);
                    PAINT.setStyle(Paint.Style.STROKE);
                    PAINT.setStrokeWidth(1.2f);
                    PAINT.setColor(Color.rgb(150, 150, 150));
                    c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 6, 6, PAINT);
                }
                break;
            }
            case "Cobblestone": {
                fill(c, 208);
                for (int i = 0; i < 26; i++) {
                    float x = random(seed + i) * TILE_SIZE, y = random(seed + i * 13 + 5) * TILE_SIZE;
                    float w = 14 + random(seed + i * 3) * 24, h = 12 + random(seed + i * 11) * 20;
                    PAINT.setStyle(Paint.Style.FILL);
                    PAINT.setColor(Color.rgb(200 + (int) (random(i + seed) * 44), 200 + (int) (random(i) * 40), 200 + (int) (random(i * 2 + seed) * 36)));
                    c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 6, 6, PAINT);
                    PAINT.setStyle(Paint.Style.STROKE);
                    PAINT.setStrokeWidth(1.2f);
                    PAINT.setColor(Color.rgb(150, 150, 150));
                    c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 6, 6, PAINT);
                }
                break;
            }
            case "Rock": {
                fill(c, 208);
                for (int i = 0; i < 26; i++) {
                    float x = random(seed + i) * TILE_SIZE, y = random(seed + i * 13 + 5) * TILE_SIZE;
                    float w = 14 + random(seed + i * 3) * 24, h = 12 + random(seed + i * 11) * 20;
                    PAINT.setStyle(Paint.Style.FILL);
                    PAINT.setColor(Color.rgb(200 + (int) (random(i + seed) * 44), 200 + (int) (random(i) * 40), 200 + (int) (random(i * 2 + seed) * 36)));
                    c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 6, 6, PAINT);
                    PAINT.setStyle(Paint.Style.STROKE);
                    PAINT.setStrokeWidth(1.2f);
                    PAINT.setColor(Color.rgb(150, 150, 150));
                    c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 6, 6, PAINT);
                }
                break;
            }
            case "Sandstone": {
                int base = 228;
                fill(c, base);
                grain(c, 77 + seed, base - 8, 26, 300);
                for (int i = 0; i < 7; i++) {
                    float y = random(seed + i * 9) * TILE_SIZE;
                    line(c, 0, y, TILE_SIZE, y + (random(i + seed) - 0.5f) * 26, 1 + random(i) * 2f, base - 26);
                }
                break;
            }
            case "Limestone": {
                int base = 228;
                fill(c, base);
                grain(c, 77 + seed, base - 8, 26, 300);
                for (int i = 0; i < 7; i++) {
                    float y = random(seed + i * 9) * TILE_SIZE;
                    line(c, 0, y, TILE_SIZE, y + (random(i + seed) - 0.5f) * 26, 1 + random(i) * 2f, base - 26);
                }
                break;
            }
            case "CrackedLava": {
                fill(c, 64);
                for (int i = 0; i < 14; i++) {
                    float x = random(seed + i) * TILE_SIZE, y = random(seed + i * 5 + 2) * TILE_SIZE;
                    line(c, x, y, x + (random(i) - 0.5f) * 60, y + (random(i * 3) - 0.5f) * 60, 2 + random(i) * 3f, 150 + (int) (random(i) * 70));
                }
                grain(c, 200 + seed, 70, 40, 260);
                break;
            }
            case "Basalt": {
                int base = 214;
                fill(c, base);
                grain(c, 101 + seed, base - 6, 34, 520);
                break;
            }
            case "Pavement": {
                int base = 214;
                fill(c, base);
                grain(c, 101 + seed, base - 6, 34, 520);
                break;
            }
            case "Asphalt": {
                int base = 120;
                fill(c, base);
                grain(c, 101 + seed, base - 6, 34, 520);
                break;
            }
            case "Ground": {
                int base = 214;
                fill(c, base);
                grain(c, 101 + seed, base - 6, 34, 520);
                break;
            }
            case "Salt": {
                int base = 240;
                fill(c, base);
                grain(c, 101 + seed, base - 6, 34, 520);
                break;
            }
            case "Sand": {
                fill(c, 238);
                grain(c, 55 + seed, 236, 22, 700);
                for (int i = 0; i < 5; i++) line(c, 0, random(seed + i) * TILE_SIZE, TILE_SIZE, random(seed + i) * TILE_SIZE, 1.2f, 226);
                break;
            }
            case "Grass": {
                int base = 196;
                fill(c, base);
                grain(c, 33 + seed, base, 46, 900);
                for (int i = 0; i < 130; i++) {
                    float x = random(seed + i * 3) * TILE_SIZE, y = random(seed + i * 7 + 1) * TILE_SIZE;
                    line(c, x, y, x + (random(i) - 0.5f) * 5, y - 3 - random(i * 2) * 5, 1.2f, base - 34);
                }
                break;
            }
            case "LeafyGrass": {
                int base = 196;
                fill(c, base);
                grain(c, 33 + seed, base, 46, 900);
                for (int i = 0; i < 130; i++) {
                    float x = random(seed + i * 3) * TILE_SIZE, y = random(seed + i * 7 + 1) * TILE_SIZE;
                    line(c, x, y, x + (random(i) - 0.5f) * 5, y - 3 - random(i * 2) * 5, 1.2f, base - 34);
                }
                break;
            }
            case "Mud": {
                int base = 130;
                fill(c, base);
                grain(c, 33 + seed, base, 46, 900);
                for (int i = 0; i < 130; i++) {
                    float x = random(seed + i * 3) * TILE_SIZE, y = random(seed + i * 7 + 1) * TILE_SIZE;
                    line(c, x, y, x + (random(i) - 0.5f) * 5, y - 3 - random(i * 2) * 5, 1.2f, base - 34);
                }
                break;
            }
            case "Snow": {
                fill(c, 250);
                grain(c, 12 + seed, 249, 10, 260);
                break;
            }
            case "Fabric": {
                fill(c, 206);
                break;
            }
            case "Carpet": {
                fill(c, 206);
                break;
            }
            case "Cardboard": {
                fill(c, 206);
                break;
            }
            case "Leather": {
                fill(c, 206);
                break;
            }
            case "Plaster": {
                fill(c, 224);
                break;
            }
            case "Rubber": {
                fill(c, 180);
                break;
            }
            case "CeramicTiles": {
                fill(c, 214);
                for (int row = 0; row < 4; row++)
                    for (int col = 0; col < 4; col++) {
                        float x = col * 32, y = row * 32;
                        rect(c, x + 1.5f, y + 1.5f, x + 30.5f, y + 30.5f, 224 + (int) (random(seed + row * 5 + col) * 22));
                    }
                grain(c, 88 + seed, 214, 16, 200);
                break;
            }
            case "ClayRoofTiles": {
                fill(c, 214);
                for (int row = 0; row < 4; row++)
                    for (int col = 0; col < 4; col++) {
                        float x = col * 32, y = row * 32;
                        rect(c, x + 1.5f, y + 1.5f, x + 30.5f, y + 30.5f, 224 + (int) (random(seed + row * 5 + col) * 22));
                    }
                grain(c, 88 + seed, 214, 16, 200);
                break;
            }
            case "RoofShingles": {
                fill(c, 214);
                for (int row = 0; row < 4; row++)
                    for (int col = 0; col < 4; col++) {
                        float x = col * 32, y = row * 32;
                        rect(c, x + 1.5f, y + 1.5f, x + 30.5f, y + 30.5f, 224 + (int) (random(seed + row * 5 + col) * 22));
                    }
                grain(c, 88 + seed, 214, 16, 200);
                break;
            }
            case "Metal": {
                int base = 214;
                fill(c, base);
                for (int i = 0; i < TILE_SIZE; i += 3) line(c, 0, i, TILE_SIZE, i, 0.8f, base - 12 - (int) (random(seed + i) * 10));
                grain(c, 41 + seed, base, 24, 260);
                break;
            }
            case "CorrodedMetal": {
                int base = 168;
                fill(c, base);
                for (int i = 0; i < TILE_SIZE; i += 3) line(c, 0, i, TILE_SIZE, i, 0.8f, base - 12 - (int) (random(seed + i) * 10));
                grain(c, 210 + seed, 150, 60, 420);
                break;
            }
            case "DiamondPlate": {
                fill(c, 190);
                for (int row = 0; row < 8; row++)
                    for (int col = 0; col < 8; col++)
                        line(c, col * 16 + 2, row * 16 + 12, col * 16 + 12, row * 16 + 3, 3.4f, 236);
                break;
            }
            case "Foil": {
                fill(c, 206);
                grain(c, 300 + seed, 204, 40, 700);
                break;
            }
            case "Ice": {
                int base = 238;
                fill(c, base);
                LinearGradient gradient = new LinearGradient(0, 0, TILE_SIZE, TILE_SIZE,
                        Color.rgb(base, base, base), Color.rgb(base - 34, base - 30, base - 24), Shader.TileMode.MIRROR);
                PAINT.setStyle(Paint.Style.FILL);
                PAINT.setShader(gradient);
                c.drawRect(0, 0, TILE_SIZE, TILE_SIZE, PAINT);
                PAINT.setShader(null);
                for (int i = 0; i < 5; i++) line(c, 0, random(seed + i) * TILE_SIZE, TILE_SIZE, random(seed + i * 3) * TILE_SIZE, 1.6f, base - 24);
                break;
            }
            case "Glacier": {
                int base = 238;
                fill(c, base);
                LinearGradient gradient = new LinearGradient(0, 0, TILE_SIZE, TILE_SIZE,
                        Color.rgb(base, base, base), Color.rgb(base - 34, base - 30, base - 24), Shader.TileMode.MIRROR);
                PAINT.setStyle(Paint.Style.FILL);
                PAINT.setShader(gradient);
                c.drawRect(0, 0, TILE_SIZE, TILE_SIZE, PAINT);
                PAINT.setShader(null);
                for (int i = 0; i < 5; i++) line(c, 0, random(seed + i) * TILE_SIZE, TILE_SIZE, random(seed + i * 3) * TILE_SIZE, 1.6f, base - 24);
                break;
            }
            case "Glass": {
                int base = 238;
                fill(c, base);
                LinearGradient gradient = new LinearGradient(0, 0, TILE_SIZE, TILE_SIZE,
                        Color.rgb(base, base, base), Color.rgb(base - 34, base - 30, base - 24), Shader.TileMode.MIRROR);
                PAINT.setStyle(Paint.Style.FILL);
                PAINT.setShader(gradient);
                c.drawRect(0, 0, TILE_SIZE, TILE_SIZE, PAINT);
                PAINT.setShader(null);
                for (int i = 0; i < 5; i++) line(c, 0, random(seed + i) * TILE_SIZE, TILE_SIZE, random(seed + i * 3) * TILE_SIZE, 1.6f, base - 24);
                break;
            }
            case "Water": {
                int base = 214;
                fill(c, base);
                LinearGradient gradient = new LinearGradient(0, 0, TILE_SIZE, TILE_SIZE,
                        Color.rgb(base, base, base), Color.rgb(base - 34, base - 30, base - 24), Shader.TileMode.MIRROR);
                PAINT.setStyle(Paint.Style.FILL);
                PAINT.setShader(gradient);
                c.drawRect(0, 0, TILE_SIZE, TILE_SIZE, PAINT);
                PAINT.setShader(null);
                for (int i = 0; i < 5; i++) line(c, 0, random(seed + i) * TILE_SIZE, TILE_SIZE, random(seed + i * 3) * TILE_SIZE, 1.6f, base - 24);
                break;
            }
            case "ForceField": {
                int base = 226;
                fill(c, base);
                LinearGradient gradient = new LinearGradient(0, 0, TILE_SIZE, TILE_SIZE,
                        Color.rgb(base, base, base), Color.rgb(base - 34, base - 30, base - 24), Shader.TileMode.MIRROR);
                PAINT.setStyle(Paint.Style.FILL);
                PAINT.setShader(gradient);
                c.drawRect(0, 0, TILE_SIZE, TILE_SIZE, PAINT);
                PAINT.setShader(null);
                for (int i = 0; i < 5; i++) line(c, 0, random(seed + i) * TILE_SIZE, TILE_SIZE, random(seed + i * 3) * TILE_SIZE, 1.6f, base - 24);
                break;
            }
            case "Studs": {
                fill(c, 236);
                for (int row = 0; row < 4; row++)
                    for (int col = 0; col < 4; col++) {
                        float x = col * 32 + 16, y = row * 32 + 16;
                        dot(c, x, y, 11, 252);
                        PAINT.setStyle(Paint.Style.STROKE);
                        PAINT.setStrokeWidth(2.6f);
                        PAINT.setColor(Color.rgb(196, 196, 196));
                        c.drawCircle(x, y, 11, PAINT);
                    }
                break;
            }
            case "Inlet": {
                fill(c, 226);
                for (int row = 0; row < 4; row++)
                    for (int col = 0; col < 4; col++) {
                        float x = col * 32 + 16, y = row * 32 + 16;
                        dot(c, x, y, 10, 172);
                        PAINT.setStyle(Paint.Style.STROKE);
                        PAINT.setStrokeWidth(2.4f);
                        PAINT.setColor(Color.rgb(206, 206, 206));
                        c.drawCircle(x, y, 10, PAINT);
                    }
                break;
            }
            case "Universal": {
                fill(c, 232);
                for (int row = 0; row < 4; row++)
                    for (int col = 0; col < 4; col++) {
                        float x = col * 32 + 16, y = row * 32 + 16;
                        dot(c, x, y, 10, 168);
                        dot(c, x, y, 6, 244);
                    }
                break;
            }
            case "Hinge": {
                fill(c, 220);
                for (int i = 0; i < 4; i++) {
                    float y = i * 32 + 16;
                    rect(c, 0, y - 8, TILE_SIZE, y + 8, 236);
                    line(c, 0, y - 8, TILE_SIZE, y - 8, 1.6f, 176);
                    line(c, 0, y + 8, TILE_SIZE, y + 8, 1.6f, 176);
                    dot(c, 64, y, 6, 150);
                }
                break;
            }
            case "Motor": {
                fill(c, 220);
                for (int i = 0; i < 4; i++) {
                    float y = i * 32 + 16;
                    rect(c, 0, y - 8, TILE_SIZE, y + 8, 236);
                    line(c, 0, y - 8, TILE_SIZE, y - 8, 1.6f, 176);
                    line(c, 0, y + 8, TILE_SIZE, y + 8, 1.6f, 176);
                    dot(c, 64, y, 6, 150);
                }
                for (int i = 0; i < 4; i++) line(c, 64, i * 32 + 16, 64, i * 32 + 16, 3f, 120);
                break;
            }
            case "SteppingMotor": {
                fill(c, 220);
                for (int i = 0; i < 4; i++) {
                    float y = i * 32 + 16;
                    rect(c, 0, y - 8, TILE_SIZE, y + 8, 236);
                    line(c, 0, y - 8, TILE_SIZE, y - 8, 1.6f, 176);
                    line(c, 0, y + 8, TILE_SIZE, y + 8, 1.6f, 176);
                    dot(c, 64, y, 6, 150);
                }
                for (int i = 0; i < 4; i++) line(c, 64, i * 32 + 16, 64, i * 32 + 16, 3f, 120);
                break;
            }
            case "Weld": {
                fill(c, 214);
                for (int i = 0; i < 4; i++) {
                    float o = i * 32;
                    line(c, 0, o + 32, 32, o, 3f, 160);
                    line(c, 32, o, 64, o + 32, 3f, 160);
                    line(c, 64, o + 32, 96, o, 3f, 160);
                    line(c, 96, o, 128, o + 32, 3f, 160);
                }
                break;
            }
            case "Glue": {
                fill(c, 214);
                for (int i = 0; i < 4; i++) {
                    float o = i * 32;
                    line(c, 0, o + 32, 32, o, 3f, 160);
                    line(c, 32, o, 64, o + 32, 3f, 160);
                    line(c, 64, o + 32, 96, o, 3f, 160);
                    line(c, 96, o, 128, o + 32, 3f, 160);
                }
                break;
            }
            case "SpawnPad": {
                fill(c, 240);
                PAINT.setStyle(Paint.Style.STROKE);
                PAINT.setStrokeWidth(6);
                PAINT.setColor(Color.rgb(170, 170, 170));
                c.drawCircle(TILE_SIZE / 2f, TILE_SIZE / 2f, 44, PAINT);
                PAINT.setStrokeWidth(10);
                PAINT.setColor(Color.rgb(120, 120, 120));
                c.drawCircle(TILE_SIZE / 2f, TILE_SIZE / 2f, 16, PAINT);
                PAINT.setStyle(Paint.Style.FILL);
                PAINT.setColor(Color.rgb(150, 150, 150));
                c.drawCircle(TILE_SIZE / 2f, TILE_SIZE / 2f, 5, PAINT);
                break;
            }
            default: {
                fill(c, 240);
                break;
            }
        }
    }
}
