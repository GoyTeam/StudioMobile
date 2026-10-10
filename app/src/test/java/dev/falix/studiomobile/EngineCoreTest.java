package dev.falix.studiomobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Engine core tests that run on the JVM (no device needed): LZ4 block decoding, binary cursor
 * reads, CFrame math, and XML value formatting. These guard the parts of the engine that
 * decode real Roblox place files.
 */
public class EngineCoreTest {

    @Test public void lz4LiteralOnlyBlockDecodes() throws Exception {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 7);
        // token = literal length >= 15 (0xF0), extra length bytes = 185, then raw literals
        byte[] block = new byte[2 + payload.length];
        block[0] = (byte) 0xF0;
        block[1] = (byte) (payload.length - 15);
        System.arraycopy(payload, 0, block, 2, payload.length);
        assertArrayEquals(payload, PlaceDecoder.lz4(block, payload.length));
    }

    @Test public void lz4BackReferenceBlockDecodes() throws Exception {
        // "abcd" literals then a 4-byte match with offset 4 -> "abcdabcd"
        byte[] block = {0x40, 'a', 'b', 'c', 'd', 0x04, 0x00};
        assertArrayEquals("abcdabcd".getBytes("UTF-8"), PlaceDecoder.lz4(block, 8));
    }

    @Test public void binaryCursorReadsLittleEndianValues() throws Exception {
        byte[] raw = new byte[12];
        raw[0] = 0x34; raw[1] = 0x12; raw[2] = 0x00; raw[3] = 0x00;  // u32 = 0x1234
        raw[4] = (byte) 0xFF; raw[5] = (byte) 0xFF; raw[6] = (byte) 0xFF; raw[7] = (byte) 0xFF; // u32 = -1
        raw[8] = 0x00; raw[9] = 0x00; raw[10] = (byte) 0x80; raw[11] = 0x3F; // f32 = 1.0
        PlaceDecoder.Cursor cursor = new PlaceDecoder.Cursor(raw);
        assertEquals(0x1234, cursor.u32());
        assertEquals(-1, cursor.u32());
        assertEquals(1.0f, cursor.f32(), 0.0001f);
    }

    @Test public void cframeRotationRoundTrips() {
        Instance part = Instance.create("Part", "Part");
        part.setPosition(10, 5, -3);
        double[] before = part.cframe();
        part.rotateQuarter(1);
        double[] after = part.cframe();
        assertEquals(10, after[0], 1e-9);
        assertEquals(5, after[1], 1e-9);
        assertEquals(-3, after[2], 1e-9);
        // one 90 degree turn maps +X to -Z (right-handed, column-major CFrame)
        part.rotateQuarter(-1);
        double[] back = part.cframe();
        for (int i = 0; i < 12; i++) assertEquals(before[i], back[i], 1e-9);
    }

    @Test public void vector3AndSizeAccessorsMatchSerializedNames() {
        Instance part = Instance.create("Part", "Part");
        part.props.put("size", new Instance.Vector3(4, 1, 2));
        double[] size = part.size();
        assertEquals(4, size[0], 1e-9);
        assertEquals(1, size[1], 1e-9);
        assertEquals(2, size[2], 1e-9);
        assertEquals(0x639BFF, part.getColor());
    }

    @Test public void writerEscapesXmlAndFormatsNumbers() {
        assertEquals("A &amp; B &lt;C&gt; &quot;D&quot;", PlaceWriter.esc("A & B <C> \"D\""));
        assertEquals("5", PlaceWriter.num(5.0));
        assertEquals("-2.5", PlaceWriter.num(-2.5));
        assertTrue(PlaceWriter.ref().startsWith("RBX"));
    }

    @Test public void rayBoxIntersectsAndMisses() {
        double[] box = {0, 0, 0, 2, 2, 2};
        double[] hit = EngineScene.normalize(0, 0, -1);
        double t = EngineScene.rayBox(new double[]{0, 0, 10}, hit, box);
        assertEquals(8, t, 1e-9);
        double[] miss = EngineScene.normalize(1, 0, -1);
        assertEquals(-1, EngineScene.rayBox(new double[]{0, 0, 10}, miss, box), 1e-9);
    }

    @Test public void xmlPlaceDecodesAndRoundTripsThroughWriter() throws Exception {
        File first = writeTemp("engine-test-1.rbxlx", PlaceTemplate.generate("Start place", "baseplate", 196.2, false));
        PlaceDecoder.Result decoded = PlaceDecoder.decode(first);
        assertEquals("XML", decoded.format);
        Instance workspace = decoded.root.findFirst("Workspace");
        assertNotNull("Workspace must decode", workspace);
        Instance baseplate = workspace.findFirst("Part");
        assertNotNull("Baseplate part must decode", baseplate);
        assertEquals(512, baseplate.size()[0], 1e-9);
        assertEquals(4285493103L & 0xFFFFFF, baseplate.getColor());
        assertNotNull("SpawnLocation must decode", workspace.findFirst("SpawnLocation"));
        assertEquals(196.2, workspace.getNumber("Gravity", 0), 1e-4);

        File second = writeTemp("engine-test-2.rbxlx", PlaceWriter.generate(decoded.root));
        PlaceDecoder.Result again = PlaceDecoder.decode(second);
        Instance workspaceAgain = again.root.findFirst("Workspace");
        assertNotNull(workspaceAgain);
        assertNotNull(workspaceAgain.findFirst("Part"));
        assertEquals(196.2, workspaceAgain.getNumber("Gravity", 0), 1e-4);
        assertEquals(2, workspaceAgain.all(null).size());
        first.delete();
        second.delete();
    }

    @Test public void xmlDecodePreservesUnmodeledProperties() throws Exception {
        String xml = "<roblox version=\"4\"><Item class=\"MeshPart\" referent=\"RBX1\"><Properties>"
                + "<string name=\"Name\">Rock</string>"
                + "<Vector3 name=\"size\"><X>2</X><Y>2</Y><Z>2</Z></Vector3>"
                + "<Content name=\"MeshId\"><url>rbxassetid://12345</url></Content>"
                + "<int64 name=\"AssetId\">4294967296</int64>"
                + "</Properties></Item></roblox>";
        File file = writeTemp("engine-test-3.rbxlx", xml);
        Instance part = PlaceDecoder.decode(file).root.findFirst("MeshPart");
        assertNotNull(part);
        assertEquals("Rock", part.name);
        assertEquals(2, part.size()[0], 1e-9);
        assertTrue("unmodeled property must be kept", part.props.get("MeshId") instanceof PlaceWriter.Raw);
        String out = PlaceWriter.generate(PlaceDecoder.decode(file).root);
        assertTrue(out.contains("<Content name=\"MeshId\"><url>rbxassetid://12345</url></Content>"));
        assertTrue(out.contains("4294967296"));
        assertTrue(out.contains("<Vector3 name=\"size\"><X>2</X><Y>2</Y><Z>2</Z></Vector3>"));
        file.delete();
    }

    @Test public void binaryPlaceDecodesInstancesPropertiesAndHierarchy() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("<roblox!".getBytes(StandardCharsets.ISO_8859_1));
        out.write(new byte[]{(byte) 0x89, (byte) 0xFF, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A});
        u16(out, 0);            // file version
        u32(out, 2);            // distinct class count
        u32(out, 2);            // total instance count
        out.write(new byte[8]); // reserved

        ByteArrayOutputStream inst = new ByteArrayOutputStream();
        u32(inst, 1); str(inst, "Workspace"); inst.write(0); u32(inst, 1); refArray(inst, 0);
        chunk(out, "INST", inst.toByteArray());

        inst = new ByteArrayOutputStream();
        u32(inst, 2); str(inst, "Part"); inst.write(0); u32(inst, 1); refArray(inst, 1);
        chunk(out, "INST", inst.toByteArray());

        ByteArrayOutputStream prop = new ByteArrayOutputStream();
        u32(prop, 1); str(prop, "Name"); prop.write(0x01); str(prop, "Workspace");
        chunk(out, "PROP", prop.toByteArray());

        prop = new ByteArrayOutputStream();
        u32(prop, 2); str(prop, "Name"); prop.write(0x01); str(prop, "Floor");
        chunk(out, "PROP", prop.toByteArray());

        // Vector3 is three planar big-endian float arrays (one element each here).
        prop = new ByteArrayOutputStream();
        u32(prop, 2); str(prop, "size"); prop.write(0x0E);
        f32Planes(prop, 3f); f32Planes(prop, 4f); f32Planes(prop, 5f);
        chunk(out, "PROP", prop.toByteArray());

        // Color3uint8 is three separate byte planes.
        prop = new ByteArrayOutputStream();
        u32(prop, 2); str(prop, "Color3uint8"); prop.write(0x1A);
        prop.write(0x12); prop.write(0x34); prop.write(0x56);
        chunk(out, "PROP", prop.toByteArray());

        ByteArrayOutputStream prnt = new ByteArrayOutputStream();
        prnt.write(0);
        u32(prnt, 1);
        refArray(prnt, 1);       // child
        refArray(prnt, 0);       // parent
        chunk(out, "PRNT", prnt.toByteArray());

        chunk(out, "END", new byte[0]);

        File file = File.createTempFile("engine-binary", ".rbxl");
        try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(out.toByteArray()); }
        PlaceDecoder.Result decoded = PlaceDecoder.decode(file);
        assertEquals("Binary", decoded.format);
        Instance workspace = decoded.root.findFirst("Workspace");
        assertNotNull("Workspace must decode", workspace);
        Instance part = workspace.findFirst("Part");
        assertNotNull("Part must be parented under Workspace", part);
        assertEquals("Floor", part.name);
        assertEquals(3, part.size()[0], 1e-6);
        assertEquals(4, part.size()[1], 1e-6);
        assertEquals(5, part.size()[2], 1e-6);
        assertEquals(0x123456, part.getColor());
        assertEquals(2, decoded.instances);
        file.delete();
    }

    @Test public void basicRotationIdsMatchThePublishedMatrices() {
        assertArrayEquals(new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, PlaceDecoder.basicRotation(0x02), 0);
        assertArrayEquals(new double[]{0, 1, 0, 0, 0, -1, -1, 0, 0}, PlaceDecoder.basicRotation(0x1f), 0);
        assertArrayEquals(new double[]{0, 0, -1, 0, -1, 0, -1, 0, 0}, PlaceDecoder.basicRotation(0x23), 0);
        assertTrue("undefined ids must report null", PlaceDecoder.basicRotation(0x04) == null);
    }

    @Test public void planarArraysDecodeBigEndianTransformsAndReferentDeltas() throws Exception {
        // Int32 planes, zig-zag encoded: stored 1 -> -1, stored 4 -> 2.
        int[] ints = new PlaceDecoder.Cursor(new byte[]{0, 0, 0, 0, 0, 0, 0x01, 0x04}).interleavedI32(2);
        assertArrayEquals(new int[]{-1, 2}, ints);

        // Referent planes are additionally difference-encoded: deltas +5, +2, -3 -> 5, 7, 4.
        int[] refs = new PlaceDecoder.Cursor(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0x0A, 0x04, 0x05}).referentArray(3);
        assertArrayEquals(new int[]{5, 7, 4}, refs);

        // Float32 planes hold big-endian bit patterns rotated left one bit.
        ByteArrayOutputStream f32 = new ByteArrayOutputStream();
        f32Planes(f32, 1.5f, -2f, 0f);
        float[] floats = new PlaceDecoder.Cursor(f32.toByteArray()).interleavedF32(3);
        assertEquals(1.5f, floats[0], 0f);
        assertEquals(-2f, floats[1], 0f);
        assertEquals(0f, floats[2], 0f);
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void u32(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }

    private static void str(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        u32(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** Referent/Int32 values are zig-zag transformed before being written big-endian. */
    private static void zigI32(ByteArrayOutputStream out, int value) {
        u32(out, (value << 1) ^ (value >> 31));
    }

    private static void refArray(ByteArrayOutputStream out, int... referents) {
        for (int referent : referents) zigI32(out, referent);
    }

    /** Writes "count" floats in the planar layout the binary format uses, x0 x1 .. xN, y0 y1 .. yN, ... */
    private static void f32Planes(ByteArrayOutputStream out, float... values) {
        int[] rotated = new int[values.length];
        for (int i = 0; i < values.length; i++) rotated[i] = Integer.rotateLeft(Float.floatToIntBits(values[i]), 1);
        for (int plane = 0; plane < 4; plane++)
            for (int i = 0; i < values.length; i++) out.write((rotated[i] >>> (24 - 8 * plane)) & 0xFF);
    }

    private static void chunk(ByteArrayOutputStream out, String name, byte[] payload) {
        byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 4; i++) out.write(i < bytes.length ? bytes[i] : 0);
        u32(out, 0);                 // compressed length: 0 means the payload is stored verbatim
        u32(out, payload.length);
        u32(out, 0);                 // reserved
        out.write(payload, 0, payload.length);
    }

    private static File writeTemp(String name, String content) throws Exception {
        File file = File.createTempFile(name, ".tmp");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(content.getBytes("UTF-8")); }
        return file;
    }

    @Test public void characterCollisionStopsAtGroundBox() {
        EngineScene scene = new EngineScene();
        Instance root = new Instance("DataModel", "DataModel");
        Instance workspace = new Instance("Workspace", "Workspace");
        root.add(workspace);
        Instance floor = Instance.create("Part", "Floor");
        floor.props.put("size", new Instance.Vector3(100, 10, 100));
        floor.setPosition(0, -5, 0);
        floor.props.put("Anchored", true);
        workspace.add(floor);
        scene.setRoot(root, "Test");
        scene.startPlay();
        scene.charPos[0] = 0; scene.charPos[1] = 20; scene.charPos[2] = 0;
        scene.charVel[1] = 0;
        for (int i = 0; i < 200; i++) scene.step(1 / 60.0);
        assertEquals(2.5, scene.charPos[1], 0.2);
        assertTrue("character should rest on the floor", scene.onGround);
    }
}
