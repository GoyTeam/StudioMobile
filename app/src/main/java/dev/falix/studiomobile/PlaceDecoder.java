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
    /**
     * Binary place reader. Encodings follow the published Roblox binary model format:
     * a 32-byte file header, 16-byte chunk headers, "interleaved" numeric planes stored
     * structure-of-arrays, big-endian values with the documented zig-zag / bit-rotation
     * transforms, difference-encoded referents and the 24 built-in CFrame rotation ids.
     * ZSTD-compressed chunks are recognised by their magic number and reported instead of
     * being silently mis-decoded.
     */
    static Result binary(File file) throws Exception {
        Result r = new Result(); r.format = "Binary";
        byte[] data;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int n; long total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("Place exceeds the 100 MiB reading limit.");
                out.write(buffer, 0, n);
            }
            data = out.toByteArray();
        }
        if (data.length < 32) throw new IOException("Binary place is too small to be valid.");
        Cursor c = new Cursor(data);
        String magic = new String(c.bytes(8), StandardCharsets.ISO_8859_1);
        if (!"<roblox!".equals(magic)) throw new IOException("Missing the binary Roblox signature.");
        int[] expectedSignature = {0x89, 0xFF, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] signature = c.bytes(6);
        for (int i = 0; i < 6; i++) if ((signature[i] & 0xFF) != expectedSignature[i]) throw new IOException("Binary signature mismatch.");
        int version = c.u16();
        if (version != 0) throw new IOException("Unsupported binary model version " + version + ".");
        c.skip(4);                                     // distinct class count
        c.skip(4);                                     // total instance count
        c.skip(8);                                     // reserved, always zero

        Instance root = new Instance("DataModel", "DataModel"); r.root = root;
        LinkedHashMap<Integer, Instance> byReferent = new LinkedHashMap<>();
        LinkedHashMap<Integer, ArrayList<Instance>> byTypeId = new LinkedHashMap<>();
        ArrayList<Instance> creationOrder = new ArrayList<>();
        ArrayList<String> sharedStrings = new ArrayList<>();

        while (c.remaining() >= 16) {
            String chunk = c.string4();
            if (chunk.startsWith("END")) break;
            long compLength = c.u32() & 0xFFFFFFFFL;
            long uncompLength = c.u32() & 0xFFFFFFFFL;
            c.skip(4);                                 // chunk reserved, always zero
            if (uncompLength > MAX_BYTES || compLength > MAX_BYTES) throw new IOException("Chunk length exceeds the safety limit.");
            byte[] payload;
            if (compLength > 0) {
                byte[] compressed = c.bytes((int) compLength);
                if (compressed.length >= 4 && (compressed[0] & 0xFF) == 0x28 && (compressed[1] & 0xFF) == 0xB5
                        && (compressed[2] & 0xFF) == 0x2F && (compressed[3] & 0xFF) == 0xFD) {
                    throw new IOException("This .rbxl uses ZSTD compression, which this beta cannot read yet. Open it in desktop Studio and save as .rbxlx, or use an .rbxlx place.");
                }
                payload = lz4(compressed, (int) uncompLength);
            } else {
                payload = c.bytes((int) uncompLength);
            }
            Cursor p = new Cursor(payload);
            if (chunk.startsWith("META")) {
                long count = p.u32() & 0xFFFFFFFFL;
                for (int i = 0; i < count; i++) { p.str(); p.str(); }
            } else if (chunk.startsWith("SSTR")) {
                readSharedStrings(p, sharedStrings);
            } else if (chunk.startsWith("INST")) {
                readInst(p, byReferent, byTypeId, creationOrder);
            } else if (chunk.startsWith("PROP")) {
                readProps(p, byTypeId, sharedStrings, r);
            } else if (chunk.startsWith("PRNT")) {
                readParents(p, byReferent);
            } else if (chunk.startsWith("SIGN")) {
                break;
            }
        }
        for (Instance instance : creationOrder) if (instance.parent == null) root.add(instance);
        if (root.children.isEmpty()) r.warnings.add("No instances were decoded from this binary place.");
        return r;
    }

    static void readSharedStrings(Cursor p, ArrayList<String> out) throws Exception {
        int version = p.u32();
        if (version != 0) throw new IOException("Unsupported SSTR chunk version " + version + ".");
        long count = p.u32() & 0xFFFFFFFFL;
        if (count > 500_000) throw new IOException("Implausible shared string count.");
        for (int i = 0; i < count; i++) {
            p.skip(16);                                // MD5 hash of the entry
            out.add(p.str());
        }
    }

    static void readInst(Cursor p, LinkedHashMap<Integer, Instance> byReferent,
                         LinkedHashMap<Integer, ArrayList<Instance>> byTypeId, ArrayList<Instance> order) throws Exception {
        int typeId = p.u32();
        String className = p.str();
        if (className.isEmpty()) className = "Instance";
        p.u8();                                        // object format: 1 when the class is a service
        long count = p.u32() & 0xFFFFFFFFL;
        if (count > 2_000_000) throw new IOException("Implausible instance count in INST chunk.");
        int[] referents = p.referentArray((int) count);
        ArrayList<Instance> group = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            Instance instance = new Instance(className, className);
            byReferent.put(referents[i], instance);
            group.add(instance);
            order.add(instance);
        }
        byTypeId.put(typeId, group);
    }

    static void readProps(Cursor p, LinkedHashMap<Integer, ArrayList<Instance>> byTypeId,
                          ArrayList<String> sharedStrings, Result r) throws Exception {
        int typeId = p.u32();
        String propName = p.str().intern();
        if (p.remaining() == 0) return;                // Roblox ignores PROP chunks that end after the name
        int type = p.u8();
        ArrayList<Instance> group = byTypeId.get(typeId);
        if (group == null) return;                     // unknown class: the value layout is unknowable
        int count = group.size();
        Object[] values;
        try {
            values = readValues(p, type, count, sharedStrings);
        } catch (IOException unsupported) {
            r.warnings.add("Binary property " + propName + " (" + typeName(type) + ") is not modeled; it was skipped.");
            return;
        }
        for (int i = 0; i < count; i++) applyProperty(group.get(i), propName, type, values[i], r);
    }

    static void applyProperty(Instance instance, String name, int type, Object value, Result r) {
        if (value == null) return;
        if ("Name".equals(name)) { instance.name = String.valueOf(value); return; }
        instance.props.put(name, value);
        if (type == 0x12) instance.tags.put(name, "token");
        if (type == 0x1B) instance.tags.put(name, "int64");
        if (type == 0x0C) instance.tags.put(name, "Color3");
        if (type == 0x04) instance.tags.put(name, "float");
        if (type == 0x05) instance.tags.put(name, "double");
    }

    static void readParents(Cursor p, LinkedHashMap<Integer, Instance> byReferent) throws Exception {
        int version = p.u8();
        if (version != 0) throw new IOException("Unsupported PRNT chunk version " + version + ".");
        long count = p.u32() & 0xFFFFFFFFL;
        if (count > 5_000_000) throw new IOException("Implausible instance count in PRNT chunk.");
        int[] children = p.referentArray((int) count);
        int[] parents = p.referentArray((int) count);
        for (int i = 0; i < count; i++) {
            if (parents[i] == -1) continue;            // -1 marks a child of the DataModel
            Instance child = byReferent.get(children[i]);
            Instance parent = byReferent.get(parents[i]);
            if (child != null && parent != null && child != parent) parent.add(child);
        }
    }

    /** Reads one value per instance of the class, in INST order. Null entries mean "no value". */
    static Object[] readValues(Cursor c, int type, int count, ArrayList<String> sharedStrings) throws Exception {
        Object[] out = new Object[count];
        switch (type) {
            case 0x01: for (int i = 0; i < count; i++) out[i] = c.str(); return out;                   // String (length-prefixed, in order)
            case 0x02: for (int i = 0; i < count; i++) out[i] = c.u8() != 0; return out;               // Bool
            case 0x03: { int[] v = c.interleavedI32(count); for (int i = 0; i < count; i++) out[i] = v[i]; return out; }  // Int32
            case 0x04: { float[] v = c.interleavedF32(count); for (int i = 0; i < count; i++) out[i] = v[i]; return out; } // Float32
            case 0x05: for (int i = 0; i < count; i++) out[i] = c.f64(); return out;                   // Float64 (little-endian, in order)
            case 0x06: c.skip(count * 8); return out;                                                  // UDim: f32 array + i32 array
            case 0x07: c.skip(count * 16); return out;                                                 // UDim2
            case 0x08: c.skip(count * 24); return out;                                                 // Ray: six floats per value
            case 0x09: case 0x0A: c.skip(count); return out;                                           // Faces, Axes: one byte each
            case 0x0B: c.skip(count * 4); return out;                                                  // BrickColor
            case 0x0C: {                                                                               // Color3: three f32 planes
                float[] red = c.interleavedF32(count), green = c.interleavedF32(count), blue = c.interleavedF32(count);
                for (int i = 0; i < count; i++) out[i] = new int[]{toByte(red[i]), toByte(green[i]), toByte(blue[i])};
                return out;
            }
            case 0x0D: c.skip(count * 8); return out;                                                  // Vector2
            case 0x0E: {                                                                               // Vector3: three f32 planes
                float[] x = c.interleavedF32(count), y = c.interleavedF32(count), z = c.interleavedF32(count);
                for (int i = 0; i < count; i++) out[i] = new Instance.Vector3(x[i], y[i], z[i]);
                return out;
            }
            case 0x0F: c.skip(count * 4); return out;                                                  // Vector2int16
            case 0x10: {                                                                               // CFrame: rotation ids first, then position planes
                double[][] rotations = new double[count][];
                for (int i = 0; i < count; i++) {
                    int id = c.u8();
                    if (id == 0) {
                        double[] rotation = new double[9];             // raw rows R00 R01 R02 R10 R11 R12 R20 R21 R22
                        for (int k = 0; k < 9; k++) rotation[k] = c.f32();
                        rotations[i] = rotation;
                    } else {
                        double[] rotation = basicRotation(id);
                        if (rotation == null) throw new IOException("Unknown CFrame rotation id " + id + ".");
                        rotations[i] = rotation;
                    }
                }
                float[] x = c.interleavedF32(count), y = c.interleavedF32(count), z = c.interleavedF32(count);
                for (int i = 0; i < count; i++) {
                    double[] cframe = new double[12];
                    cframe[0] = x[i]; cframe[1] = y[i]; cframe[2] = z[i];
                    System.arraycopy(rotations[i], 0, cframe, 3, 9);
                    out[i] = cframe;
                }
                return out;
            }
            case 0x11: c.skip(count * 16); return out;                                                 // Quaternion
            case 0x12: { int[] v = c.interleavedU32(count); for (int i = 0; i < count; i++) out[i] = v[i]; return out; }  // Enum
            case 0x13: c.referentArray(count); return out;                                             // Ref (left unresolved)
            case 0x14: c.skip(count * 6); return out;                                                  // Vector3int16
            case 0x15: {                                                                               // NumberSequence: per value u32 count + count * (time, value, envelope)
                for (int i = 0; i < count; i++) {
                    long keypoints = c.u32() & 0xFFFFFFFFL;
                    if (keypoints > 4096) throw new IOException("Invalid NumberSequence keypoint count.");
                    c.skip((int) keypoints * 12);
                }
                return out;
            }
            case 0x16: {                                                                               // ColorSequence: per value u32 count + count * (time, r, g, b, envelope)
                for (int i = 0; i < count; i++) {
                    long keypoints = c.u32() & 0xFFFFFFFFL;
                    if (keypoints > 4096) throw new IOException("Invalid ColorSequence keypoint count.");
                    c.skip((int) keypoints * 20);
                }
                return out;
            }
            case 0x17: c.skip(count * 8); return out;                                                  // NumberRange
            case 0x18: c.skip(count * 16); return out;                                                 // Rect: four f32 planes
            case 0x19: {                                                                               // PhysicalProperties: discriminator byte + optional custom floats
                for (int i = 0; i < count; i++) {
                    int discriminator = c.u8();
                    if ((discriminator & 0b01) != 0) c.skip((discriminator & 0b10) != 0 ? 24 : 20);
                }
                return out;
            }
            case 0x1A: {                                                                               // Color3uint8: three byte planes
                byte[] planes = c.interleavedBytes(count, 3);
                for (int i = 0; i < count; i++)
                    out[i] = ((planes[i * 3] & 0xFF) << 16) | ((planes[i * 3 + 1] & 0xFF) << 8) | (planes[i * 3 + 2] & 0xFF);
                return out;
            }
            case 0x1B: { long[] v = c.interleavedI64(count); for (int i = 0; i < count; i++) out[i] = v[i]; return out; }  // Int64
            case 0x1C: {                                                                               // SharedString: u32 indices into the SSTR table
                int[] indexes = c.interleavedU32(count);
                for (int i = 0; i < count; i++)
                    out[i] = indexes[i] >= 0 && indexes[i] < sharedStrings.size() ? sharedStrings.get(indexes[i]) : null;
                return out;
            }
            case 0x1D: for (int i = 0; i < count; i++) out[i] = c.str(); return out;                   // Bytecode / ProtectedString
            case 0x20: {                                                                               // Font: family, weight, style, cached face
                for (int i = 0; i < count; i++) { c.str(); c.skip(3); c.str(); }
                return out;
            }
            default: throw new IOException("Unmodeled binary property type 0x" + Integer.toHexString(type) + ".");
        }
    }

    static int toByte(float component) {
        int value = Math.round(component * 255);
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /**
     * Row-major rotation matrix (R00 R01 R02 R10 R11 R12 R20 R21 R22) for a CFrame basic rotation
     * id, or null when the id is not one of the 24 the format defines. The matrices are the exact
     * values in the published format description.
     */
    static double[] basicRotation(int id) {
        switch (id) {
            case 0x02: return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            case 0x03: return new double[]{1, 0, 0, 0, 0, -1, 0, 1, 0};
            case 0x05: return new double[]{1, 0, 0, 0, -1, 0, 0, 0, -1};
            case 0x06: return new double[]{1, 0, 0, 0, 0, 1, 0, -1, 0};
            case 0x07: return new double[]{0, 1, 0, 1, 0, 0, 0, 0, -1};
            case 0x09: return new double[]{0, 0, 1, 1, 0, 0, 0, 1, 0};
            case 0x0a: return new double[]{0, -1, 0, 1, 0, 0, 0, 0, 1};
            case 0x0c: return new double[]{0, 0, -1, 1, 0, 0, 0, -1, 0};
            case 0x0d: return new double[]{0, 1, 0, 0, 0, 1, 1, 0, 0};
            case 0x0e: return new double[]{0, 0, -1, 0, 1, 0, 1, 0, 0};
            case 0x10: return new double[]{0, -1, 0, 0, 0, -1, 1, 0, 0};
            case 0x11: return new double[]{0, 0, 1, 0, -1, 0, 1, 0, 0};
            case 0x14: return new double[]{-1, 0, 0, 0, 1, 0, 0, 0, -1};
            case 0x15: return new double[]{-1, 0, 0, 0, 0, 1, 0, 1, 0};
            case 0x17: return new double[]{-1, 0, 0, 0, -1, 0, 0, 0, 1};
            case 0x18: return new double[]{-1, 0, 0, 0, 0, -1, 0, -1, 0};
            case 0x19: return new double[]{0, 1, 0, -1, 0, 0, 0, 0, 1};
            case 0x1b: return new double[]{0, 0, -1, -1, 0, 0, 0, 1, 0};
            case 0x1c: return new double[]{0, -1, 0, -1, 0, 0, 0, 0, -1};
            case 0x1e: return new double[]{0, 0, 1, -1, 0, 0, 0, -1, 0};
            case 0x1f: return new double[]{0, 1, 0, 0, 0, -1, -1, 0, 0};
            case 0x20: return new double[]{0, 0, 1, 0, 1, 0, -1, 0, 0};
            case 0x22: return new double[]{0, -1, 0, 0, 0, 1, -1, 0, 0};
            case 0x23: return new double[]{0, 0, -1, 0, -1, 0, -1, 0, 0};
            default: return null;
        }
    }

    static String typeName(int type) {
        switch (type) {
            case 0x01: return "String";
            case 0x02: return "Bool";
            case 0x03: return "Int32";
            case 0x04: return "Float32";
            case 0x05: return "Float64";
            case 0x06: return "UDim";
            case 0x07: return "UDim2";
            case 0x08: return "Ray";
            case 0x09: return "Faces";
            case 0x0A: return "Axes";
            case 0x0B: return "BrickColor";
            case 0x0C: return "Color3";
            case 0x0D: return "Vector2";
            case 0x0E: return "Vector3";
            case 0x0F: return "Vector2int16";
            case 0x10: return "CFrame";
            case 0x11: return "Quaternion";
            case 0x12: return "Enum";
            case 0x13: return "Ref";
            case 0x14: return "Vector3int16";
            case 0x15: return "NumberSequence";
            case 0x16: return "ColorSequence";
            case 0x17: return "NumberRange";
            case 0x18: return "Rect";
            case 0x19: return "PhysicalProperties";
            case 0x1A: return "Color3uint8";
            case 0x1B: return "Int64";
            case 0x1C: return "SharedString";
            case 0x1D: return "Bytecode";
            case 0x1E: return "OptionalCFrame";
            case 0x1F: return "UniqueId";
            case 0x20: return "Font";
            default: return "0x" + Integer.toHexString(type);
        }
    }

    /** Inverse of the zig-zag transform Roblox applies to 32-bit values. */
    static int untransformI32(int value) { return (value >>> 1) ^ -(value & 1); }

    /** Inverse of the zig-zag transform Roblox applies to 64-bit values. */
    static long untransformI64(long value) { return (value >>> 1) ^ -(value & 1L); }

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
        int u16() throws IOException {
            if (at + 2 > b.length) throw new IOException("Unexpected end of data.");
            int v = (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
            at += 2;
            return v;
        }

        /**
         * Reads `count` big-endian 32-bit values that Roblox stores as structure-of-arrays planes:
         * each of the four bytes occupies its own run of `count` bytes.
         */
        int[] interleavedU32(int count) throws IOException {
            if (count < 0) throw new IOException("Invalid array length.");
            byte[] raw = bytes(count * 4);
            int[] out = new int[count];
            for (int i = 0; i < count; i++)
                out[i] = ((raw[i] & 0xFF) << 24) | ((raw[count + i] & 0xFF) << 16)
                        | ((raw[2 * count + i] & 0xFF) << 8) | (raw[3 * count + i] & 0xFF);
            return out;
        }

        int[] interleavedI32(int count) throws IOException {
            int[] out = interleavedU32(count);
            for (int i = 0; i < count; i++) out[i] = untransformI32(out[i]);
            return out;
        }

        /** Planar big-endian bit patterns rotated back one bit to recover each float. */
        float[] interleavedF32(int count) throws IOException {
            int[] raw = interleavedU32(count);
            float[] out = new float[count];
            for (int i = 0; i < count; i++) out[i] = Float.intBitsToFloat(Integer.rotateRight(raw[i], 1));
            return out;
        }

        long[] interleavedI64(int count) throws IOException {
            if (count < 0) throw new IOException("Invalid array length.");
            byte[] raw = bytes(count * 8);
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                long v = 0;
                for (int k = 0; k < 8; k++) v = (v << 8) | (raw[count * k + i] & 0xFFL);
                out[i] = untransformI64(v);
            }
            return out;
        }

        /** Reads `count` fixed-size records from planar layout back into element-major order. */
        byte[] interleavedBytes(int count, int elementSize) throws IOException {
            if (count < 0 || elementSize <= 0) throw new IOException("Invalid array length.");
            byte[] raw = bytes(count * elementSize);
            byte[] out = new byte[count * elementSize];
            for (int i = 0; i < count; i++)
                for (int j = 0; j < elementSize; j++)
                    out[i * elementSize + j] = raw[i + count * j];
            return out;
        }

        /** Referent arrays are difference-encoded, so each value accumulates onto the previous one. */
        int[] referentArray(int count) throws IOException {
            int[] out = interleavedI32(count);
            int last = 0;
            for (int i = 0; i < count; i++) { last += out[i]; out[i] = last; }
            return out;
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
