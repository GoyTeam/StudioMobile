package dev.falix.studiomobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        // Medium stone grey, the BrickColor 194 Roblox Studio's baseplate template uses.
        assertEquals(0xA3A2A5, baseplate.getColor());
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

    // ------------------------------------------------------------------ template + textures

    @Test public void baseplateTemplateMatchesStudioAndTheSpawnSitsOnTop() throws Exception {
        Instance document = EngineScene.parseFragment(PlaceTemplate.generate("Start place", "baseplate", 196.2, false));
        Instance workspace = document.findFirst("Workspace");
        assertNotNull("Workspace must exist", workspace);
        Instance baseplate = workspace.findFirst("Part");
        assertNotNull("a Baseplate part must exist", baseplate);
        double[] size = baseplate.size();
        assertEquals(512, size[0], 1e-9);
        assertEquals(20, size[1], 1e-9);
        assertEquals(512, size[2], 1e-9);
        double[] c = baseplate.cframe();
        assertEquals(0, c[0], 1e-9);
        assertEquals(-10, c[1], 1e-9);
        assertEquals(0, c[2], 1e-9);
        assertEquals("the baseplate top face is Y = 0", 0, c[1] + size[1] / 2, 1e-9);
        assertEquals(0xA3A2A5, baseplate.getColor());
        assertEquals("Plastic", EngineMaterial.nameOf(baseplate.getInt("Material", 256)));

        Instance spawn = workspace.findFirst("SpawnLocation");
        assertNotNull("a SpawnLocation must exist", spawn);
        double[] spawnSize = spawn.size();
        double[] spawnCframe = spawn.cframe();
        assertEquals(6, spawnSize[0], 1e-9);
        assertEquals(1, spawnSize[1], 1e-9);
        assertEquals(6, spawnSize[2], 1e-9);
        assertEquals("the spawn pad stands on the baseplate, never inside it", 0,
                spawnCframe[1] - spawnSize[1] / 2, 1e-9);
        assertTrue("the spawn pad is above the baseplate surface", spawnCframe[1] - spawnSize[1] / 2 >= c[1] + size[1] / 2);
        for (String surface : new String[]{"TopSurface", "BottomSurface", "LeftSurface", "RightSurface", "FrontSurface", "BackSurface"})
            assertEquals(surface + " is Smooth (0)", 0, spawn.getInt(surface, 0));

        Instance lighting = document.findFirst("Lighting");
        assertNotNull("Lighting must exist", lighting);
        assertEquals(14, lighting.getNumber("ClockTime", 0), 1e-9);
        assertNotNull("Sky belongs to Lighting", lighting.findFirst("Sky"));
        assertNotNull("Atmosphere belongs to Lighting", lighting.findFirst("Atmosphere"));
        Instance terrain = document.findFirst("Terrain");
        assertNotNull("Clouds belong to Terrain", terrain == null ? null : terrain.findFirst("Clouds"));
        assertEquals(196.2, workspace.getNumber("Gravity", 0), 1e-4);
    }

    @Test public void skyTexturesAreReadInCubeMapOrder() {
        Instance root = new Instance("DataModel", "DataModel");
        Instance lighting = new Instance("Lighting", "Lighting");
        root.add(lighting);
        Instance sky = new Instance("Sky", "Sky");
        lighting.add(sky);
        sky.props.put("SkyboxRt", "rbxassetid://1");
        sky.props.put("SkyboxLf", "rbxassetid://2");
        sky.props.put("SkyboxUp", "rbxassetid://3");
        sky.props.put("SkyboxDn", "rbxassetid://4");
        sky.props.put("SkyboxFt", "rbxassetid://5");
        sky.props.put("SkyboxBk", "rbxassetid://6");
        EngineLighting light = EngineLighting.from(root);
        assertTrue("a Sky with textures must be flagged", light.skyboxTextures);
        assertArrayEquals(new String[]{"rbxassetid://1", "rbxassetid://2", "rbxassetid://3", "rbxassetid://4", "rbxassetid://5", "rbxassetid://6"},
                light.skyTextures);
    }

    /**
     * The texture-tile and asset-id helpers are pure static math. The JVM test shim uses
     * API-parasitic stubs, so the atlas builder (which owns the first Paint that the class loader
     * hits) is guarded out here and covered by the CI APK (instrumented or robolectric) where a
     * real renderer surface exists.
     */
    @Test public void textureTilesTileLikeRobloxAndRbxAssetIdsParse() throws Exception {
        // Pure static tile math, no android.graphics.Paint, no GL context.
        assertEquals("surface types tile every stud", 1f, EngineTextures.studsForName("Studs"), 0f);
        assertEquals(1f, EngineTextures.studsForName("Inlet"), 0f);
        assertEquals(1f, EngineTextures.studsForName("Universal"), 0f);
        assertEquals("materials tile every four studs", 4f, EngineTextures.studsForName("Plastic"), 0f);
        assertEquals(4f, EngineTextures.studsForName("WoodPlanks"), 0f);
        assertEquals("the spawn pad covers its whole face", 6f, EngineTextures.studsForName("SpawnPad"), 0f);
        assertEquals("SurfaceType enum order is the Roblox one", "Smooth", EngineTextures.SURFACE_TILES[0]);
        assertEquals("Studs", EngineTextures.SURFACE_TILES[3]);
        assertEquals("SteppingMotor", EngineTextures.SURFACE_TILES[8]);
    }

    @Test public void textureFacesMapToTheCubeSidesTheMeshNumbers() {
        // Roblox Enum.NormalId: Right 0, Top 1, Back 2, Left 3, Bottom 4, Front 5 -> +X,-X,+Y,-Y,+Z,-Z.
        assertEquals(0, EngineRenderer.faceOfNormalId(0));
        assertEquals(2, EngineRenderer.faceOfNormalId(1));
        assertEquals(4, EngineRenderer.faceOfNormalId(2));
        assertEquals(1, EngineRenderer.faceOfNormalId(3));
        assertEquals(3, EngineRenderer.faceOfNormalId(4));
        assertEquals(5, EngineRenderer.faceOfNormalId(5));
    }

    // ------------------------------------------------------------------ gizmo + camera

    @Test public void gizmoHandlesKeepAConstantScreenSize() {
        double near = EngineGizmo.handleLength(20, 60, 1080, 1);
        double far = EngineGizmo.handleLength(200, 60, 1080, 1);
        assertEquals("handles grow with distance so they stay the same on screen", 10, far / near, 1e-9);
        assertTrue("a closer camera gives a shorter handle", near < far);
        double[] screen = EngineGizmo.project(identityMvp(), new double[]{0, 0, 0}, 100, 100);
        assertNotNull(screen);
        assertEquals(50, screen[0], 1e-6);
        assertEquals("screen Y is flipped", 50, screen[1], 1e-6);
    }

    @Test public void raysThroughTheRightEdgePointRight() {
        EngineScene scene = new EngineScene();
        scene.camYaw = 0;
        scene.camPitch = 0;
        scene.camTarget[0] = 0; scene.camTarget[1] = 0; scene.camTarget[2] = 0;
        scene.updateEye();
        double[][] right = scene.screenRay(1, 0, 1, 60);
        assertTrue("the right edge of the screen is +X when the camera looks down -Z", right[1][0] > 0.1);
        double[][] left = scene.screenRay(-1, 0, 1, 60);
        assertTrue(left[1][0] < -0.1);
        double[][] up = scene.screenRay(0, 1, 1, 60);
        assertTrue("the top edge is +Y", up[1][1] > 0.1);
    }

    @Test public void moveHandleDragsThePartAlongItsAxis() {
        EngineScene scene = sceneWithPart();
        Instance part = scene.selection;
        double[] before = part.cframe();
        double[][] down = {{0, 5, 20}, {0, 0, -1}};
        assertTrue(scene.beginGizmoDrag(EngineGizmo.MOVE, 0, down[0], down[1], 1));
        double[][] move = {{3, 5, 20}, {0, 0, -1}};
        scene.updateGizmoDrag(move[0], move[1]);
        double[] after = part.cframe();
        assertEquals("3 studs along +X", before[0] + 3, after[0], 1e-9);
        assertEquals(before[1], after[1], 1e-9);
        assertEquals(before[2], after[2], 1e-9);
        assertTrue("a real drag is undoable", scene.canUndo());
        scene.endGizmoDrag();
        assertFalse(scene.isDragging());
    }

    @Test public void grabbingNoHandlePushesNoUndoEntry() {
        EngineScene scene = sceneWithPart();
        double[][] down = {{0, 5, 20}, {0, 0, -1}};
        assertTrue(scene.beginGizmoDrag(EngineGizmo.MOVE, 0, down[0], down[1], 1));
        scene.endGizmoDrag();
        assertFalse("touching a handle without moving must not add undo history", scene.canUndo());
    }

    @Test public void rotateRingDragTurnsThePartAroundThatAxis() {
        EngineScene scene = sceneWithPart();
        Instance part = scene.selection;
        double[][] down = {{0, 10, 10}, {0, -1, 0}};       // ray hits the Y ring at -90 degrees
        assertTrue(scene.beginGizmoDrag(EngineGizmo.ROTATE, 1, down[0], down[1], 1));
        double[][] move = {{10, 10, 0}, {0, -1, 0}};       // and at 0 degrees
        scene.updateGizmoDrag(move[0], move[1]);
        double[] c = part.cframe();
        assertEquals("a quarter turn maps the RightVector onto -Z", 0, c[3], 1e-9);
        assertEquals(0, c[4], 1e-9);
        assertEquals(-1, c[5], 1e-9);
        assertEquals(1, c[7], 1e-9);
        assertEquals(1, c[9], 1e-9);
        assertEquals("rotation keeps the position", 5, c[1], 1e-9);
    }

    @Test public void scaleHandleStretchesOnlyItsOwnAxis() {
        EngineScene scene = sceneWithPart();
        Instance part = scene.selection;
        double[] before = part.size();
        double[][] down = {{0, 5, 20}, {0, 0, -1}};
        assertTrue(scene.beginGizmoDrag(EngineGizmo.SCALE, 0, down[0], down[1], 1));
        double[][] move = {{1, 5, 20}, {0, 0, -1}};        // one handle length out: doubles the size
        scene.updateGizmoDrag(move[0], move[1]);
        double[] after = part.size();
        assertEquals(before[0] * 2, after[0], 1e-9);
        assertEquals(before[1], after[1], 1e-9);
        assertEquals(before[2], after[2], 1e-9);
    }

    @Test public void grabHandleSlidesThePartOnTheCameraFacingPlane() {
        EngineScene scene = sceneWithPart();
        Instance part = scene.selection;
        double[][] down = {{0, 5, 20}, {0, 0, -1}};
        assertTrue(scene.beginGizmoDrag(EngineGizmo.GRAB, -1, down[0], down[1], 1));
        double[][] move = {{1, 5, 20}, {0, 0, -1}};
        scene.updateGizmoDrag(move[0], move[1]);
        double[] c = part.cframe();
        assertEquals(1, c[0], 1e-6);
        assertEquals(5, c[1], 1e-6);
        assertEquals(0, c[2], 1e-6);
    }

    @Test public void gridSnappingRoundsTheDrag() {
        EngineScene scene = sceneWithPart();
        scene.gridSnap = 1;
        Instance part = scene.selection;
        double[][] down = {{0, 5, 20}, {0, 0, -1}};
        assertTrue(scene.beginGizmoDrag(EngineGizmo.MOVE, 0, down[0], down[1], 1));
        double[][] move = {{2.4, 5, 20}, {0, 0, -1}};
        scene.updateGizmoDrag(move[0], move[1]);
        assertEquals("2.4 studs snap to 2", 2, part.cframe()[0], 1e-9);
    }

    @Test public void flyCameraFollowsTheJoystickAlongTheViewDirection() {
        EngineScene scene = new EngineScene();
        scene.camYaw = 0;
        scene.camPitch = 0;
        scene.camTarget[0] = 0; scene.camTarget[1] = 4; scene.camTarget[2] = 0;
        scene.camDist = 34;
        scene.updateEye();
        scene.flyZ = 1;                                     // push the stick forward
        scene.flyEditCamera(0.05);
        assertEquals(0, scene.camTarget[0], 1e-9);
        assertEquals(4, scene.camTarget[1], 1e-9);
        assertTrue("the camera flies toward -Z when it looks down -Z", scene.camTarget[2] < -1);
        scene.updateEye();
        assertEquals("the eye keeps its orbit offset", scene.camTarget[2] + 34, scene.camEye[2], 1e-6);
        scene.flyZ = 0;
        scene.flyUp = 1;                                    // and climbs without changing the view
        scene.flyEditCamera(0.05);
        assertTrue(scene.camTarget[1] > 4);
    }

    @Test public void playModeIgnoresTheFlyInput() {
        EngineScene scene = sceneWithPart();
        scene.playing = true;
        double before = scene.camTarget[2];
        scene.flyZ = 1;
        scene.flyEditCamera(0.05);
        assertEquals(before, scene.camTarget[2], 1e-9);
        scene.playing = false;
    }

    /** A workspace with one 4x1x2 part at (0,5,0), selected, ready for a handle drag. */
    private static EngineScene sceneWithPart() {
        EngineScene scene = new EngineScene();
        Instance root = new Instance("DataModel", "DataModel");
        Instance workspace = new Instance("Workspace", "Workspace");
        root.add(workspace);
        Instance part = Instance.create("Part", "Part");
        part.props.put("size", new Instance.Vector3(4, 1, 2));
        part.setPosition(0, 5, 0);
        workspace.add(part);
        scene.setRoot(root, "Test");
        scene.selection = part;
        return scene;
    }

    private static float[] identityMvp() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
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

    // ------------------------------------------------------------------ lighting

    @Test public void sunFollowsClockTimeAndGeographicLatitude() {
        double[] sunrise = EngineLighting.sunDirection(6, 41.7333);
        assertEquals("sunrise sits on the horizon", 0, sunrise[1], 1e-6);
        assertTrue("sunrise is in the east (+X)", sunrise[0] > 0.99);

        double[] noonEquator = EngineLighting.sunDirection(12, 0);
        assertEquals("noon at the equator is the zenith", 1, noonEquator[1], 1e-6);

        double[] noon = EngineLighting.sunDirection(12, 41.7333);
        assertTrue("latitude lowers the noon sun", noon[1] < 0.8);
        assertTrue("a northern latitude leans the arc toward +Z", noon[2] > 0.5);

        double[] sunset = EngineLighting.sunDirection(18, 41.7333);
        assertEquals(0, sunset[1], 1e-6);
        assertTrue("sunset is in the west (-X)", sunset[0] < -0.99);

        double[] midnight = EngineLighting.sunDirection(0, 41.7333);
        assertTrue("the sun is below the horizon at midnight", midnight[1] < -0.5);

        for (double hour = 0; hour < 24; hour += 1.5) {
            double[] d = EngineLighting.sunDirection(hour, 52.5);
            assertEquals("sun direction stays a unit vector", 1, Math.hypot(Math.hypot(d[0], d[1]), d[2]), 1e-9);
        }
        assertTrue(EngineLighting.sunDirection(12, 41.7333)[1] > EngineLighting.sunDirection(9, 41.7333)[1]);
        assertTrue(EngineLighting.sunDirection(15, 41.7333)[1] < EngineLighting.sunDirection(12, 41.7333)[1]);
    }

    @Test public void lightingWithoutAServiceUsesRobloxDefaults() {
        EngineLighting light = EngineLighting.from(null);
        assertEquals(14, light.clockTime, 1e-9);
        assertEquals(2, light.brightness, 1e-9);
        assertEquals("default FogEnd means no visible fog", false, light.fogEnabled);
        assertEquals(0, light.fogFactor(5000), 1e-9);
        assertTrue("daylight sun intensity", light.sunGain > 0.9);
        assertTrue("outdoor ambient lights the scene", light.ambientDiffuse[0] > 0.3);
        assertTrue(light.dayFactor > 0.99);
        assertFalse("no effects means no post pass", light.needsPost());
    }

    @Test public void lightingServicePropertiesReachTheRenderer() {
        Instance root = new Instance("DataModel", "DataModel");
        Instance lighting = new Instance("Lighting", "Lighting");
        root.add(lighting);
        lighting.props.put("Ambient", new int[]{255, 0, 0});
        lighting.props.put("OutdoorAmbient", new int[]{0, 0, 0});
        lighting.props.put("ColorShift_Top", new int[]{0, 0, 255});
        lighting.props.put("ClockTime", 0d);
        lighting.props.put("Brightness", 3d);
        lighting.props.put("FogColor", new int[]{10, 20, 30});
        lighting.props.put("FogStart", 5d);
        lighting.props.put("FogEnd", 105d);
        lighting.props.put("EnvironmentDiffuseScale", 1d);
        lighting.props.put("GlobalShadows", false);
        lighting.props.put("Technology", 3);

        EngineLighting light = EngineLighting.from(root);
        assertEquals(0, light.clockTime, 1e-9);
        assertEquals("midnight follows ClockTime 0", 0, light.dayFactor, 1e-9);
        assertEquals("no sun at midnight", 0, light.sunGain, 1e-9);
        assertEquals(1, light.ambient[0], 1e-9);
        assertEquals(1, light.colorShiftTop[2], 1e-9);
        assertTrue("the red Ambient reaches the ambient term", light.ambientDiffuse[0] > 0.9);
        assertTrue("unset channels stay dark", light.ambientDiffuse[1] < 0.2);
        assertTrue(light.fogEnabled);
        assertEquals(0.5, light.fogFactor(55), 1e-6);
        assertEquals("the sky gradient is mixed in with EnvironmentDiffuseScale", true, light.ambientDiffuse[0] >= light.ambient[0] * 0.1);
        assertFalse(light.globalShadows);
        assertEquals("Future", light.technology);
        assertTrue(light.summary().contains("ClockTime"));
    }

    @Test public void skyAtmosphereCloudsAndPostEffectsAreReadFromTheDataModel() {
        Instance root = new Instance("DataModel", "DataModel");
        Instance lighting = new Instance("Lighting", "Lighting");
        root.add(lighting);
        Instance sky = new Instance("Sky", "Sky");
        sky.props.put("SunAngularSize", 30d);
        sky.props.put("StarCount", 5000d);
        lighting.add(sky);
        Instance atmosphere = new Instance("Atmosphere", "Atmosphere");
        atmosphere.props.put("Density", 0.5d);
        atmosphere.props.put("Haze", 2d);
        atmosphere.props.put("Glare", 0.3d);
        atmosphere.props.put("Color", new int[]{255, 200, 150});
        lighting.add(atmosphere);
        Instance terrain = new Instance("Terrain", "Terrain");
        root.add(terrain);
        Instance clouds = new Instance("Clouds", "Clouds");
        clouds.props.put("Cover", 0.65d);
        clouds.props.put("Density", 0.4d);
        terrain.add(clouds);
        Instance bloom = new Instance("BloomEffect", "BloomEffect");
        bloom.props.put("Intensity", 1.4d);
        bloom.props.put("Threshold", 1.5d);
        lighting.add(bloom);
        Instance blur = new Instance("BlurEffect", "BlurEffect");
        blur.props.put("Size", 12d);
        lighting.add(blur);
        Instance cc = new Instance("ColorCorrectionEffect", "ColorCorrectionEffect");
        cc.props.put("Saturation", -0.5d);
        cc.props.put("TintColor", new int[]{255, 220, 200});
        lighting.add(cc);
        lighting.props.put("ExposureCompensation", 0.75d);

        EngineLighting light = EngineLighting.from(root);
        assertTrue(light.sky);
        assertEquals(30, light.sunAngularSize, 1e-9);
        assertEquals(5000, light.starCount, 1e-9);
        assertTrue(light.atmosphere);
        assertEquals(0.5, light.atmosphereDensity, 1e-9);
        assertEquals(150 / 255.0, light.atmosphereColor[2], 1e-9);
        assertTrue("Clouds under Terrain are honoured", light.clouds);
        assertEquals(0.65, light.cloudCover, 1e-9);
        assertTrue(light.bloom);
        assertEquals(1.4, light.bloomIntensity, 1e-9);
        assertTrue(light.blur);
        assertEquals(12, light.blurSize, 1e-9);
        assertTrue(light.colorCorrection);
        assertEquals(-0.5, light.ccSaturation, 1e-9);
        assertEquals(0.75, light.exposureCompensation, 1e-9);
        assertTrue(light.needsPost());
    }

    @Test public void neutralColourCorrectionSkipsThePostPass() {
        Instance root = new Instance("DataModel", "DataModel");
        Instance lighting = new Instance("Lighting", "Lighting");
        root.add(lighting);
        lighting.props.put("ClockTime", 14d);
        lighting.add(new Instance("ColorCorrectionEffect", "ColorCorrectionEffect"));
        EngineLighting light = EngineLighting.from(root);
        assertTrue("the effect object is still reported", light.colorCorrection);
        assertFalse("an untouched ColorCorrectionEffect must not cost a framebuffer pass", light.needsPost());
        // Moving any of its properties turns the pass on, as does an added effect.
        lighting.children.get(0).props.put("Saturation", -0.4d);
        assertTrue(EngineLighting.from(root).needsPost());
        lighting.children.get(0).props.remove("Saturation");
        lighting.add(new Instance("BloomEffect", "BloomEffect"));
        assertTrue("an added BloomEffect runs the bloom pass", EngineLighting.from(root).needsPost());
        lighting.add(new Instance("BlurEffect", "BlurEffect"));
        assertTrue(EngineLighting.from(root).needsPost());
    }

    @Test public void outdoorAmbientFollowsTheSun() {
        double day = EngineLighting.from(lightingWithOutdoorAmbient(12)).ambientDiffuse[0];
        double night = EngineLighting.from(lightingWithOutdoorAmbient(0)).ambientDiffuse[0];
        assertTrue("outdoor ambient is brighter with the sun up", day > night * 2);
    }

    /** Minimal place with only OutdoorAmbient and a clock time, used by the ambient test. */
    private static Instance lightingWithOutdoorAmbient(double clockTime) {
        Instance root = new Instance("DataModel", "DataModel");
        Instance lighting = new Instance("Lighting", "Lighting");
        lighting.props.put("OutdoorAmbient", new int[]{255, 255, 255});
        lighting.props.put("ClockTime", clockTime);
        root.add(lighting);
        return root;
    }

    @Test public void lightObjectsResolveToWorldSpaceAndAreCappedAtFour() {
        Instance root = new Instance("DataModel", "DataModel");
        Instance workspace = new Instance("Workspace", "Workspace");
        root.add(workspace);
        Instance lamp = Instance.create("Part", "Lamp");
        lamp.setPosition(10, 5, 0);
        workspace.add(lamp);
        Instance point = new Instance("PointLight", "PointLight");
        point.props.put("Color", new int[]{255, 128, 0});
        point.props.put("Brightness", 2d);
        point.props.put("Range", 30d);
        lamp.add(point);

        EngineLighting light = EngineLighting.from(root);
        assertEquals(1, light.lightCount);
        assertEquals(1, light.lights.size());
        EngineLighting.Light resolved = light.lights.get(0);
        assertEquals(EngineLighting.POINT, resolved.kind);
        assertEquals(10, resolved.position[0], 1e-9);
        assertEquals(5, resolved.position[1], 1e-9);
        assertEquals(1, resolved.color[0], 1e-9);
        assertEquals(2, resolved.brightness, 1e-9);
        assertEquals(30, resolved.range, 1e-9);
        assertEquals("a point light covers the full sphere", 1, resolved.cosAngle, 1e-9);

        // A SurfaceLight emits from a face centre, pointing away from that face.
        Instance surface = new Instance("SurfaceLight", "SurfaceLight");
        surface.props.put("Face", 1);              // Top
        surface.props.put("Angle", 180d);
        lamp.add(surface);
        light = EngineLighting.from(root);
        EngineLighting.Light top = light.lights.get(1);
        assertEquals(EngineLighting.SURFACE, top.kind);
        assertEquals(5.5, top.position[1], 1e-9);
        assertEquals(1, top.direction[1], 1e-9);
        assertEquals(Math.cos(Math.toRadians(90)), top.cosAngle, 1e-9);

        // A disabled light is ignored; the 5th enabled light is reported but not shaded.
        surface.props.put("Enabled", false);
        for (int i = 0; i < 5; i++) {
            Instance extra = new Instance("PointLight", "Light" + i);
            extra.props.put("Range", 1000d);
            lamp.add(extra);
        }
        light = EngineLighting.from(root);
        assertEquals("the disabled light is skipped, so five point lights remain", 6, light.lightCount);
        assertEquals("only four lights are shaded per frame", EngineLighting.MAX_SHADED_LIGHTS, light.lights.size());
    }

    @Test public void materialsCarryShadingValues() {
        assertEquals("Neon", EngineMaterial.nameOf(288));
        assertEquals(1.0, EngineMaterial.of(288).emissive, 1e-9);
        assertEquals("Metal", EngineMaterial.nameOf(1088));
        assertEquals(0.9, EngineMaterial.of(1088).metalness, 1e-9);
        assertEquals("Glass", EngineMaterial.nameOf(1568));
        assertTrue(EngineMaterial.of(1568).reflectance > 0.5);
        assertEquals("unknown ids fall back to Plastic", "Plastic", EngineMaterial.of(123456).name);
        assertEquals(EngineMaterial.PICKER_IDS.length, EngineMaterial.pickerNames().length);
        assertTrue("SmoothPlastic is smoother than Concrete", EngineMaterial.of(272).roughness < EngineMaterial.of(816).roughness);
        assertTrue("rough materials need a small specular exponent", EngineMaterial.of(1312).shininess() < EngineMaterial.of(1568).shininess());
    }

    @Test public void fixturePlaceCarriesItsLightingIntoTheEngine() throws Exception {
        File file = writeTemp("lighting-fixture-", PlaceTemplate.generate("Place", "baseplate", 196.2, false));
        try {
            PlaceDecoder.Result result = PlaceDecoder.decode(file);
            EngineLighting light = EngineLighting.from(result.root);
            assertEquals("the template writes ClockTime 14", 14, light.clockTime, 1e-9);
            assertEquals(2, light.brightness, 1e-9);
            assertTrue("the fixture is lit by a high sun", light.sunGain > 0.3);
            assertTrue("the template has no post-processing effects", !light.needsPost());
            assertNotNull(light.summary());
        } finally { file.delete(); }
    }
}
