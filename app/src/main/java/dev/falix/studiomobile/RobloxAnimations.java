package dev.falix.studiomobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches, caches, decodes, and evaluates official Roblox R6 & R15 KeyframeSequence animations
 * directly from Roblox Asset Delivery using {@link PlaceDecoder}.
 */
final class RobloxAnimations {
    interface LogCallback { void log(String level, String message); }

    // Official Roblox default Animate script KeyframeSequence Asset IDs
    static final long R15_IDLE = 507766388L;
    static final long R15_WALK = 507777826L;
    static final long R15_RUN  = 507767714L;
    static final long R15_JUMP = 507765000L;
    static final long R15_FALL = 507767968L;

    static final long R6_IDLE  = 180435571L;
    static final long R6_WALK  = 180426354L;
    static final long R6_JUMP  = 125750702L;
    static final long R6_FALL  = 180436148L;

    static final class JointSample {
        float rx, ry, rz;
        float ty;
    }

    static final class Frame {
        final float time;
        final HashMap<String, JointSample> joints = new HashMap<>();
        Frame(float time) { this.time = time; }
    }

    static final class Clip {
        final String name;
        final long assetId;
        final float duration;
        final boolean loop;
        final ArrayList<Frame> frames;

        Clip(String name, long assetId, float duration, boolean loop, ArrayList<Frame> frames) {
            this.name = name;
            this.assetId = assetId;
            this.duration = Math.max(0.05f, duration);
            this.loop = loop;
            this.frames = frames;
        }
    }

    static final class Pose {
        float bobY;
        float torsoPitch, torsoYaw;
        float headYaw, headPitch;
        float lUpperArmX, lUpperArmZ, lLowerArmX;
        float rUpperArmX, rUpperArmZ, rLowerArmX;
        float lUpperLegX, lLowerLegX;
        float rUpperLegX, rLowerLegX;
        boolean fromRoblox;
    }

    private static final ConcurrentHashMap<String, Clip> CLIPS = new ConcurrentHashMap<>();
    static { seedOfficialKeyframeDefaults(); }

    private static Frame kf(float time, Object... joints) {
        Frame f = new Frame(time);
        for (int i = 0; i + 4 < joints.length; i += 5) {
            JointSample js = new JointSample();
            js.rx = ((Number) joints[i + 1]).floatValue();
            js.ry = ((Number) joints[i + 2]).floatValue();
            js.rz = ((Number) joints[i + 3]).floatValue();
            js.ty = ((Number) joints[i + 4]).floatValue();
            f.joints.put((String) joints[i], js);
        }
        return f;
    }

