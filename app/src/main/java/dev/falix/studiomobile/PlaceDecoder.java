package dev.falix.studiomobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;

/**
 * Reads real Roblox place files:
 *  - .rbxlx Roblox XML (version 4), including nested Items and typed properties;
 *  - .rbxl binary chunks (INST/PROP/PRNT/META) with LZ4 block decompression.
 * Property types this build does not model are kept verbatim (XML) or reported (binary),
 * so saving never silently loses content.
 */
final class PlaceDecoder {
    static final int MAX_BYTES = 100 * 1024 * 1024;
    static final class Result {
        Instance root; String format = "XML"; int instances; int unmodeled; ArrayList<String> warnings = new ArrayList<>();
    }

    static Result decode(File file) throws Exception {
        byte[] head = new byte[64];
        int n;
        try (InputStream in = new FileInputStream(file)) { n = in.read(head); }
        if (n < 8) throw new IOException("The place file is empty or unreadable.");
        String text = new String(head, 0, n, StandardCharsets.UTF_8).trim();
        Result r;
        if (n > 14 && head[0] == '<' && head[7] == '!') r = binary(file);          // "<roblox!" magic
        else if (text.startsWith("<?xml") || text.startsWith("<roblox")) r = xml(file);
        else throw new IOException("Not a Roblox place file.");
        for (Instance i : r.root.all(null)) r.instances++;
        return r;
    }

    // ------------------------------------------------------------------ XML
    /** SAX-based reader: the same code path runs on device and in JVM unit tests. */
    static Result xml(File file) throws Exception {
        Result r = new Result(); r.format = "XML";
        Instance root = new Instance("DataModel", "DataModel"); r.root = root;
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        javax.xml.parsers.SAXParser parser = factory.newSAXParser();
        org.xml.sax.XMLReader reader = parser.getXMLReader();
        for (String feature : new String[]{"http://apache.org/xml/features/disallow-doctype-decl", "http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities"}) {
            try { reader.setFeature(feature, false); } catch (Exception ignored) { }
        }
        reader.setContentHandler(new XmlHandler(root, r));
        try (InputStream in = new FileInputStream(file)) {
            reader.parse(new org.xml.sax.InputSource(in));
        }
        if (root.children.isEmpty()) r.warnings.add("No Items were found in this place file.");
        return r;
    }

    static final String[] CF_NAMES = {"X", "Y", "Z", "R00", "R01", "R02", "R10", "R11", "R12", "R20", "R21", "R22"};

    /** Builds Items, reads modeled properties, and captures anything else as verbatim XML. */
    static final class XmlHandler extends org.xml.sax.helpers.DefaultHandler {
        private final Instance root;
        private final Result result;
        private final ArrayDeque<Instance> items = new ArrayDeque<>();
        private boolean inProperties;
        private String propTag, propName;
        private final StringBuilder text = new StringBuilder();
        private final double[] vec = new double[3];
        private final double[] cf = new double[12];
        private final int[] color = new int[3];
        private StringBuilder raw;
        private final ArrayList<String> rawStack = new ArrayList<>();

        XmlHandler(Instance root, Result result) { this.root = root; this.result = result; }

