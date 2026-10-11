package dev.falix.studiomobile;

import java.util.*;

/**
 * The Roblox `Lighting` service, decoded into everything the viewport needs.
 *
 * Property names, types and defaults follow the published Roblox reference for
 * Lighting, Sky, Atmosphere, Clouds, the Light classes and the post-processing effects.
 * Every value is read from the place file, so a place that sets Ambient, ClockTime,
 * FogEnd, an Atmosphere, a Sky or a ColorCorrectionEffect renders with those settings.
 *
 * Deliberate, documented approximations of a mobile GLES2 renderer without an
 * environment probe, shadow maps or a texture pipeline:
 *  - `OutdoorAmbient` follows the sun's height (Roblox mixes it with the environment when
 *    EnvironmentDiffuseScale > 0); this build has no environment probe, so a rising/setting
 *    sun also dims outdoor ambient. Nights therefore read dark instead of daytime-grey.
 *  - `EnvironmentDiffuseScale` blends the sky gradient into the ambient term, and
 *    `EnvironmentSpecularScale` scales the specular highlight.
 *  - `GlobalShadows`/`ShadowSoftness` are read and reported, but no shadow map is rendered:
 *    faces turned away from the sun are simply darker. This is stated in the editor's limits.
 *  - `Sky` skybox textures are Roblox asset ids, and this build has no asset pipeline, so the
 *    sky is a procedural gradient with sun, stars, atmosphere haze/glare and drifting clouds.
 *  - `Technology`/`LightingStyle` are read for reporting; the model below is one fixed pipeline.
 */
final class EngineLighting {
    // --- Lighting service defaults (Roblox reference values) ---
    double[] ambient = {0, 0, 0};                       // Lighting.Ambient
    double[] outdoorAmbient = {127 / 255.0, 127 / 255.0, 127 / 255.0};   // Lighting.OutdoorAmbient
    double[] colorShiftTop = {0, 0, 0};                 // Lighting.ColorShift_Top
    double[] colorShiftBottom = {0, 0, 0};              // Lighting.ColorShift_Bottom
    double[] fogColor = {192 / 255.0, 192 / 255.0, 192 / 255.0};         // Lighting.FogColor
    double brightness = 2;                              // Lighting.Brightness
    double clockTime = 14;                              // Lighting.ClockTime (0..24 hours)
    double geographicLatitude = 41.7333;                // Lighting.GeographicLatitude
    double environmentDiffuseScale = 0;                 // Lighting.EnvironmentDiffuseScale
    double environmentSpecularScale = 1;                // Lighting.EnvironmentSpecularScale
    double exposureCompensation = 0;                    // Lighting.ExposureCompensation
    double fogStart = 0, fogEnd = 100000;               // Lighting.FogStart / FogEnd
    boolean globalShadows = true;                       // Lighting.GlobalShadows
    double shadowSoftness = 0.2;                        // Lighting.ShadowSoftness
    String technology = "Voxel", lightingStyle = "Soft";

    // --- Sky ---
    boolean sky;
    boolean celestialBodiesShown = true;
    double sunAngularSize = 21, moonAngularSize = 11, starCount = 3000;
    /**
     * `Sky.SkyboxRt/Lf/Up/Dn/Ft/Bk` as content strings, already in GL cube-map face order so the
     * renderer can upload them straight into a cubemap: +X Right, -X Left, +Y Up, -Y Down,
     * +Z Front(Ft), -Z Back(Bk).
     */
    String[] skyTextures = new String[6];
    boolean skyboxTextures;                             // a Sky with real SkyboxBk/Up/... assets

    // --- Atmosphere ---
    boolean atmosphere;
    double atmosphereDensity = 0.3, atmosphereOffset = 0, atmosphereDecay = 0.4, atmosphereGlare = 0, atmosphereHaze = 0;
    double[] atmosphereColor = {199 / 255.0, 199 / 255.0, 199 / 255.0};

    // --- Clouds (only renders as a child of Terrain in Roblox) ---
    boolean clouds;
    double cloudCover = 0, cloudDensity = 0.7;
    double[] cloudColor = {1, 1, 1};

