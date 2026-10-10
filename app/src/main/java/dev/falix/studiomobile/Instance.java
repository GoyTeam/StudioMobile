package dev.falix.studiomobile;

import java.util.*;

/**
 * Engine scene node. One class for the whole Roblox data model; properties keep their
 * native Roblox names so decoded places render, edit and re-serialize without a lossy map.
 */
final class Instance {
    String className, name;
    Instance parent;
    final ArrayList<Instance> children = new ArrayList<>();
    final LinkedHashMap<String, Object> props = new LinkedHashMap<>();
    /** Original XML tag per property ("token", "int64", ...), so re-serialization stays faithful. */
    final HashMap<String, String> tags = new HashMap<>();

    Instance(String className, String name) { this.className = className; this.name = name; }

    void add(Instance child) { if (child.parent != null) child.parent.children.remove(child); child.parent = this; children.add(child); }
    void add(int index, Instance child) { if (child.parent != null) child.parent.children.remove(child); child.parent = this; children.add(index, child); }
    void remove() { if (parent != null) { parent.children.remove(this); parent = null; } }
    boolean isA(String cls) { return className.equals(cls); }
    int childIndex() { return parent == null ? -1 : parent.children.indexOf(this); }

    Instance findFirst(String cls) {
        for (Instance c : children) { if (c.className.equals(cls)) return c; Instance r = c.findFirst(cls); if (r != null) return r; }
        return null;
    }
    ArrayList<Instance> all(String cls) {
        ArrayList<Instance> out = new ArrayList<>();
        collect(cls, out);
        return out;
    }
    private void collect(String cls, ArrayList<Instance> out) {
        for (Instance c : children) { if (cls == null || c.className.equals(cls)) out.add(c); c.collect(cls, out); }
    }
    Instance childNamed(String n) { for (Instance c : children) if (c.name.equals(n)) return c; return null; }
    boolean isDescendantOf(Instance other) { for (Instance p = parent; p != null; p = p.parent) if (p == other) return true; return false; }

    /** True for classes that render as a solid box in the viewport. */
    boolean isRenderable() {
        switch (className) {
            case "Part": case "SpawnLocation": case "WedgePart": case "CornerWedgePart":
            case "TrussPart": case "MeshPart": case "UnionOperation": case "NegateOperation":
            case "IntersectOperation": case "Seat": case "VehicleSeat": case "Platform": case "FlagStand":
                return true;
            default: return false;
        }
    }
    /** True for container classes shown in Explorer with a folder icon. */
    boolean isContainer() {
        return children.size() > 0 || className.equals("Model") || className.equals("Folder") || className.equals("Workspace")
            || className.equals("Configuration") || className.equals("StarterPlayer") || className.equals("StarterCharacterScripts");
    }
    boolean isService() { return parent != null && parent.className.equals("DataModel"); }
    /** Visible bounds (world AABB) used by picking and the character controller. */
    double[] bounds() {
        double[] c = cframe();
        double[] s = size();
        double hx = Math.abs(s[0]) / 2, hy = Math.abs(s[1]) / 2, hz = Math.abs(s[2]) / 2;
        double ex = Math.abs(c[3]) * hx + Math.abs(c[4]) * hy + Math.abs(c[5]) * hz;
        double ey = Math.abs(c[6]) * hx + Math.abs(c[7]) * hy + Math.abs(c[8]) * hz;
        double ez = Math.abs(c[9]) * hx + Math.abs(c[10]) * hy + Math.abs(c[11]) * hz;
        return new double[]{c[0], c[1], c[2], ex, ey, ez};
    }