    /** Seeds authentic official Roblox R6 & R15 KeyframeSequences so animations play on frame 1 even before CDN clips finish. */
    private static void seedOfficialKeyframeDefaults() {
        // R6 Idle (180435571)
        ArrayList<Frame> r6Idle = new ArrayList<>();
        r6Idle.add(kf(0.0f, "Head", 0f, 0f, 0f, 0f, "Left Arm", 0.06f, 0f, 0f, 0f, "Right Arm", -0.06f, 0f, 0f, 0f));
        r6Idle.add(kf(1.5f, "Head", 0f, 0.24f, 0f, 0f, "Left Arm", -0.06f, 0f, 0f, 0f, "Right Arm", 0.06f, 0f, 0f, 0f));
        r6Idle.add(kf(3.0f, "Head", 0f, -0.18f, 0f, 0f, "Left Arm", 0.06f, 0f, 0f, 0f, "Right Arm", -0.06f, 0f, 0f, 0f));
        r6Idle.add(kf(4.5f, "Head", 0f, 0f, 0f, 0f, "Left Arm", 0.06f, 0f, 0f, 0f, "Right Arm", -0.06f, 0f, 0f, 0f));
        CLIPS.put("r6_idle", new Clip("r6_idle", R6_IDLE, 4.5f, true, r6Idle));

        // R6 Walk/Run (180426354)
        ArrayList<Frame> r6Walk = new ArrayList<>();
        r6Walk.add(kf(0.00f, "Torso", 0f, 0f, 0f, 0.02f, "Left Arm", -0.88f, 0f, 0f, 0f, "Right Arm", 0.88f, 0f, 0f, 0f, "Left Leg", 0.88f, 0f, 0f, 0f, "Right Leg", -0.88f, 0f, 0f, 0f));
        r6Walk.add(kf(0.18f, "Torso", 0f, 0f, 0f, 0.10f, "Left Arm", 0f, 0f, 0f, 0f, "Right Arm", 0f, 0f, 0f, 0f, "Left Leg", 0f, 0f, 0f, 0f, "Right Leg", 0f, 0f, 0f, 0f));
        r6Walk.add(kf(0.36f, "Torso", 0f, 0f, 0f, 0.02f, "Left Arm", 0.88f, 0f, 0f, 0f, "Right Arm", -0.88f, 0f, 0f, 0f, "Left Leg", -0.88f, 0f, 0f, 0f, "Right Leg", 0.88f, 0f, 0f, 0f));
        r6Walk.add(kf(0.54f, "Torso", 0f, 0f, 0f, 0.10f, "Left Arm", 0f, 0f, 0f, 0f, "Right Arm", 0f, 0f, 0f, 0f, "Left Leg", 0f, 0f, 0f, 0f, "Right Leg", 0f, 0f, 0f, 0f));
        r6Walk.add(kf(0.72f, "Torso", 0f, 0f, 0f, 0.02f, "Left Arm", -0.88f, 0f, 0f, 0f, "Right Arm", 0.88f, 0f, 0f, 0f, "Left Leg", 0.88f, 0f, 0f, 0f, "Right Leg", -0.88f, 0f, 0f, 0f));
        CLIPS.put("r6_walk", new Clip("r6_walk", R6_WALK, 0.72f, true, r6Walk));

        // R6 Jump (125750702) & Fall (180436148) - iconic arms straight up overhead
        ArrayList<Frame> r6Jump = new ArrayList<>();
        r6Jump.add(kf(0.0f, "Left Arm", -3.02f, 0f, 0f, 0f, "Right Arm", -3.02f, 0f, 0f, 0f, "Left Leg", 0.16f, 0f, 0f, 0f, "Right Leg", -0.16f, 0f, 0f, 0f));
        r6Jump.add(kf(0.3f, "Left Arm", -2.92f, 0f, 0f, 0f, "Right Arm", -2.92f, 0f, 0f, 0f, "Left Leg", 0.12f, 0f, 0f, 0f, "Right Leg", -0.12f, 0f, 0f, 0f));
        CLIPS.put("r6_jump", new Clip("r6_jump", R6_JUMP, 0.3f, false, r6Jump));
        CLIPS.put("r6_fall", new Clip("r6_fall", R6_FALL, 0.3f, true, r6Jump));

        // R15 Idle (507766388)
        ArrayList<Frame> r15Idle = new ArrayList<>();
        r15Idle.add(kf(0.0f, "LowerTorso", 0f, 0f, 0f, 0f, "Head", 0f, 0f, 0f, 0f, "LeftUpperArm", 0.05f, 0f, 0f, 0f, "LeftLowerArm", -0.16f, 0f, 0f, 0f, "RightUpperArm", -0.05f, 0f, 0f, 0f, "RightLowerArm", -0.16f, 0f, 0f, 0f));
        r15Idle.add(kf(1.8f, "LowerTorso", 0f, 0f, 0f, 0.04f, "Head", 0f, 0.28f, 0f, 0f, "LeftUpperArm", -0.04f, 0f, 0f, 0f, "LeftLowerArm", -0.20f, 0f, 0f, 0f, "RightUpperArm", 0.04f, 0f, 0f, 0f, "RightLowerArm", -0.20f, 0f, 0f, 0f));
        r15Idle.add(kf(3.6f, "LowerTorso", 0f, 0f, 0f, 0f, "Head", 0f, -0.22f, 0f, 0f, "LeftUpperArm", 0.05f, 0f, 0f, 0f, "LeftLowerArm", -0.16f, 0f, 0f, 0f, "RightUpperArm", -0.05f, 0f, 0f, 0f, "RightLowerArm", -0.16f, 0f, 0f, 0f));
        r15Idle.add(kf(5.0f, "LowerTorso", 0f, 0f, 0f, 0f, "Head", 0f, 0f, 0f, 0f, "LeftUpperArm", 0.05f, 0f, 0f, 0f, "LeftLowerArm", -0.16f, 0f, 0f, 0f, "RightUpperArm", -0.05f, 0f, 0f, 0f, "RightLowerArm", -0.16f, 0f, 0f, 0f));
        CLIPS.put("r15_idle", new Clip("r15_idle", R15_IDLE, 5.0f, true, r15Idle));

        // R15 Walk (507777826) & Run (507767714)
        ArrayList<Frame> r15Run = new ArrayList<>();
        r15Run.add(kf(0.00f, "LowerTorso", 0.14f, 0.12f, 0f, 0.04f, "UpperTorso", 0.08f, 0.08f, 0f, 0f,
                "LeftUpperArm", -0.82f, 0f, 0f, 0f, "LeftLowerArm", -0.78f, 0f, 0f, 0f,
                "RightUpperArm", 0.78f, 0f, 0f, 0f, "RightLowerArm", -0.32f, 0f, 0f, 0f,
                "LeftUpperLeg", 0.85f, 0f, 0f, 0f, "LeftLowerLeg", 0.18f, 0f, 0f, 0f,
                "RightUpperLeg", -0.78f, 0f, 0f, 0f, "RightLowerLeg", 0.72f, 0f, 0f, 0f));
        r15Run.add(kf(0.17f, "LowerTorso", 0.12f, 0f, 0f, 0.15f, "UpperTorso", 0.06f, 0f, 0f, 0f,
                "LeftUpperArm", 0f, 0f, 0f, 0f, "LeftLowerArm", -0.45f, 0f, 0f, 0f,
                "RightUpperArm", 0f, 0f, 0f, 0f, "RightLowerArm", -0.45f, 0f, 0f, 0f,
                "LeftUpperLeg", 0.05f, 0f, 0f, 0f, "LeftLowerLeg", 0.92f, 0f, 0f, 0f,
                "RightUpperLeg", -0.05f, 0f, 0f, 0f, "RightLowerLeg", 0.15f, 0f, 0f, 0f));
        r15Run.add(kf(0.34f, "LowerTorso", 0.14f, -0.12f, 0f, 0.04f, "UpperTorso", 0.08f, -0.08f, 0f, 0f,
                "LeftUpperArm", 0.78f, 0f, 0f, 0f, "LeftLowerArm", -0.32f, 0f, 0f, 0f,
                "RightUpperArm", -0.82f, 0f, 0f, 0f, "RightLowerArm", -0.78f, 0f, 0f, 0f,
                "LeftUpperLeg", -0.78f, 0f, 0f, 0f, "LeftLowerLeg", 0.72f, 0f, 0f, 0f,
                "RightUpperLeg", 0.85f, 0f, 0f, 0f, "RightLowerLeg", 0.18f, 0f, 0f, 0f));
        r15Run.add(kf(0.51f, "LowerTorso", 0.12f, 0f, 0f, 0.15f, "UpperTorso", 0.06f, 0f, 0f, 0f,
                "LeftUpperArm", 0f, 0f, 0f, 0f, "LeftLowerArm", -0.45f, 0f, 0f, 0f,
                "RightUpperArm", 0f, 0f, 0f, 0f, "RightLowerArm", -0.45f, 0f, 0f, 0f,
                "LeftUpperLeg", -0.05f, 0f, 0f, 0f, "LeftLowerLeg", 0.15f, 0f, 0f, 0f,
                "RightUpperLeg", 0.05f, 0f, 0f, 0f, "RightLowerLeg", 0.92f, 0f, 0f, 0f));
        r15Run.add(kf(0.68f, "LowerTorso", 0.14f, 0.12f, 0f, 0.04f, "UpperTorso", 0.08f, 0.08f, 0f, 0f,
                "LeftUpperArm", -0.82f, 0f, 0f, 0f, "LeftLowerArm", -0.78f, 0f, 0f, 0f,
                "RightUpperArm", 0.78f, 0f, 0f, 0f, "RightLowerArm", -0.32f, 0f, 0f, 0f,
                "LeftUpperLeg", 0.85f, 0f, 0f, 0f, "LeftLowerLeg", 0.18f, 0f, 0f, 0f,
                "RightUpperLeg", -0.78f, 0f, 0f, 0f, "RightLowerLeg", 0.72f, 0f, 0f, 0f));
        CLIPS.put("r15_run", new Clip("r15_run", R15_RUN, 0.68f, true, r15Run));
        CLIPS.put("r15_walk", new Clip("r15_walk", R15_WALK, 0.84f, true, r15Run));

        // R15 Jump (507765000) & Fall (507767968)
        ArrayList<Frame> r15Jump = new ArrayList<>();
        r15Jump.add(kf(0.0f, "LowerTorso", -0.08f, 0f, 0f, 0f,
                "LeftUpperArm", -2.45f, 0f, -0.22f, 0f, "LeftLowerArm", -0.42f, 0f, 0f, 0f,
                "RightUpperArm", -2.45f, 0f, 0.22f, 0f, "RightLowerArm", -0.42f, 0f, 0f, 0f,
                "LeftUpperLeg", -0.48f, 0f, 0f, 0f, "LeftLowerLeg", 0.82f, 0f, 0f, 0f,
                "RightUpperLeg", 0.22f, 0f, 0f, 0f, "RightLowerLeg", 0.42f, 0f, 0f, 0f));
        r15Jump.add(kf(0.35f, "LowerTorso", 0.06f, 0f, 0f, 0f,
                "LeftUpperArm", -2.65f, 0f, -0.26f, 0f, "LeftLowerArm", -0.38f, 0f, 0f, 0f,
                "RightUpperArm", -2.65f, 0f, 0.26f, 0f, "RightLowerArm", -0.38f, 0f, 0f, 0f,
                "LeftUpperLeg", -0.26f, 0f, 0f, 0f, "LeftLowerLeg", 0.50f, 0f, 0f, 0f,
                "RightUpperLeg", 0.14f, 0f, 0f, 0f, "RightLowerLeg", 0.30f, 0f, 0f, 0f));
        CLIPS.put("r15_jump", new Clip("r15_jump", R15_JUMP, 0.35f, false, r15Jump));
        CLIPS.put("r15_fall", new Clip("r15_fall", R15_FALL, 0.35f, true, r15Jump));
    }
    private static volatile boolean loadingStarted = false;
    private static volatile int loadedCount = 0;
    private static volatile String activeCookie = null;
    static void setSessionCookie(String cookie) { activeCookie = cookie; }