    // --- Post-processing effects (children of Lighting or the Camera) ---
    boolean colorCorrection, ccEnabled = true;
    double ccBrightness = 0, ccContrast = 0, ccSaturation = 0;
    double[] ccTint = {1, 1, 1};
    boolean bloom, blur, sunRays, depthOfField, colorGrading;
    double bloomIntensity = 1, bloomSize = 24, bloomThreshold = 2;
    double blurSize = 24;
    double sunRaysIntensity = 0.25, sunRaysSpread = 1;
    double dofFarIntensity = 0.75, dofFocusDistance = 0.1, dofInFocusRadius = 1, dofNearIntensity = 0;
    String colorGradingTonemapper = "Default";

    // --- local light objects (max 4 are shaded per frame) ---
    static final int POINT = 0, SPOT = 1, SURFACE = 2;
    static final int MAX_SHADED_LIGHTS = 4;
    static final class Light {
        int kind; double[] position; double[] direction; double range;
        double[] color; double brightness; double cosAngle;
    }
    final ArrayList<Light> lights = new ArrayList<>();
    int lightCount;                                     // unmodeled lights are reported, not silently dropped

    // --- derived every refresh ---
    double[] sunDirection = {0, 1, 0};                  // unit vector pointing from the world toward the sun
    double sunElevation;                                // degrees above the horizon
    double dayFactor, duskFactor, sunUp;                // 0..1 blends used by the shaders
    double[] sunColor = {1, 0.98, 0.94};
    double sunGain;                                     // Brightness-scaled directional intensity
    double[] skyTop = {0.28, 0.55, 0.92}, skyHorizon = {0.71, 0.85, 0.98}, skyGround = {0.30, 0.33, 0.36};
    double[] ambientDiffuse = {0.4, 0.4, 0.4};
    double[] fogTint = {192 / 255.0, 192 / 255.0, 192 / 255.0};
    boolean fogEnabled;
    double specularScale = 1;

    /** Reads the Lighting service (if the place has one) and everything it owns. */
    static EngineLighting from(Instance root) {
        EngineLighting l = new EngineLighting();
        if (root == null) { l.prepare(); return l; }
        Instance lighting = root.findFirst("Lighting");
        if (lighting != null) {
            l.ambient = colour(lighting, "Ambient", l.ambient);
            l.outdoorAmbient = colour(lighting, "OutdoorAmbient", l.outdoorAmbient);
            l.colorShiftTop = colour(lighting, "ColorShift_Top", l.colorShiftTop);
            l.colorShiftBottom = colour(lighting, "ColorShift_Bottom", l.colorShiftBottom);
            l.fogColor = colour(lighting, "FogColor", l.fogColor);
            l.brightness = number(lighting, "Brightness", l.brightness);
            l.clockTime = number(lighting, "ClockTime", timeOfDaySeconds(lighting, l.clockTime));
            l.geographicLatitude = number(lighting, "GeographicLatitude", l.geographicLatitude);
            l.environmentDiffuseScale = clamp01(number(lighting, "EnvironmentDiffuseScale", l.environmentDiffuseScale));
            l.environmentSpecularScale = clamp01(number(lighting, "EnvironmentSpecularScale", l.environmentSpecularScale));
            l.exposureCompensation = clamp(number(lighting, "ExposureCompensation", l.exposureCompensation), -3, 3);
            l.fogStart = number(lighting, "FogStart", l.fogStart);
            l.fogEnd = number(lighting, "FogEnd", l.fogEnd);
            l.globalShadows = bool(lighting, "GlobalShadows", l.globalShadows);
            l.shadowSoftness = clamp01(number(lighting, "ShadowSoftness", l.shadowSoftness));
            l.technology = enumName(lighting, "Technology", l.technology);
            l.lightingStyle = enumName(lighting, "LightingStyle", l.lightingStyle);
            for (Instance child : lighting.children) {
                switch (child.className) {
                    case "Sky": readSky(l, child); break;
                    case "Atmosphere": readAtmosphere(l, child); break;
                    case "BloomEffect": l.bloom = true; l.bloomIntensity = number(child, "Intensity", l.bloomIntensity); l.bloomSize = number(child, "Size", l.bloomSize); l.bloomThreshold = number(child, "Threshold", l.bloomThreshold); break;
                    case "BlurEffect": l.blur = true; l.blurSize = number(child, "Size", l.blurSize); break;
                    case "ColorCorrectionEffect": readColorCorrection(l, child); break;
                    case "SunRaysEffect": l.sunRays = true; l.sunRaysIntensity = number(child, "Intensity", l.sunRaysIntensity); l.sunRaysSpread = clamp01(number(child, "Spread", l.sunRaysSpread)); break;
                    case "DepthOfFieldEffect":
                        l.depthOfField = true;
                        l.dofFarIntensity = clamp01(number(child, "FarIntensity", l.dofFarIntensity));
                        l.dofFocusDistance = number(child, "FocusDistance", l.dofFocusDistance);
                        l.dofInFocusRadius = number(child, "InFocusRadius", l.dofInFocusRadius);
                        l.dofNearIntensity = clamp01(number(child, "NearIntensity", l.dofNearIntensity));
                        break;
                    case "ColorGradingEffect": l.colorGrading = true; l.colorGradingTonemapper = enumName(child, "TonemapperPreset", l.colorGradingTonemapper); break;
                    default: break;
                }
            }
        }
        // Clouds: Roblox renders Clouds only when parented to Terrain, but any Clouds under the
        // data model is honoured so a place that stores it loosely still shows weather.
        Instance terrain = root.findFirst("Terrain");
        Instance cloudsInstance = terrain == null ? null : terrain.findFirst("Clouds");
        if (cloudsInstance == null) cloudsInstance = root.findFirst("Clouds");
        if (cloudsInstance != null) {
            l.clouds = true;
            l.cloudCover = clamp01(number(cloudsInstance, "Cover", l.cloudCover));
            l.cloudDensity = clamp01(number(cloudsInstance, "Density", l.cloudDensity));
            l.cloudColor = colour(cloudsInstance, "Color", l.cloudColor);
        }
        // Post effects may also live under the Camera, where Roblox applies them per player.
        Instance camera = root.findFirst("Camera");
        if (camera != null) for (Instance child : camera.children) {
            switch (child.className) {
                case "BloomEffect": l.bloom = true; l.bloomIntensity = number(child, "Intensity", l.bloomIntensity); l.bloomSize = number(child, "Size", l.bloomSize); l.bloomThreshold = number(child, "Threshold", l.bloomThreshold); break;
                case "BlurEffect": l.blur = true; l.blurSize = number(child, "Size", l.blurSize); break;
                case "ColorCorrectionEffect": readColorCorrection(l, child); break;
                case "SunRaysEffect": l.sunRays = true; l.sunRaysIntensity = number(child, "Intensity", l.sunRaysIntensity); l.sunRaysSpread = clamp01(number(child, "Spread", l.sunRaysSpread)); break;
                default: break;
            }
        }
        l.collectLights(root);
        l.prepare();
        return l;
    }