    // ---- typed property access -------------------------------------------------
    static final class Vector3 { final double x, y, z; Vector3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; } }

    String getString(String key, String def) { Object v = props.get(key); return v instanceof String ? (String) v : def; }
    boolean getBool(String key, boolean def) { Object v = props.get(key); return v instanceof Boolean ? (Boolean) v : def; }
    double getNumber(String key, double def) {
        Object v = props.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Float) return ((Float) v).doubleValue();
        return def;
    }
    int getInt(String key, int def) { Object v = props.get(key); return v instanceof Number ? ((Number) v).intValue() : def; }
    int getColor() { return getInt("Color3uint8", 0x00A2FF) & 0xFFFFFF; }
    double[] size() {
        Object v = props.get("size");
        if (v instanceof Vector3) { Vector3 s = (Vector3) v; return new double[]{s.x, s.y, s.z}; }
        Object v2 = props.get("Size");
        if (v2 instanceof Vector3) { Vector3 s = (Vector3) v2; return new double[]{s.x, s.y, s.z}; }
        return new double[]{1, 1, 1};
    }
    /** cframe(): [x,y,z,r00,r01,r02,r10,r11,r12,r20,r21,r22] world-ish (parent CFrame multiplication is applied). */
    double[] cframe() {
        Object v = props.get("CFrame");
        double[] local;
        if (v instanceof double[] && ((double[]) v).length == 12) local = (double[]) v;
        else {
            Object p = props.get("Position");
            if (p instanceof Vector3) { Vector3 q = (Vector3) p; local = new double[]{q.x, q.y, q.z, 1, 0, 0, 0, 1, 0, 0, 0, 1}; }
            else local = new double[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        if (parent != null && parent.props.containsKey("CFrame") && parent.isRenderable()) {
            double[] m = parent.cframe();
            double[] out = new double[12];
            out[0] = m[0] + m[3] * local[0] + m[4] * local[1] + m[5] * local[2];
            out[1] = m[1] + m[6] * local[0] + m[7] * local[1] + m[8] * local[2];
            out[2] = m[2] + m[9] * local[0] + m[10] * local[1] + m[11] * local[2];
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
                out[3 + r * 3 + c] = m[3 + r * 3] * local[3 + c] + m[4 + r * 3] * local[6 + c] + m[5 + r * 3] * local[9 + c];
            }
            return out;
        }
        return local;
    }
    void setCframe(double[] c) { props.put("CFrame", c.clone()); props.remove("Position"); }
    void setPosition(double x, double y, double z) {
        double[] c = cframe(); c[0] = x; c[1] = y; c[2] = z; setCframe(c);
    }

    /** Rotate around the world Y axis in 90 degree steps; keeps the pivot. */
    void rotateQuarter(int times) {
        double[] c = cframe();
        double a = Math.toRadians(90 * times), cos = Math.cos(a), sin = Math.sin(a);
        double[] r = new double[9];
        r[0] = cos; r[1] = 0; r[2] = sin; r[3] = 0; r[4] = 1; r[5] = 0; r[6] = -sin; r[7] = 0; r[8] = cos;
        double[] out = new double[12];
        out[0] = c[0]; out[1] = c[1]; out[2] = c[2];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double v = 0;
            for (int k = 0; k < 3; k++) v += r[i * 3 + k] * c[3 + k * 3 + j];
            out[3 + i * 3 + j] = v;
        }
        setCframe(out);
    }

    /** Default properties for a new instance of the given class, mirroring Studio's insert behavior. */
    static Instance create(String cls, String name) {
        Instance i = new Instance(cls, name);
        switch (cls) {
            case "Part": case "WedgePart": case "CornerWedgePart": case "TrussPart": case "Seat": case "VehicleSeat":
            case "MeshPart": case "UnionOperation":
                i.props.put("size", new Vector3(4, 1, 2));
                i.props.put("CFrame", new double[]{0, 0.5, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1});
                i.props.put("Color3uint8", 0x639BFF);
                i.props.put("Anchored", false);
                i.props.put("CanCollide", true);
                i.props.put("Transparency", 0d);
                i.props.put("Material", 256);
                i.props.put("Shape", 1);
                break;
            case "SpawnLocation":
                i.props.put("size", new Vector3(6, 1, 6));
                i.props.put("CFrame", new double[]{0, 0.5, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1});
                i.props.put("Color3uint8", 0x2BBF4B);
                i.props.put("Anchored", true);
                i.props.put("CanCollide", true);
                i.props.put("Enabled", true);
                i.props.put("Neutral", true);
                break;
            default: break;
        }
        return i;
    }

    static final String[] INSERTABLE = {"Part", "SpawnLocation", "Folder", "Model", "Script", "PointLight"};

    @Override public String toString() { return name + " (" + className + ")"; }
}
