package dev.falix.studiomobile;

import java.io.*;
import java.util.*;

/** Serializes an engine tree to Roblox XML (rbxlx, version 4). Unknown classes round-trip by name. */
final class PlaceWriter {
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') b.append("&amp;");
            else if (c == '<') b.append("&lt;");
            else if (c == '>') b.append("&gt;");
            else if (c == '"') b.append("&quot;");
            else b.append(c);
        }
        return b.toString();
    }
    static String ref() { return "RBX" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.US); }

    static String generate(Instance root) { StringBuilder out = new StringBuilder(4096); writeRoot(out, root); return out.toString(); }

    private static void writeRoot(StringBuilder out, Instance root) {
        out.append("<roblox xmlns:xmime=\"http://www.w3.org/2005/05/xmlmime\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"4\"><External>null</External><External>nil</External>");
        for (Instance c : root.children) write(out, c, 0);
        out.append("</roblox>\n");
    }

    private static void indent(StringBuilder out, int depth) { for (int i = 0; i < depth; i++) out.append("  "); }

    private static void write(StringBuilder out, Instance n, int depth) {
        String r = n.getString("__referent", null);
        if (r == null) { r = ref(); n.props.put("__referent", r); }
        indent(out, depth);
        out.append("<Item class=\"").append(esc(n.className)).append("\" referent=\"").append(r).append("\">");
        out.append("<Properties><string name=\"Name\">").append(esc(n.name)).append("</string>");
        for (Map.Entry<String, Object> e : n.props.entrySet()) writeProperty(out, n, e.getKey(), e.getValue(), depth + 1);
        out.append("\n");
        indent(out, depth);
        out.append("</Properties>");
        if (n.children.isEmpty()) { out.append("</Item>\n"); return; }
        out.append("\n");
        for (Instance c : n.children) write(out, c, depth + 1);
        indent(out, depth);
        out.append("</Item>\n");
    }

    /** Vec3 values may be stored as Instance.Vector3 or as double[3]; arrays keep the exact property type. */
    private static void writeProperty(StringBuilder out, Instance owner, String key, Object v, int depth) {
        if (key.startsWith("__") || key.equals("Name")) return;
        if ("Color3uint8".equals(key) && v instanceof Number) {
            out.append("<Color3uint8 name=\"Color3uint8\">").append(Long.toString(0xFF000000L | (((Number) v).intValue() & 0xFFFFFFL), 10)).append("</Color3uint8>");
            return;
        }
        if (owner.tags.containsKey(key)) {
            String hinted = owner.tags.get(key);
            if ("token".equals(hinted) && v instanceof Number) {
                out.append("<token name=\"").append(esc(key)).append("\">").append(((Number) v).longValue()).append("</token>");
                return;
            }
            if ("int64".equals(hinted) && v instanceof Number) {
                out.append("<int64 name=\"").append(esc(key)).append("\">").append(((Number) v).longValue()).append("</int64>");
                return;
            }
        }
        String tag = v instanceof String ? "string" : v instanceof Boolean ? "bool" : v instanceof Float ? "float"
                : v instanceof Double ? "double" : v instanceof Integer ? "int" : v instanceof Long ? "int64" : null;
        if (tag != null) {
            out.append("<").append(tag).append(" name=\"").append(esc(key)).append("\">").append(esc(String.valueOf(v))).append("</").append(tag).append(">");
            return;
        }
        if (v instanceof Instance.Vector3) {
            Instance.Vector3 p = (Instance.Vector3) v;
            out.append("<Vector3 name=\"").append(esc(key)).append("\"><X>").append(num(p.x)).append("</X><Y>").append(num(p.y)).append("</Y><Z>").append(num(p.z)).append("</Z></Vector3>");
            return;
        }
        if (v instanceof double[] && ((double[]) v).length == 12) {
            double[] c = (double[]) v;
            out.append("<CoordinateFrame name=\"").append(esc(key)).append("\"><X>").append(num(c[0])).append("</X><Y>").append(num(c[1])).append("</Y><Z>").append(num(c[2])).append("</Z>");
            String[] names = {"R00", "R01", "R02", "R10", "R11", "R12", "R20", "R21", "R22"};
            for (int i = 0; i < 9; i++) out.append("<").append(names[i]).append(">").append(num(c[3 + i])).append("</").append(names[i]).append(">");
            out.append("</CoordinateFrame>");
            return;
        }
        if (v instanceof double[] && ((double[]) v).length == 3) {
            double[] c = (double[]) v;
            out.append("<Vector3 name=\"").append(esc(key)).append("\"><X>").append(num(c[0])).append("</X><Y>").append(num(c[1])).append("</Y><Z>").append(num(c[2])).append("</Z></Vector3>");
            return;
        }
        if (v instanceof int[]) {
            int[] c = (int[]) v;
            out.append("<Color3 name=\"").append(esc(key)).append("\"><R>").append(num(c[0] / 255.0)).append("</R><G>").append(num(c[1] / 255.0)).append("</G><B>").append(num(c[2] / 255.0)).append("</B></Color3>");
            return;
        }
        // Anything not modeled is preserved when it knows its original XML tag.
        String original = v instanceof Raw ? ((Raw) v).xml : null;
        if (original != null) out.append(original);
    }

    static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.format(Locale.US, "%.6f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s + "0" : s;
    }

    /** Verbatim XML fragment kept for property types this build does not model. */
    static final class Raw { final String xml; Raw(String xml) { this.xml = xml; } }
}