    private static final String[] SKY_KEYS = {"SkyboxRt", "SkyboxLf", "SkyboxUp", "SkyboxDn", "SkyboxFt", "SkyboxBk"};

    private static void readSky(EngineLighting l, Instance sky) {
        l.sky = true;
        l.celestialBodiesShown = bool(sky, "CelestialBodiesShown", l.celestialBodiesShown);
        l.sunAngularSize = Math.max(0, number(sky, "SunAngularSize", l.sunAngularSize));
        l.moonAngularSize = Math.max(0, number(sky, "MoonAngularSize", l.moonAngularSize));
        l.starCount = Math.max(0, number(sky, "StarCount", l.starCount));
        boolean any = false;
        for (int i = 0; i < 6; i++) {
            Object raw = sky.props.get(SKY_KEYS[i]);
            String content = raw instanceof String ? (String) raw : null;
            l.skyTextures[i] = content == null || content.isEmpty() ? null : content;
            any |= l.skyTextures[i] != null;
        }
        l.skyboxTextures = any;
    }

    private static void readAtmosphere(EngineLighting l, Instance a) {
        l.atmosphere = true;
        l.atmosphereDensity = clamp01(number(a, "Density", l.atmosphereDensity));
        l.atmosphereOffset = clamp(number(a, "Offset", l.atmosphereOffset), -1, 1);
        l.atmosphereDecay = clamp01(number(a, "Decay", l.atmosphereDecay));
        l.atmosphereGlare = Math.max(0, number(a, "Glare", l.atmosphereGlare));
        l.atmosphereHaze = Math.max(0, number(a, "Haze", l.atmosphereHaze));
        l.atmosphereColor = colour(a, "Color", l.atmosphereColor);
    }