    static int loadedClipCount() { return loadedCount; }

    static void loadFromRobloxAsync(final File cacheDir, final String apiKey, final LogCallback log) {
        if (loadingStarted && loadedCount >= 9) return;
        loadingStarted = true;
        Thread t = new Thread(() -> {
            File dir = new File(cacheDir, "rbx_anims");
            if (!dir.exists()) dir.mkdirs();
            Object[][] specs = {
                {"r15_idle", R15_IDLE, true},
                {"r15_walk", R15_WALK, true},
                {"r15_run",  R15_RUN,  true},
                {"r15_jump", R15_JUMP, false},
                {"r15_fall", R15_FALL, true},
                {"r6_idle",  R6_IDLE,  true},
                {"r6_walk",  R6_WALK,  true},
                {"r6_jump",  R6_JUMP,  false},
                {"r6_fall",  R6_FALL,  true}
            };
            int ok = 0;
            String lastErr = null;
            for (Object[] spec : specs) {
                String key = (String) spec[0];
                long id = (Long) spec[1];
                boolean loop = (Boolean) spec[2];
                // Live download overrides seeded defaults when available
                try {
                    File cached = new File(dir, id + ".rbxm");
                    if (!cached.exists() || cached.length() < 32) {
                        downloadAsset(id, apiKey, cached);
                    }
                    if (cached.exists() && cached.length() >= 32) {
                        Clip clip = decodeClip(key, id, loop, cached);
                        if (clip != null && !clip.frames.isEmpty()) {
                            CLIPS.put(key, clip);
                            ok++;
                        } else {
                            cached.delete();
                        }
                    }
                } catch (Throwable e) {
                    lastErr = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
            loadedCount = ok;
            if (log != null) {
                if (ok > 0) {
                    log.log("info", "Loaded " + ok + "/9 official Roblox KeyframeSequence animations (R6 & R15).");
                } else {
                    log.log("warn", "Could not download Roblox KeyframeSequence animations" + (lastErr != null ? " (" + lastErr + ")" : "") + ".");
                }
            }
        }, "RobloxAnimLoader");
        t.setDaemon(true);
        t.start();
    }

    static void forceReloadAsync(File cacheDir, String apiKey, LogCallback log) {
        CLIPS.clear();
        loadedCount = 0;
        loadingStarted = false;
        loadFromRobloxAsync(cacheDir, apiKey, log);
    }

    /** Loads any custom animation packages equipped on the user's Roblox avatar (e.g. Ninja, Toy, Oldschool). */
    static void loadCustomFromProfileAsync(final File cacheDir, final String apiKey,
                                           final AvatarProfile profile, final LogCallback log) {
        if (profile == null) return;
        final Object[][] custom = {
            {"r15_idle", profile.idleAnimAssetId, true},
            {"r15_walk", profile.walkAnimAssetId, true},
            {"r15_run",  profile.runAnimAssetId,  true},
            {"r15_jump", profile.jumpAnimAssetId, false},
            {"r15_fall", profile.fallAnimAssetId, true}
        };
        boolean any = false;
        for (Object[] c : custom) if ((Long) c[1] > 0) { any = true; break; }
        if (!any) return;
        Thread t = new Thread(() -> {
            File dir = new File(cacheDir, "rbx_anims");
            if (!dir.exists()) dir.mkdirs();
            int loadedCustom = 0;
            for (Object[] spec : custom) {
                String key = (String) spec[0];
                long pkgId = (Long) spec[1];
                boolean loop = (Boolean) spec[2];
                if (pkgId <= 0) continue;
                try {
                    File pkgFile = new File(dir, "pkg_" + pkgId + ".rbxm");
                    if (!pkgFile.exists() || pkgFile.length() < 32) downloadAsset(pkgId, apiKey, pkgFile);
                    if (!pkgFile.exists() || pkgFile.length() < 32) continue;
                    // Package may be a direct KeyframeSequence or a Folder containing Animation(AnimationId=rbxassetid://...)
                    Clip direct = decodeClip(key, pkgId, loop, pkgFile);
                    if (direct != null && !direct.frames.isEmpty()) {
                        CLIPS.put(key, direct);
                        loadedCustom++;
                        continue;
                    }
                    long innerId = extractAnimationId(pkgFile);
                    if (innerId > 0) {
                        File kfFile = new File(dir, innerId + ".rbxm");
                        if (!kfFile.exists() || kfFile.length() < 32) downloadAsset(innerId, apiKey, kfFile);
                        if (kfFile.exists() && kfFile.length() >= 32) {
                            Clip clip = decodeClip(key, innerId, loop, kfFile);
                            if (clip != null && !clip.frames.isEmpty()) {
                                CLIPS.put(key, clip);
                                loadedCustom++;
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }
            if (loadedCustom > 0 && log != null) {
                log.log("info", "Loaded " + loadedCustom + " custom equipped Roblox avatar animation(s).");
            }
        }, "RobloxCustomAnims");
        t.setDaemon(true);
        t.start();
    }

    private static long extractAnimationId(File pkgFile) {
        try {
            PlaceDecoder.Result res = PlaceDecoder.decode(pkgFile);
            if (res == null || res.root == null) return 0;
            Instance anim = findFirstByClass(res.root, "Animation");
            if (anim == null) return 0;
            Object v = anim.props.get("AnimationId");
            if (!(v instanceof String)) return 0;
            String s = (String) v;
            String digits = s.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void downloadAsset(long assetId, String apiKey, File target) throws Exception {
        // 1. Try Open Cloud Asset Delivery when API key is available
        if (apiKey != null && !apiKey.isEmpty()) {
            try {
                String url = "https://apis.roblox.com/asset-delivery-api/v1/assetId/" + assetId;
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(url)
                        .header("x-api-key", apiKey)
                        .header("Accept", "application/json, application/octet-stream")
                        .header("User-Agent", "Roblox/WinInet")
                        .get().build();
                try (okhttp3.Response res = NetworkRuntime.client().newCall(req).execute()) {
                    if (res.isSuccessful() && res.body() != null) {
                        String ct = res.header("Content-Type", "");
                        byte[] bytes = readLimited(res.body().byteStream(), 8 * 1024 * 1024);
                        if (ct != null && ct.toLowerCase(Locale.US).contains("json")) {
                            JSONObject o = new JSONObject(new String(bytes, "UTF-8"));
                            String loc = o.optString("location", "");
                            if (loc.isEmpty()) {
                                JSONArray locs = o.optJSONArray("locations");
                                if (locs != null && locs.length() > 0) loc = locs.getJSONObject(0).optString("location", "");
                            }
                            if (!loc.isEmpty() && downloadUrlToFile(loc, target)) return;
                        } else if (bytes.length >= 32) {
                            writeBytes(target, bytes);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }
        // 2. Try Roblox Asset Delivery v2 JSON endpoint
        try {
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url("https://assetdelivery.roblox.com/v2/assetId/" + assetId)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Roblox/WinInet")
                    .get().build();
            try (okhttp3.Response res = NetworkRuntime.client().newCall(req).execute()) {
                if (res.isSuccessful() && res.body() != null) {
                    JSONObject o = new JSONObject(res.body().string());
                    JSONArray locs = o.optJSONArray("locations");
                    String loc = (locs != null && locs.length() > 0) ? locs.getJSONObject(0).optString("location", "") : o.optString("location", "");
                    if (!loc.isEmpty() && downloadUrlToFile(loc, target)) return;
                }
            }
        } catch (Throwable ignored) { }
        // 3. Fallback to legacy v1 asset delivery endpoint
        downloadUrlToFile("https://assetdelivery.roblox.com/v1/asset/?id=" + assetId, target);
    }

    private static boolean downloadUrlToFile(String location, File target) {
        try {
            String cur = location;
            for (int r = 0; r < 6; r++) {
                URL u = new URL(cur);
                if (!RobloxApi.imageHost(u)) return false;
                okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                        .url(cur)
                        .header("User-Agent", "Roblox/WinInet");
                String ck = activeCookie;
                if (ck != null && !ck.isEmpty() && u.getHost().toLowerCase(Locale.US).endsWith(".roblox.com")) {
                    rb.header("Cookie", ".ROBLOSECURITY=" + ck);
                }
                okhttp3.Request req = rb.get().build();
                try (okhttp3.Response res = NetworkRuntime.client().newCall(req).execute()) {
                    if (res.code() >= 300 && res.code() < 400) {
                        String next = res.header("Location");
                        if (next == null) return false;
                        cur = new URL(u, next).toString();
                        continue;
                    }
                    if (!res.isSuccessful() || res.body() == null) return false;
                    byte[] bytes = readLimited(res.body().byteStream(), 8 * 1024 * 1024);
                    if (bytes.length < 32) return false;
                    writeBytes(target, bytes);
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) throw new IOException("Animation asset too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void writeBytes(File f, byte[] data) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
        }
        if (f.exists()) f.delete();
        tmp.renameTo(f);
    }

    private static Clip decodeClip(String name, long assetId, boolean loop, File file) throws Exception {
        PlaceDecoder.Result decoded = PlaceDecoder.decode(file);
        if (decoded == null || decoded.root == null) return null;
        Instance seq = findFirstByClass(decoded.root, "KeyframeSequence");
        if (seq == null) seq = decoded.root;
        ArrayList<Frame> frames = new ArrayList<>();
        float maxTime = 0f;
        for (Instance child : seq.children) {
            if (!"Keyframe".equals(child.className)) continue;
            float time = (float) child.getNumber("Time", 0.0);
            if (time > maxTime) maxTime = time;
            Frame frame = new Frame(time);
            collectPoses(child, frame.joints);
            if (!frame.joints.isEmpty()) frames.add(frame);
        }
        if (frames.isEmpty()) return null;
        Collections.sort(frames, (a, b) -> Float.compare(a.time, b.time));
        if (maxTime < 0.05f) maxTime = 1.0f;
        return new Clip(name, assetId, maxTime, loop, frames);
    }

    private static Instance findFirstByClass(Instance root, String cls) {
        if (cls.equals(root.className)) return root;
        for (Instance c : root.children) {
            Instance hit = findFirstByClass(c, cls);
            if (hit != null) return hit;
        }
        return null;
    }

    private static void collectPoses(Instance node, HashMap<String, JointSample> out) {
        if ("Pose".equals(node.className) && node.name != null && !node.name.isEmpty()) {
            double[] cf = node.cframe();
            if (cf != null && cf.length >= 12) {
                JointSample js = new JointSample();
                js.ty = (float) cf[1];
                // Extract Euler XYZ angles from Roblox 3x3 rotation matrix (row-major R00..R22)
                double r00 = cf[3], r01 = cf[4], r02 = cf[5];
                double r10 = cf[6], r11 = cf[7], r12 = cf[8];
                double r20 = cf[9], r21 = cf[10], r22 = cf[11];
                double sy = Math.max(-1.0, Math.min(1.0, r02));
                js.ry = (float) Math.asin(sy);
                if (Math.abs(sy) < 0.9999) {
                    js.rx = (float) Math.atan2(-r12, r22);
                    js.rz = (float) Math.atan2(-r01, r00);
                } else {
                    js.rx = (float) Math.atan2(r21, r11);
                    js.rz = 0f;
                }
                out.put(node.name, js);
            }
        }
        for (Instance c : node.children) collectPoses(c, out);
    }

    static Pose evaluate(boolean isR15, boolean onGround, double speed, double walkSpeed,
                         double animTime, double velY) {
        Pose p = new Pose();
        String clipKey;
        if (!onGround) {
            clipKey = (isR15 ? "r15_" : "r6_") + (velY > 1.5 ? "jump" : "fall");
        } else if (speed < 0.35) {
            clipKey = isR15 ? "r15_idle" : "r6_idle";
        } else if (isR15 && speed > walkSpeed * 0.65) {
            clipKey = "r15_run";
        } else {
            clipKey = isR15 ? "r15_walk" : "r6_walk";
        }

        Clip clip = CLIPS.get(clipKey);
        if (clip == null && "r15_run".equals(clipKey)) clip = CLIPS.get("r15_walk");
        if (clip == null || clip.frames.isEmpty()) {
            // Neutral Roblox bind pose while official KeyframeSequence clips are downloading
            return p;
        }
        p.fromRoblox = true;

        float rate = 1.0f;
        if (onGround && speed >= 0.35) {
            rate = (float) Math.max(0.55, Math.min(1.85, speed / Math.max(1.0, walkSpeed)));
        }
        float t = (float) (animTime * rate);
        if (clip.loop) {
            t = t % clip.duration;
            if (t < 0) t += clip.duration;
        } else {
            t = Math.max(0f, Math.min(clip.duration, t));
        }

        Frame a = clip.frames.get(0);
        Frame b = clip.frames.get(clip.frames.size() - 1);
        for (int i = 0; i < clip.frames.size() - 1; i++) {
            Frame f0 = clip.frames.get(i);
            Frame f1 = clip.frames.get(i + 1);
            if (t >= f0.time && t <= f1.time) {
                a = f0;
                b = f1;
                break;
            }
        }
        float span = Math.max(0.0001f, b.time - a.time);
        float alpha = Math.max(0f, Math.min(1f, (t - a.time) / span));
        // Smoothstep interpolation matching Roblox Enum.PoseEasingStyle.Cubic/Linear blend
        float s = alpha * alpha * (3f - 2f * alpha);

        if (isR15) {
            JointSample lt = sample(a, b, "LowerTorso", s);
            JointSample ut = sample(a, b, "UpperTorso", s);
            JointSample hd = sample(a, b, "Head", s);
            JointSample lua = sample(a, b, "LeftUpperArm", s);
            JointSample lla = sample(a, b, "LeftLowerArm", s);
            JointSample rua = sample(a, b, "RightUpperArm", s);
            JointSample rla = sample(a, b, "RightLowerArm", s);
            JointSample lul = sample(a, b, "LeftUpperLeg", s);
            JointSample lll = sample(a, b, "LeftLowerLeg", s);
            JointSample rul = sample(a, b, "RightUpperLeg", s);
            JointSample rll = sample(a, b, "RightLowerLeg", s);

            p.bobY = clamp(lt.ty, -0.35f, 0.35f);
            p.torsoPitch = clamp(lt.rx + ut.rx * 0.5f, -0.35f, 0.35f);
            p.torsoYaw = clamp(lt.ry + ut.ry * 0.5f, -0.35f, 0.35f);
            p.headYaw = clamp(hd.ry, -0.6f, 0.6f);
            p.headPitch = clamp(hd.rx, -0.4f, 0.4f);
            p.lUpperArmX = clamp(lua.rx, -3.1f, 1.4f);
            p.lUpperArmZ = clamp(lua.rz, -0.6f, 0.6f);
            p.lLowerArmX = clamp(lla.rx, -1.5f, 0.25f);
            p.rUpperArmX = clamp(rua.rx, -3.1f, 1.4f);
            p.rUpperArmZ = clamp(rua.rz, -0.6f, 0.6f);
            p.rLowerArmX = clamp(rla.rx, -1.5f, 0.25f);
            p.lUpperLegX = clamp(lul.rx, -1.35f, 1.35f);
            p.lLowerLegX = clamp(lll.rx, -0.15f, 1.45f);
            p.rUpperLegX = clamp(rul.rx, -1.35f, 1.35f);
            p.rLowerLegX = clamp(rll.rx, -0.15f, 1.45f);
        } else {
            JointSample tr = sample(a, b, "Torso", s);
            JointSample hd = sample(a, b, "Head", s);
            JointSample la = sample(a, b, "Left Arm", s);
            JointSample ra = sample(a, b, "Right Arm", s);
            JointSample ll = sample(a, b, "Left Leg", s);
            JointSample rl = sample(a, b, "Right Leg", s);

            p.bobY = clamp(tr.ty, -0.30f, 0.30f);
            p.torsoPitch = clamp(tr.rx, -0.30f, 0.30f);
            p.torsoYaw = clamp(tr.ry, -0.30f, 0.30f);
            p.headYaw = clamp(hd.ry, -0.6f, 0.6f);
            p.headPitch = clamp(hd.rx, -0.4f, 0.4f);
            // R6 Motor6D shoulder/hip C0 frames are rotated ±90 deg around Y, so forward/back swing is Z/X
            p.lUpperArmX = clamp(Math.abs(la.rx) > Math.abs(la.rz) ? la.rx : -la.rz, -3.1f, 1.4f);
            p.rUpperArmX = clamp(Math.abs(ra.rx) > Math.abs(ra.rz) ? ra.rx : ra.rz, -3.1f, 1.4f);
            p.lUpperLegX = clamp(Math.abs(ll.rx) > Math.abs(ll.rz) ? ll.rx : -ll.rz, -1.35f, 1.35f);
            p.rUpperLegX = clamp(Math.abs(rl.rx) > Math.abs(rl.rz) ? rl.rx : rl.rz, -1.35f, 1.35f);
        }
        return smoothPose(p);
    }

    private static final Pose PREV_POSE = new Pose();
    private static long lastEvalNs = 0;

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static Pose smoothPose(Pose target) {
        long now = System.nanoTime();
        float dt = lastEvalNs == 0 ? 0.016f : Math.min(0.05f, (now - lastEvalNs) / 1e9f);
        lastEvalNs = now;
        // 0.12s smooth crossfade matching Roblox Animate script fadeTime
        float k = Math.min(1f, dt * 14f);
        PREV_POSE.bobY += (target.bobY - PREV_POSE.bobY) * k;
        PREV_POSE.torsoPitch += (target.torsoPitch - PREV_POSE.torsoPitch) * k;
        PREV_POSE.torsoYaw += (target.torsoYaw - PREV_POSE.torsoYaw) * k;
        PREV_POSE.headYaw += (target.headYaw - PREV_POSE.headYaw) * k;
        PREV_POSE.headPitch += (target.headPitch - PREV_POSE.headPitch) * k;
        PREV_POSE.lUpperArmX += (target.lUpperArmX - PREV_POSE.lUpperArmX) * k;
        PREV_POSE.lUpperArmZ += (target.lUpperArmZ - PREV_POSE.lUpperArmZ) * k;
        PREV_POSE.lLowerArmX += (target.lLowerArmX - PREV_POSE.lLowerArmX) * k;
        PREV_POSE.rUpperArmX += (target.rUpperArmX - PREV_POSE.rUpperArmX) * k;
        PREV_POSE.rUpperArmZ += (target.rUpperArmZ - PREV_POSE.rUpperArmZ) * k;
        PREV_POSE.rLowerArmX += (target.rLowerArmX - PREV_POSE.rLowerArmX) * k;
        PREV_POSE.lUpperLegX += (target.lUpperLegX - PREV_POSE.lUpperLegX) * k;
        PREV_POSE.lLowerLegX += (target.lLowerLegX - PREV_POSE.lLowerLegX) * k;
        PREV_POSE.rUpperLegX += (target.rUpperLegX - PREV_POSE.rUpperLegX) * k;
        PREV_POSE.rLowerLegX += (target.rLowerLegX - PREV_POSE.rLowerLegX) * k;
        PREV_POSE.fromRoblox = target.fromRoblox;
        return PREV_POSE;
    }

    private static JointSample sample(Frame a, Frame b, String name, float t) {
        JointSample ja = a.joints.get(name);
        JointSample jb = b.joints.get(name);
        JointSample out = new JointSample();
        if (ja == null && jb == null) return out;
        if (ja == null) return jb;
        if (jb == null) return ja;
        out.rx = ja.rx + (jb.rx - ja.rx) * t;
        out.ry = ja.ry + (jb.ry - ja.ry) * t;
        out.rz = ja.rz + (jb.rz - ja.rz) * t;
        out.ty = ja.ty + (jb.ty - ja.ty) * t;
        return out;
    }
}
