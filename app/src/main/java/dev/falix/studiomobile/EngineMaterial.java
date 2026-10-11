package dev.falix.studiomobile;

/**
 * Roblox `Enum.Material` values and the surface properties the lighting pipeline shades with.
 *
 * The numeric ids are the published Enum.Material values (Plastic 256, SmoothPlastic 272,
 * Neon 288, Wood 512, ... Glass 1568, Water 2048, Cardboard 2304). The roughness/metalness/
 * emissive/reflectance columns are this build's approximation of Roblox's PBR baselines: a
 * full Metal render ignores the diffuse term, Neon emits its own colour, glass and ice are
 * smooth and reflective, masonry and fabric are rough and diffuse. Texture assets,
 * `MaterialVariant` and `SurfaceAppearance` maps are not applied yet (see docs/ENGINE.md).
 */
final class EngineMaterial {
    final String name;
    final double roughness, metalness, emissive, reflectance;

    private EngineMaterial(String name, double roughness, double metalness, double emissive, double reflectance) {
        this.name = name;
        this.roughness = roughness;
        this.metalness = metalness;
        this.emissive = emissive;
        this.reflectance = reflectance;
    }

    static final EngineMaterial PLASTIC = new EngineMaterial("Plastic", 0.45, 0, 0, 0);

    /** Material ids offered by the Properties picker, in Enum.Material order. */
    static final int[] PICKER_IDS = {
            256, 272, 288, 512, 528, 784, 788, 800, 804, 816, 820, 832, 836, 848, 864, 880, 896, 912,
            1040, 1056, 1072, 1088, 1280, 1284, 1296, 1312, 1328, 1344, 1360, 1376, 1392, 1536, 1552, 1568, 1584, 2304, 2310, 2311
    };

    static String[] pickerNames() {
        String[] names = new String[PICKER_IDS.length];
        for (int i = 0; i < PICKER_IDS.length; i++) names[i] = of(PICKER_IDS[i]).name;
        return names;
    }

    static EngineMaterial of(int value) {
        switch (value) {
            case 272: return new EngineMaterial("SmoothPlastic", 0.18, 0, 0, 0.05);
            case 288: return new EngineMaterial("Neon", 0.40, 0, 1.00, 0);
            case 512: return new EngineMaterial("Wood", 0.80, 0, 0, 0);
            case 528: return new EngineMaterial("WoodPlanks", 0.75, 0, 0, 0);
            case 784: return new EngineMaterial("Marble", 0.22, 0, 0, 0.20);
            case 788: return new EngineMaterial("Basalt", 0.85, 0, 0, 0);
            case 800: return new EngineMaterial("Slate", 0.90, 0, 0, 0);
            case 804: return new EngineMaterial("CrackedLava", 0.90, 0, 0.35, 0);
            case 816: return new EngineMaterial("Concrete", 0.90, 0, 0, 0);
            case 820: return new EngineMaterial("Limestone", 0.80, 0, 0, 0);
            case 832: return new EngineMaterial("Granite", 0.65, 0, 0, 0.05);
            case 836: return new EngineMaterial("Pavement", 0.85, 0, 0, 0);
            case 848: return new EngineMaterial("Brick", 0.85, 0, 0, 0);
            case 864: return new EngineMaterial("Pebble", 0.92, 0, 0, 0);
            case 880: return new EngineMaterial("Cobblestone", 0.90, 0, 0, 0);
            case 896: return new EngineMaterial("Rock", 0.90, 0, 0, 0);
            case 912: return new EngineMaterial("Sandstone", 0.85, 0, 0, 0);
            case 1040: return new EngineMaterial("CorrodedMetal", 0.70, 0.70, 0, 0.05);
            case 1056: return new EngineMaterial("DiamondPlate", 0.35, 0.90, 0, 0.10);
            case 1072: return new EngineMaterial("Foil", 0.20, 0.95, 0, 0.15);
            case 1088: return new EngineMaterial("Metal", 0.30, 0.90, 0, 0.10);
            case 1280: return new EngineMaterial("Grass", 0.95, 0, 0, 0);
            case 1284: return new EngineMaterial("LeafyGrass", 0.95, 0, 0, 0);
            case 1296: return new EngineMaterial("Sand", 0.90, 0, 0, 0);
            case 1312: return new EngineMaterial("Fabric", 1.00, 0, 0, 0);
            case 1328: return new EngineMaterial("Snow", 0.70, 0, 0, 0.05);
            case 1344: return new EngineMaterial("Mud", 0.95, 0, 0, 0);
            case 1360: return new EngineMaterial("Ground", 0.95, 0, 0, 0);
            case 1376: return new EngineMaterial("Asphalt", 0.85, 0, 0, 0);
            case 1392: return new EngineMaterial("Salt", 0.80, 0, 0, 0);
            case 1536: return new EngineMaterial("Ice", 0.10, 0, 0, 0.30);
            case 1552: return new EngineMaterial("Glacier", 0.15, 0, 0, 0.25);
            case 1568: return new EngineMaterial("Glass", 0.05, 0, 0, 0.65);
            case 1584: return new EngineMaterial("ForceField", 0.10, 0, 0.50, 0);
            case 1792: return new EngineMaterial("Air", 0.90, 0, 0, 0);
            case 2048: return new EngineMaterial("Water", 0.05, 0, 0, 0.40);
            case 2304: return new EngineMaterial("Cardboard", 0.95, 0, 0, 0);
            case 2305: return new EngineMaterial("Carpet", 1.00, 0, 0, 0);
            case 2306: return new EngineMaterial("CeramicTiles", 0.12, 0, 0, 0.35);
            case 2307: return new EngineMaterial("ClayRoofTiles", 0.60, 0, 0, 0.05);
            case 2308: return new EngineMaterial("RoofShingles", 0.85, 0, 0, 0);
            case 2309: return new EngineMaterial("Leather", 0.60, 0, 0, 0);
            case 2310: return new EngineMaterial("Plaster", 0.80, 0, 0, 0);
            case 2311: return new EngineMaterial("Rubber", 0.90, 0, 0, 0);
            default: return PLASTIC;
        }
    }

    static String nameOf(int value) { return of(value).name; }

    /** Blinn-Phong exponent derived from roughness, the GLES2 stand-in for GGX roughness. */
    double shininess() {
        double r = Math.max(roughness, 0.02);
        double value = 2.0 / Math.pow(r, 4) - 2.0;
        return Math.max(2, Math.min(value, 512));
    }
}