    private static void readColorCorrection(EngineLighting l, Instance cc) {
        l.colorCorrection = true;
        l.ccEnabled = bool(cc, "Enabled", true);
        l.ccBrightness = number(cc, "Brightness", l.ccBrightness);
        l.ccContrast = number(cc, "Contrast", l.ccContrast);
        l.ccSaturation = number(cc, "Saturation", l.ccSaturation);
        l.ccTint = colour(cc, "TintColor", l.ccTint);
    }

    /** Finds PointLight/SpotLight/SurfaceLight objects and resolves their world position/aim. */
    private void collectLights(Instance root) {
        lights.clear();
        lightCount = 0;
        walkLights(root, 0);
    }

    private void walkLights(Instance node, int depth) {
        if (node == null || depth >= Instance.MAX_DEPTH) return;
        for (Instance child : node.children) {
            if (isLightClass(child.className) && child.getBool("Enabled", true)
                    && child.getNumber("Brightness", 1) > 0
                    && child.parent != null && child.parent.isRenderable()) {   // Roblox lights need a part parent
                lightCount++;
                if (lights.size() < MAX_SHADED_LIGHTS) lights.add(resolveLight(child));
            }
            walkLights(child, depth + 1);
        }
    }

    private static Light resolveLight(Instance node) {
        Instance part = node.parent;
        double[] c = part.cframe();
        double[] s = part.size();
        double[] right = {c[3], c[6], c[9]}, up = {c[4], c[7], c[10]}, back = {c[5], c[8], c[11]};
        Light light = new Light();
        light.kind = node.className.equals("PointLight") ? POINT : node.className.equals("SpotLight") ? SPOT : SURFACE;
        light.color = colour(node, "Color", new double[]{1, 1, 1});
        light.brightness = Math.max(0, node.getNumber("Brightness", 1));
        light.range = Math.max(0.1, Math.min(120, node.getNumber("Range", defaultRange(node.className))));
        if (light.kind == POINT) {
            light.position = new double[]{c[0], c[1], c[2]};
            light.direction = new double[]{0, -1, 0};
            light.cosAngle = 1;
        } else {
            // SpotLight emits from the face centre too, aimed away from that face.
            double[] normal = faceNormal(node.getInt("Face", 5), right, up, back);
            double halfExtent = Math.abs(s[0] * normal[0]) + Math.abs(s[1] * normal[1]) + Math.abs(s[2] * normal[2]);
            light.position = new double[]{c[0] + normal[0] * halfExtent / 2, c[1] + normal[1] * halfExtent / 2, c[2] + normal[2] * halfExtent / 2};
            light.direction = normal;
            double angle = Math.max(1, Math.min(180, node.getNumber("Angle", 90)));
            light.cosAngle = Math.cos(Math.toRadians(angle / 2));
        }
        return light;
    }

    static boolean isLightClass(String className) {
        return className.equals("PointLight") || className.equals("SpotLight") || className.equals("SurfaceLight");
    }

    static double defaultRange(String className) {
        return 16;
    }

    /** Roblox normal ids: 0 Right, 1 Top, 2 Back, 3 Left, 4 Bottom, 5 Front. */
    static double[] faceNormal(int face, double[] right, double[] up, double[] back) {
        switch (face) {
            case 0: return right.clone();
            case 1: return up.clone();
            case 2: return back.clone();
            case 3: return new double[]{-right[0], -right[1], -right[2]};
            case 4: return new double[]{-up[0], -up[1], -up[2]};
            default: return new double[]{-back[0], -back[1], -back[2]};
        }
    }

    /**
     * Sun direction for a time of day and latitude. Matches the behaviour Roblox documents for
     * ClockTime/GeographicLatitude: sunrise in the east at 06:00, highest at 12:00, sunset in the
     * west at 18:00, below the horizon from 18:00 to 06:00, and a noon arc that leans toward +Z
     * (south) and drops as the latitude grows. Roblox's exact ephemeris is not published, so this
     * is the documented approximation.
     */
    static double[] sunDirection(double clockTime, double latitude) {
        double hourAngle = Math.toRadians((clockTime - 6.0) * 15.0);
        double lat = Math.toRadians(clamp(latitude, -90, 90));
        double east = Math.cos(hourAngle);
        double up = Math.sin(hourAngle) * Math.cos(lat);
        double south = Math.sin(hourAngle) * Math.sin(lat);
        double len = Math.sqrt(east * east + up * up + south * south);
        if (len < 1e-9) return new double[]{0, 1, 0};
        return new double[]{east / len, up / len, south / len};
    }