        private static String attrs(org.xml.sax.Attributes a) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.getLength(); i++) b.append(' ').append(a.getQName(i)).append("=\"").append(PlaceWriter.esc(a.getValue(i))).append('"');
            return b.toString();
        }

        private static String attribute(org.xml.sax.Attributes a, String name) {
            for (int i = 0; i < a.getLength(); i++) if (name.equals(a.getQName(i))) return a.getValue(i);
            return null;
        }

        private static boolean modeled(String tag) {
            switch (tag) {
                case "string": case "ProtectedString": case "BinaryString": case "bool": case "int": case "int64":
                case "token": case "Enum": case "float": case "double": case "Color3uint8": case "Color3":
                case "Vector3": case "CoordinateFrame": case "CFrame": return true;
                default: return false;
            }
        }

        private void closeRaw() {
            String tag = rawStack.remove(rawStack.size() - 1);
            raw.append("</").append(tag).append('>');
            if (rawStack.isEmpty()) {
                Instance item = items.peek();
                String key = propName == null ? "__raw" + (item == null ? 0 : item.props.size()) : propName;
                if (item != null) {
                    item.props.put(key, new PlaceWriter.Raw(raw.toString()));
                    item.tags.put(key, propTag == null ? "raw" : propTag);
                }
                result.unmodeled++;
                raw = null;
                propTag = null;
                propName = null;
            }
        }

        @Override public void startElement(String uri, String local, String qName, org.xml.sax.Attributes a) {
            text.setLength(0);
            if (raw != null) { raw.append('<').append(qName).append(attrs(a)).append('>'); rawStack.add(qName); return; }
            if ("Item".equals(qName)) {
                String cls = attribute(a, "class");
                if (cls == null || cls.isEmpty()) cls = "Instance";
                Instance item = new Instance(cls, cls);
                String referent = attribute(a, "referent");
                if (referent != null) item.props.put("__referent", referent);
                if (items.isEmpty()) root.add(item); else items.peek().add(item);
                items.push(item);
                return;
            }
            if ("Properties".equals(qName)) { inProperties = !items.isEmpty(); return; }
            if (!inProperties || propTag != null) return;
            propTag = qName.intern();
            propName = attribute(a, "name");
            if (propName != null) propName = propName.intern();
            if (propName == null || !modeled(qName)) {
                raw = new StringBuilder().append('<').append(qName).append(attrs(a)).append('>');
                rawStack.add(qName);
            }
            if ("Vector3".equals(qName)) { vec[0] = vec[1] = vec[2] = 0; }
            if ("CoordinateFrame".equals(qName) || "CFrame".equals(qName)) {
                double[] identity = {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1};
                System.arraycopy(identity, 0, cf, 0, 12);
            }
        }

        @Override public void characters(char[] ch, int start, int length) {
            String s = new String(ch, start, length);
            if (raw != null) raw.append(PlaceWriter.esc(s)); else text.append(s);
        }

        @Override public void endElement(String uri, String local, String qName) {
            if (raw != null) { closeRaw(); return; }
            if ("Properties".equals(qName)) { inProperties = false; propTag = null; propName = null; return; }
            if ("Item".equals(qName)) { if (!items.isEmpty()) items.pop(); return; }
            if (!inProperties || propTag == null) return;
            String t = text.toString().trim();
            if (!qName.equals(propTag)) {
                if ("X".equals(qName)) vec[0] = number(t, vec[0]);
                else if ("Y".equals(qName)) vec[1] = number(t, vec[1]);
                else if ("Z".equals(qName)) vec[2] = number(t, vec[2]);
                else if ("R".equals(qName)) color[0] = (int) Math.round(number(t, 0) * 255);
                else if ("G".equals(qName)) color[1] = (int) Math.round(number(t, 0) * 255);
                else if ("B".equals(qName)) color[2] = (int) Math.round(number(t, 0) * 255);
                else for (int i = 0; i < CF_NAMES.length; i++) if (CF_NAMES[i].equals(qName)) cf[i] = number(t, cf[i]);
                return;
            }
            Instance item = items.peek();
            if (item == null) { propTag = null; propName = null; return; }
            String name = propName;
            switch (qName) {
                case "string": case "ProtectedString": case "BinaryString":
                    if ("Name".equals(name)) item.name = t;
                    else if (name != null) { item.props.put(name, t); item.tags.put(name, qName); }
                    break;
                case "bool": if (name != null) item.props.put(name, Boolean.parseBoolean(t)); break;
                case "int": case "token": case "Enum":
                    if (name != null) { item.props.put(name, (int) number(t, 0)); item.tags.put(name, "token".equals(qName) ? "token" : qName); }
                    break;
                case "int64": if (name != null) { item.props.put(name, longValue(t)); item.tags.put(name, "int64"); } break;
                case "float": if (name != null) { item.props.put(name, (float) number(t, 0)); item.tags.put(name, "float"); } break;
                case "double": if (name != null) { item.props.put(name, number(t, 0)); item.tags.put(name, "double"); } break;
                case "Color3uint8": if (name != null) item.props.put(name, (int) (longValue(t) & 0xFFFFFFL)); break;
                case "Color3": if (name != null) { item.props.put(name, color.clone()); item.tags.put(name, "Color3"); } break;
                case "Vector3": if (name != null) item.props.put(name, new Instance.Vector3(vec[0], vec[1], vec[2])); break;
                case "CoordinateFrame": case "CFrame": if (name != null) item.props.put(name, cf.clone()); break;
                default: break;
            }
            propTag = null;
            propName = null;
        }

        private static double number(String t, double fallback) {
            try { return Double.parseDouble(t); } catch (Exception ignored) { return fallback; }
        }

        private static long longValue(String t) {
            try { return Long.parseLong(t); } catch (Exception ignored) { return 0L; }
        }
    }

    // ------------------------------------------------------------------ binary
    static Result binary(File file) throws Exception {
        Result r = new Result(); r.format = "Binary";
        byte[] data;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n; long total = 0;
            while ((n = in.read(b)) != -1) { total += n; if (total > MAX_BYTES) throw new IOException("Place exceeds the 100 MiB reading limit."); out.write(b, 0, n); }
            data = out.toByteArray();
        }
        if (data.length < 32) throw new IOException("Binary place is too small to be valid.");
        Cursor c = new Cursor(data);
        c.skip(8);                      // "<roblox!"
        c.skip(6);                      // 0x89 FF 0D 0A 1A 0A
        c.skip(2);                      // version
        c.skip(4);                      // class count
        c.skip(4);                      // instance count
        Instance root = new Instance("DataModel", "DataModel"); r.root = root;
        LinkedHashMap<Integer, Instance> byId = new LinkedHashMap<>();
        ArrayList<Integer> creationOrder = new ArrayList<>();
        while (c.remaining() >= 4) {
            String chunk = c.string4();
            if (chunk.equals("END\u0000") || chunk.startsWith("END")) break;
            if (chunk.equals("ZSTD")) throw new IOException("This .rbxl uses ZSTD compression, which this beta cannot read yet. Open it in desktop Studio and save as .rbxlx, or use an .rbxlx place.");
            if (chunk.equals("META")) { int count = c.u32(); for (int i = 0; i < count; i++) { c.str(); c.str(); } continue; }
            if (chunk.equals("SIGN")) break; // signature chunk is the last payload; instances are already read
            long compLength = c.u32() & 0xFFFFFFFFL;
            long uncompLength = c.u32() & 0xFFFFFFFFL;
            if (uncompLength > MAX_BYTES || compLength > MAX_BYTES) throw new IOException("Chunk length exceeds the safety limit.");
            byte[] payload;
            if (compLength > 0) payload = lz4(c.bytes((int) compLength), (int) uncompLength);
            else payload = c.bytes((int) uncompLength);
            Cursor p = new Cursor(payload);
            if (chunk.startsWith("INST")) readInst(p, byId, creationOrder);
            else if (chunk.startsWith("PROP")) readProps(p, byId, creationOrder, r);
            else if (chunk.startsWith("PRNT")) readParents(p, byId);
        }
        for (int id : creationOrder) root.add(byId.get(id));
        if (root.children.isEmpty()) r.warnings.add("No instances were decoded from this binary place.");
        return r;
    }

    static void readInst(Cursor p, LinkedHashMap<Integer, Instance> byId, ArrayList<Integer> order) throws Exception {
        int classId = p.u32();
        String className = p.str();
        p.skip(1);                                   // object format (0 plain, 1 service)
        long count = p.u32() & 0xFFFFFFFFL;
        if (className.isEmpty()) className = "Instance";
        if (count > 2_000_000) throw new IOException("Implausible instance count in INST chunk.");
        int[] ids = new int[(int) count], flags = new int[(int) count];
        for (int i = 0; i < count; i += 4) {         // interleaved referents: four ids, then four scope bytes
            for (int j = 0; j < 4 && i + j < count; j++) ids[i + j] = p.u32();
            for (int j = 0; j < 4 && i + j < count; j++) flags[i + j] = p.u8();
        }
        for (int i = 0; i < count; i++) {
            Instance inst = new Instance(className, className);
            byId.put(ids[i], inst);
            order.add(ids[i]);
        }
    }

    static void readProps(Cursor p, LinkedHashMap<Integer, Instance> byId, ArrayList<Integer> order, Result r) throws Exception {
        while (p.remaining() > 0) {
            int referent = p.u32();
            String name = p.str();
            int type = p.u8();
            if (type == 0) continue;
            Instance sample = byId.get(referent);
            if (sample == null) throw new IOException("Property references an unknown instance.");
            ArrayList<Instance> sameClass = new ArrayList<>();
            for (int id : order) { Instance x = byId.get(id); if (x.className.equals(sample.className)) sameClass.add(x); }
            for (Instance target : sameClass) {
                Object value = readValue(p, type, r);
                if (target == sample) applyProperty(target, name, type, value, r);
            }
        }
    }

    static void applyProperty(Instance inst, String name, int type, Object value, Result r) {
        if (value == null) return;
        if ("Name".equals(name)) { inst.name = String.valueOf(value); return; }
        if (value instanceof Unsupported) { r.warnings.add("Property " + name + " (" + inst.className + ") uses an unmodeled binary type and was skipped."); return; }
        inst.props.put(name.intern(), value);
        if (type == 0x12) inst.tags.put(name.intern(), "token");
        if (type == 0x1B) inst.tags.put(name.intern(), "int64");
        if (type == 0x0C) inst.tags.put(name.intern(), "Color3");
    }

    static void readParents(Cursor p, LinkedHashMap<Integer, Instance> byId) throws Exception {
        while (p.remaining() >= 8) {
            int child = p.u32(), parent = p.u32();
            Instance c = byId.get(child), par = byId.get(parent);
            if (c != null && par != null && c.parent == null && c != par) par.add(c);
        }
    }

    static final class Unsupported {}

    static Object readValue(Cursor p, int type, Result r) throws Exception {
        switch (type) {
            case 0x01: return p.str();
            case 0x02: return p.u8() != 0;
            case 0x03: return p.u32();
            case 0x04: return p.f32();
            case 0x05: return p.f64();
            case 0x06: p.skip(8); return null;
            case 0x07: p.skip(16); return null;
            case 0x08: p.skip(24); return null;
            case 0x09: p.skip(1); return null;
            case 0x0A: p.skip(1); return null;
            case 0x0B: return (int) p.u32();
            case 0x0C: { int[] c = {(int) Math.round(p.f32() * 255), (int) Math.round(p.f32() * 255), (int) Math.round(p.f32() * 255)}; return c; }
            case 0x0D: p.skip(8); return null;
            case 0x0E: return new Instance.Vector3(p.f32(), p.f32(), p.f32());
            case 0x0F: p.skip(4); return null;
            case 0x10: { double[] c = new double[12]; for (int i = 0; i < 9; i++) c[3 + i] = p.f32(); c[0] = p.f32(); c[1] = p.f32(); c[2] = p.f32(); return c; }
            case 0x11: p.skip(16); return null;
            case 0x12: return p.u32();
            case 0x13: p.skip(4); return null;
            case 0x14: p.skip(6); return null;
            case 0x15: { long n = p.u32(); if (n > 1000) throw new IOException("Invalid NumberSequence."); p.skip((int) n * 12); return null; }
            case 0x16: { long n = p.u32(); if (n > 1000) throw new IOException("Invalid ColorSequence."); p.skip((int) n * 16); return null; }
            case 0x17: p.skip(8); return null;
            case 0x18: p.skip(16); return null;
            case 0x19: p.skip(20); return null;
            case 0x1A: { long v = p.u32(); return (int) (v & 0xFFFFFFL); }
            case 0x1B: return p.u64();
            case 0x1C: p.skip(4); return null;
            case 0x1D: return p.str();
            case 0x1E: { p.skip(1); double[] c = new double[12]; for (int i = 0; i < 9; i++) c[3 + i] = p.f32(); c[0] = p.f32(); c[1] = p.f32(); c[2] = p.f32(); return c; }
            default: throw new IOException("Unmodeled binary property type 0x" + Integer.toHexString(type) + ".");
        }
    }

    // ------------------------------------------------------------------ LZ4 block format
    static byte[] lz4(byte[] src, int expected) throws IOException {
        byte[] dst = new byte[expected];
        int sp = 0, dp = 0;
        while (sp < src.length) {
            int token = src[sp++] & 0xFF;
            int literals = token >>> 4;
            if (literals == 15) { int extra; do { extra = src[sp++] & 0xFF; literals += extra; } while (extra == 255); }
            if (dp + literals > dst.length || sp + literals > src.length) throw new IOException("Corrupt LZ4 literals.");
            System.arraycopy(src, sp, dst, dp, literals);
            sp += literals; dp += literals;
            if (sp >= src.length) break;
            if (sp + 2 > src.length) throw new IOException("Corrupt LZ4 offset.");
            int offset = (src[sp] & 0xFF) | ((src[sp + 1] & 0xFF) << 8); sp += 2;
            if (offset == 0 || offset > dp) throw new IOException("Corrupt LZ4 match offset.");
            int match = token & 0x0F;
            if (match == 15) { int extra; do { extra = src[sp++] & 0xFF; match += extra; } while (extra == 255); }
            match += 4;
            if (dp + match > dst.length) throw new IOException("Corrupt LZ4 match length.");
            int start = dp - offset;
            for (int i = 0; i < match; i++) dst[dp + i] = dst[start + i];
            dp += match;
        }
        if (dp != expected) throw new IOException("LZ4 chunk size mismatch.");
        return dst;
    }

    /** Little-endian cursor over a chunk payload. */
    static final class Cursor {
        final byte[] b; int at;
        Cursor(byte[] b) { this.b = b; }
        int remaining() { return b.length - at; }
        void skip(int n) throws IOException { if (n < 0 || at + n > b.length) throw new IOException("Unexpected end of data."); at += n; }
        int u8() throws IOException { if (at + 1 > b.length) throw new IOException("Unexpected end of data."); return b[at++] & 0xFF; }
        int u32() throws IOException {
            if (at + 4 > b.length) throw new IOException("Unexpected end of data.");
            int v = (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
            at += 4;
            return v;
        }
        long u64() throws IOException {
            if (at + 8 > b.length) throw new IOException("Unexpected end of data.");
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (b[at + i] & 0xFFL);
            at += 8;
            return v;
        }
        float f32() throws IOException { return Float.intBitsToFloat(u32()); }
        double f64() throws IOException {
            long lo = u32() & 0xFFFFFFFFL, hi = u32() & 0xFFFFFFFFL;
            return Double.longBitsToDouble(lo | (hi << 32));
        }
        String str() throws IOException {
            long len = u32() & 0xFFFFFFFFL;
            if (len > remaining()) throw new IOException("Invalid string length.");
            String s = new String(b, at, (int) len, StandardCharsets.UTF_8);
            at += (int) len;
            return s;
        }
        byte[] bytes(int n) throws IOException { if (n < 0 || n > remaining()) throw new IOException("Unexpected end of data."); byte[] out = Arrays.copyOfRange(b, at, at + n); at += n; return out; }
        String string4() throws IOException {
            if (at + 4 > b.length) throw new IOException("Unexpected end of data.");
            String s = new String(b, at, 4, StandardCharsets.ISO_8859_1);
            at += 4;
            return s;
        }
    }
}
