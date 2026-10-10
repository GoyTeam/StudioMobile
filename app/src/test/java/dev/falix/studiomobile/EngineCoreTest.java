package dev.falix.studiomobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;

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