    /** Recomputes the blends the renderer and the shaders use for this frame. */
    void prepare() {
        sunDirection = sunDirection(clockTime, geographicLatitude);
        sunElevation = Math.toDegrees(Math.asin(clamp(sunDirection[1], -1, 1)));
        dayFactor = smoothstep(-6, 8, sunElevation);
        duskFactor = Math.exp(-(sunElevation * sunElevation) / (11.0 * 11.0));
        sunUp = smoothstep(-2.5, 3.0, sunElevation);

        double[] dayTop = {0.24, 0.51, 0.92}, dayHorizon = {0.70, 0.85, 0.98};
        double[] nightTop = {0.012, 0.022, 0.070}, nightHorizon = {0.050, 0.070, 0.150};
        double[] duskTop = {0.16, 0.20, 0.46}, duskHorizon = {1.00, 0.47, 0.22};
        skyTop = mix(mix(nightTop, dayTop, dayFactor), duskTop, duskFactor * 0.65);
        skyHorizon = mix(mix(nightHorizon, dayHorizon, dayFactor), duskHorizon, duskFactor * 0.85);
        // ColorShift_Top reflects off surfaces facing the sun, so it tints the sky near the sun.
        skyTop = clamp01(add(skyTop, mul(colorShiftTop, 0.18 * dayFactor)));
        skyHorizon = clamp01(add(skyHorizon, mul(colorShiftTop, 0.10 * dayFactor)));
        skyGround = mul(skyHorizon, 0.42);

        if (atmosphere) {
            double haze = clamp(atmosphereHaze, 0, 3) * (0.35 + 0.65 * atmosphereDensity);
            skyHorizon = clamp01(mix(skyHorizon, atmosphereColor, clamp(haze * 0.6, 0, 0.85)));
            skyGround = clamp01(mix(skyGround, mul(atmosphereColor, 0.5), clamp(haze * 0.7, 0, 0.85)));
            skyTop = clamp01(mix(skyTop, mul(atmosphereColor, 0.85), clamp(haze * 0.15 + atmosphereDensity * 0.12, 0, 0.5)));
        }
        // Distance fog. Roblox's default FogEnd of 100000 means "no visible fog".
        fogTint = fogColor;
        if (atmosphere) fogTint = mix(fogColor, atmosphereColor, clamp(atmosphereHaze * atmosphereDensity, 0, 0.75));
        fogEnabled = fogEnd > fogStart + 1e-3 && fogEnd < 100000;

        double warmth = clamp((sunElevation - 2) / 18.0, 0, 1);
        sunColor = mix(new double[]{1.0, 0.70, 0.42}, new double[]{1.0, 0.98, 0.94}, warmth);
        sunGain = clamp(brightness, 0, 4) * 0.5 * sunUp;

        // Ambient = Ambient (everywhere) + OutdoorAmbient (outdoors; scaled by the sun's height, see class notes)
        // blended with the sky gradient by EnvironmentDiffuseScale.
        double[] base = add(ambient, mul(outdoorAmbient, 0.35 + 0.65 * dayFactor));
        double[] skyAmbient = mul(mix(skyHorizon, skyTop, 0.35), environmentDiffuseScale);
        double ambientGain = 0.35 + 0.45 * clamp(brightness / 2.0, 0, 1.5);
        ambientDiffuse = mul(clamp01(add(base, skyAmbient)), ambientGain);

        specularScale = environmentSpecularScale;
    }

    /** 0..1 fog blend for a view distance, matching linear Roblox fog between FogStart/FogEnd. */
    double fogFactor(double distance) {
        if (!fogEnabled) return 0;
        return clamp((distance - fogStart) / Math.max(fogEnd - fogStart, 1e-3), 0, 1);
    }

    /** True when at least one post-processing effect would change the image. */
    boolean needsPost() {
        if (blur && blurSize > 0.5) return true;
        if (bloom && bloomIntensity > 0.001) return true;
        if (sunRays && sunRaysIntensity > 0.001 && sunUp > 0.05) return true;
        if (colorCorrection && ccEnabled && (Math.abs(ccBrightness) > 1e-3 || Math.abs(ccContrast) > 1e-3 || Math.abs(ccSaturation) > 1e-3
                || Math.abs(ccTint[0] - 1) > 1e-3 || Math.abs(ccTint[1] - 1) > 1e-3 || Math.abs(ccTint[2] - 1) > 1e-3)) return true;
        return Math.abs(exposureCompensation) > 1e-3;
    }

    /** Human readable summary of the active environment, shown in the editor's limits dialog. */
    String summary() {
        StringBuilder b = new StringBuilder();
        b.append("ClockTime ").append(round(clockTime)).append("  ·  sun ").append(round(sunElevation)).append("°");
        b.append("  ·  Brightness ").append(round(brightness));
        b.append("  ·  ").append(technology).append('/').append(lightingStyle);
        if (globalShadows) b.append("  ·  shadows on");
        if (fogEnabled) b.append("  ·  fog ").append(round(fogStart)).append("–").append(round(fogEnd));
        if (sky) b.append("  ·  Sky");
        if (atmosphere) b.append("  ·  Atmosphere ").append(round(atmosphereDensity * 100)).append("%");
        if (clouds) b.append("  ·  Clouds ").append(round(cloudCover * 100)).append('%');
        if (colorCorrection) b.append("  ·  ColorCorrection");
        if (bloom) b.append("  ·  Bloom");
        if (blur) b.append("  ·  Blur");
        if (sunRays) b.append("  ·  SunRays");
        if (depthOfField) b.append("  ·  DepthOfField (not rendered)");
        if (colorGrading) b.append("  ·  ColorGrading ").append(colorGradingTonemapper);
        if (Math.abs(exposureCompensation) > 1e-3) b.append("  ·  Exposure ").append(round(exposureCompensation));
        b.append("  ·  lights ").append(lightCount);
        return b.toString();
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    private static String enumName(Instance owner, String key, String fallback) {
        Object v = owner == null ? null : owner.props.get(key);
        if (v instanceof Number) {
            int id = ((Number) v).intValue();
            if ("Technology".equals(key)) {
                switch (id) { case 0: return "Legacy"; case 1: return "Voxel"; case 2: return "ShadowMap"; case 3: return "Future"; default: return "Technology " + id; }
            }
            if ("LightingStyle".equals(key)) return id == 0 ? "Realistic" : id == 1 ? "Soft" : "LightingStyle " + id;
            return fallback;
        }
        return v instanceof String ? (String) v : fallback;
    }

    /** TimeOfDay ("14:30:00") is the string twin of ClockTime. */
    private static double timeOfDaySeconds(Instance lighting, double fallback) {
        Object v = lighting == null ? null : lighting.props.get("TimeOfDay");
        if (!(v instanceof String)) return fallback;
        String[] parts = ((String) v).split(":");
        if (parts.length < 2) return fallback;
        try {
            return Integer.parseInt(parts[0].trim()) + Integer.parseInt(parts[1].trim()) / 60.0 + (parts.length > 2 ? Integer.parseInt(parts[2].trim()) / 3600.0 : 0);
        } catch (Exception ignored) { return fallback; }
    }

    static double[] colour(Instance owner, String key, double[] fallback) {
        Object v = owner == null ? null : owner.props.get(key);
        if (v instanceof int[] && ((int[]) v).length == 3) {
            int[] c = (int[]) v;
            return new double[]{clamp01(c[0] / 255.0), clamp01(c[1] / 255.0), clamp01(c[2] / 255.0)};
        }
        if (v instanceof Number) {
            int c = ((Number) v).intValue();
            return new double[]{((c >> 16) & 0xFF) / 255.0, ((c >> 8) & 0xFF) / 255.0, (c & 0xFF) / 255.0};
        }
        return fallback.clone();
    }

    static double number(Instance owner, String key, double fallback) {
        return owner == null ? fallback : owner.getNumber(key, fallback);
    }

    static boolean bool(Instance owner, String key, boolean fallback) {
        return owner == null ? fallback : owner.getBool(key, fallback);
    }

    static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static double[] mul(double[] a, double s) { return new double[]{a[0] * s, a[1] * s, a[2] * s}; }
    static double[] mul(double[] a, double[] b) { return new double[]{a[0] * b[0], a[1] * b[1], a[2] * b[2]}; }
    static double[] mix(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
    static double[] clamp01(double[] c) { return new double[]{clamp01(c[0]), clamp01(c[1]), clamp01(c[2])}; }
    static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    static double clamp01(double v) { return clamp(v, 0, 1); }
    static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
