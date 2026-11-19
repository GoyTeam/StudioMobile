package dev.falix.studiomobile;

import java.text.SimpleDateFormat;
import java.util.*;

final class EngineScene {
    static final class OutputEntry {
        final String time, level, message;
        OutputEntry(String time, String level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }
    }

    interface OutputListener {
        void onOutputChanged();
    }

    Instance root;
    Instance workspace;
    Instance selection;
    volatile int version = 1;
    String placeName = "Place";
    final ArrayList<String> warnings = new ArrayList<>();
    final ArrayList<String> unmodeledWarnings = new ArrayList<>();

    volatile double camYaw = Math.toRadians(35), camPitch = Math.toRadians(-30), camDist = 34;
    final double[] camTarget = {0, 4, 0};
    final double[] camEye = {0, 20, 34};

    double[] focusPoint = {0, 0, 0};
    volatile int gizmoTool = EngineGizmo.MOVE;
    final float[] viewProjection = new float[16];

    volatile double flyX, flyZ, flyUp;
    double flySpeed = 34;
    volatile double flySpeedMultiplier = 1.0;
    double gridSnap;

    volatile boolean playing;
    volatile boolean firstPerson;
    final double[] charPos = {0, 6, 0};
    final double[] charVel = {0, 0, 0};
    double charYaw;
    boolean onGround;
    double walkSpeed = 16, jumpPower = 50, gravity = 196.2;
    double charHx = 1, charHy = 2.5, charHz = 1;
    volatile AvatarProfile avatar = AvatarProfile.defaultAvatar();
    volatile boolean use3dAvatar = true;
    volatile boolean classicBaseplate = false;
    volatile double charHealth = 100, charMaxHealth = 100;
    volatile double charWalkPhase = 0, charSpeed = 0;
    volatile double idleTime = 0, landTimer = 0;
    private double coyoteTimer = 0, jumpBufferTimer = 0;
    private boolean wasOnGround = false;
    private Instance standingPart;
    private final double[] standingPartPrev = new double[3];
    private final HashMap<Instance, double[]> partVel = new HashMap<>();
    volatile String humanoidState = "Running";
    double jumpHeight = 7.2;
    boolean useJumpPower = true;
    double maxSlopeAngle = 89.0;
    Instance livePlayer, liveCharacter, liveHumanoid, liveRootPart;
    volatile double moveX, moveZ;
    volatile boolean jumpQueued;
    double renderYaw = Double.NaN;

    int cacheVersion = -1;
    volatile EngineLighting lighting = EngineLighting.from(null);
    final ArrayList<Instance> renderList = new ArrayList<>();
    final ArrayList<double[]> colliders = new ArrayList<>();
    final ArrayList<Instance> colliderOwners = new ArrayList<>();

    final ArrayDeque<String> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
    static final int UNDO_LIMIT = 40;

    final ArrayList<OutputEntry> outputLog = new ArrayList<>();
    private static final int MAX_OUTPUT_ENTRIES = 200;
    private OutputListener outputListener;
    private LuaRuntime.UiRefreshListener uiRefreshListener;
    private LuaRuntime luaRuntime;
    private String playSnapshot;
    private final HashSet<Instance> touchedPrev = new HashSet<>();
    private final HashSet<Instance> touchedNow = new HashSet<>();

    void setOutputListener(OutputListener listener) { this.outputListener = listener; }
    void setUiRefreshListener(LuaRuntime.UiRefreshListener listener) { this.uiRefreshListener = listener; }

    LuaRuntime lua() {
        if (luaRuntime == null) {
            luaRuntime = new LuaRuntime(this, this::logOutput, () -> {
                if (uiRefreshListener != null) uiRefreshListener.onUiChanged();
            });
        }
        return luaRuntime;
    }

    void logOutput(String level, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (outputLog) {
            outputLog.add(new OutputEntry(time, level, message == null ? "" : message));
            while (outputLog.size() > MAX_OUTPUT_ENTRIES) outputLog.remove(0);
        }
        if (outputListener != null) outputListener.onOutputChanged();
    }

    void clearOutput() {
        synchronized (outputLog) { outputLog.clear(); }
        if (outputListener != null) outputListener.onOutputChanged();
    }

    void runCommand(String code) {
        lua().runCommand(code);
    }

    void setRoot(Instance dataModel, String name) {
        root = dataModel;
        workspace = root.findFirst("Workspace");
        if (workspace == null) { workspace = new Instance("Workspace", "Workspace"); root.add(0, workspace); }
        placeName = name;
        for (Instance i : root.all(null)) {
            if (i.className.equals("SpawnLocation")) {
                charPos[0] = i.cframe()[0];
                charPos[1] = i.cframe()[1] + i.size()[1] / 2 + charHy + 1;
                charPos[2] = i.cframe()[2];
                break;
            }
        }
        gravity = workspace.getNumber("Gravity", 196.2);
        refreshCache();
    }

    synchronized void markDirty() { version++; }

    synchronized void refreshCache() {
        lighting = EngineLighting.from(root);
        renderList.clear();
        colliders.clear();
        colliderOwners.clear();
        if (root != null) {
            for (Instance i : root.all(null)) {
                if (i.getBool("__playChar", false)) continue;
                if (i.isRenderable()) {
                    renderList.add(i);
                    if (i.getBool("CanCollide", true) && i.getNumber("Transparency", 0) < 0.95) {
                        double[] b = i.bounds();
                        colliders.add(new double[]{b[0], b[1], b[2], Math.max(b[3], .1), Math.max(b[4], .1), Math.max(b[5], .1)});
                        colliderOwners.add(i);
                    }
                }
            }
        }
        cacheVersion = version;
    }

    synchronized Instance insert(String cls) {
        pushUndo();
        String base = cls.equals("Part") ? "Part" : cls;
        String name = base;
        int n = 1;
        while (workspace.childNamed(name) != null) name = base + " " + (++n);
        Instance inst = Instance.create(cls, name);
        if ("Script".equals(cls) || "LocalScript".equals(cls)) {
            inst.props.put("Source", "print(\"Hello from " + name + "!\")\n");
            inst.tags.put("Source", "ProtectedString");
        } else if ("ModuleScript".equals(cls)) {
            inst.props.put("Source", "local module = {}\n\nreturn module\n");
            inst.tags.put("Source", "ProtectedString");
        }
        double x = focusPoint[0], y = focusPoint[1], z = focusPoint[2];
        if (selection != null && selection.isRenderable()) {
            double[] p = selection.cframe();
            x = p[0]; y = p[1]; z = p[2];
            if (inst.props.containsKey("CFrame")) y += selection.size()[1] / 2 + inst.size()[1] / 2 + 0.1;
        } else if (inst.props.containsKey("CFrame")) y += inst.size()[1] / 2 + 0.25;
        if (inst.props.containsKey("CFrame")) inst.setPosition(x, y, z);
        Instance parent = insertParent(cls);
        parent.add(inst);
        selection = inst;
        markDirty();
        return inst;
    }

    Instance insertParent(String cls) {
        Instance target = selection;
        if (target != null && !target.isService() && !target.className.equals("Model") && !target.className.equals("Folder")
                && !target.className.equals("Terrain") && !target.className.equals("Camera")) {
            if (PART_CHILD.contains(cls) && target.isRenderable()) return target;
            if (("ScreenGui".equals(target.className) || "Frame".equals(target.className))
                    && ("Frame".equals(cls) || "TextLabel".equals(cls) || "TextButton".equals(cls) || "LocalScript".equals(cls))) {
                return target;
            }
            target = null;
        }
        if (target != null) return target;
        Instance home = serviceFor(cls);
        if (home != null) return home;
        return workspace == null ? root : workspace;
    }

    private static final HashSet<String> PART_CHILD = new HashSet<>(Arrays.asList(
            "Decal", "Texture", "SurfaceAppearance", "ParticleEmitter", "Fire", "Smoke", "Sparkles", "Trail", "Beam",
            "PointLight", "SpotLight", "SurfaceLight", "Attachment", "Sound", "Script", "LocalScript"));

    private static final HashMap<String, String> SERVICE_HOME = new HashMap<>();
    static {
        for (String cls : new String[]{"Sky", "Atmosphere", "BloomEffect", "BlurEffect", "ColorCorrectionEffect",
                "SunRaysEffect", "DepthOfFieldEffect", "ColorGradingEffect"}) SERVICE_HOME.put(cls, "Lighting");
        for (String cls : new String[]{"Clouds", "Terrain"}) SERVICE_HOME.put(cls, "Workspace");
        for (String cls : new String[]{"ScreenGui", "BillboardGui", "SurfaceGui"}) SERVICE_HOME.put(cls, "StarterGui");
        SERVICE_HOME.put("Script", "ServerScriptService");
        SERVICE_HOME.put("ModuleScript", "ReplicatedStorage");
    }

    private Instance serviceFor(String cls) {
        String name = SERVICE_HOME.get(cls);
        if (name == null || root == null) return null;
        Instance service = root.findFirst(name);
        if (service == null) {
            service = new Instance(name, name);
            root.add(service);
        }
        return service;
    }

    synchronized void deleteSelection() {
        if (selection == null || selection.className.equals("Workspace") || selection.isService()) return;
        pushUndo();
        Instance removed = selection;
        removed.remove();
        selection = null;
        markDirty();
    }

    synchronized Instance duplicateSelection() {
        if (selection == null || selection.isService() || !selection.isRenderable() && selection.children.isEmpty()) return null;
        pushUndo();
        Instance copy = deepCopy(selection, selection.className.equals("Model") ? selection.name + " copy" : selection.name);
        Instance parent = selection.parent == null ? workspace : selection.parent;
        parent.add(copy);
        selection = copy;
        markDirty();
        return copy;
    }

    static Instance deepCopy(Instance source, String newName) {
        Instance copy = new Instance(source.className, newName);
        copy.props.putAll(source.props);
        copy.tags.putAll(source.tags);
        if (source.props.containsKey("CFrame")) {
            double[] c = source.cframe();
            copy.setCframe(c.clone());
        }
        for (Instance child : source.children) copy.add(deepCopy(child, child.name));
        return copy;
    }

    synchronized void nudge(int axis, double amount) {
        if (selection == null || selection.isService()) return;
        pushUndo();
        double[] c = selection.cframe();
        c[axis] += amount;
        selection.setCframe(c);
        markDirty();
    }

    synchronized void rotateSelection(int quarters) {
        if (selection == null || !selection.isRenderable()) return;
        pushUndo();
        selection.rotateQuarter(quarters);
        markDirty();
    }

    synchronized void scaleSelection(int axis, double factor) {
        if (selection == null || !selection.isRenderable()) return;
        pushUndo();
        double[] s = selection.size();
        s[axis] = Math.max(0.05, Math.min(4096, s[axis] * factor));
        selection.props.put("size", new Instance.Vector3(s[0], s[1], s[2]));
        markDirty();
    }

    synchronized void setProperty(Instance target, String key, Object value) {
        if (target == null) return;
        pushUndo();
        if ("Name".equals(key)) {
            String name = String.valueOf(value).trim();
            if (name.isEmpty()) name = target.className;
            if (target.parent != null) {
                Instance clash = target.parent.childNamed(name);
                if (clash != null && clash != target) name = name + " (2)";
            }
            target.name = name;
        } else {
            target.props.put(key.intern(), value);
            if (target == liveHumanoid && value instanceof Number) {
                double nv = ((Number) value).doubleValue();
                if ("WalkSpeed".equals(key)) walkSpeed = Math.max(0, nv);
                else if ("JumpPower".equals(key)) jumpPower = Math.max(0, nv);
                else if ("Health".equals(key)) { charHealth = nv; if (nv <= 0) respawnCharacter(); }
                else if ("MaxHealth".equals(key)) charMaxHealth = Math.max(1, nv);
            }
        }
        markDirty();
    }

    synchronized void pushUndo() {
        if (root == null) return;
        if (count(root) > 4000) { undoStack.clear(); redoStack.clear(); return; }
        undoStack.push(capture());
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        redoStack.clear();
    }

    static int count(Instance node) { return count(node, 0); }
    static int count(Instance node, int depth) {
        if (depth >= Instance.MAX_DEPTH) return 0;
        int n = 1;
        for (Instance child : node.children) n += count(child, depth + 1);
        return n;
    }

    synchronized String capture() {
        return PlaceWriter.generate(root);
    }

    synchronized void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(capture());
        restore(undoStack.pop());
    }
    synchronized void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(capture());
        restore(redoStack.pop());
    }
    synchronized boolean canUndo() { return !undoStack.isEmpty(); }
    synchronized boolean canRedo() { return !redoStack.isEmpty(); }

    synchronized void restore(String xml) {
        try {
            Instance fresh = parseFragment(xml);
            root = fresh;
            workspace = fresh.findFirst("Workspace");
            selection = null;
            markDirty();
        } catch (Exception ignored) { }
    }

    static Instance parseFragment(String xml) throws Exception {
        java.io.File tmp = java.io.File.createTempFile("undo-", ".rbxlx");
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(xml.getBytes("UTF-8")); }
            PlaceDecoder.Result result = PlaceDecoder.decode(tmp);
            result.root.props.clear();
            return result.root;
        } finally { tmp.delete(); }
    }

    Instance pick(double[] rayOrigin, double[] rayDir) {
        Instance bestInstance = null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < colliders.size(); i++) {
            double t = rayBox(rayOrigin, rayDir, colliders.get(i));
            if (t >= 0 && t < best) { best = t; bestInstance = colliderOwners.get(i); }
        }
        return bestInstance;
    }

    static double rayBox(double[] o, double[] d, double[] box) {
        double tmin = -Double.MAX_VALUE, tmax = Double.MAX_VALUE;
        double[] od = {o[0] - box[0], o[1] - box[1], o[2] - box[2]};
        double[] h = {box[3], box[4], box[5]};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-9) { if (Math.abs(od[a]) > h[a]) return -1; continue; }
            double t1 = (-h[a] - od[a]) / d[a], t2 = (h[a] - od[a]) / d[a];
            if (t1 > t2) { double s = t1; t1 = t2; t2 = s; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmin < 0 ? (tmax < 0 ? -1 : tmax) : tmin;
    }

    synchronized void startPlay() {
        if (root != null && count(root) <= 6000) {
            try { playSnapshot = capture(); } catch (Exception ignored) { playSnapshot = null; }
        } else {
            playSnapshot = null;
        }
        playing = true;
        walkSpeed = 16;
        jumpPower = 50;
        charHealth = charMaxHealth = 100;
        charWalkPhase = 0;
        charSpeed = 0;
        idleTime = 0;
        landTimer = 0;
        coyoteTimer = 0;
        jumpBufferTimer = 0;
        wasOnGround = false;
        standingPart = null;
        partVel.clear();
        charVel[0] = charVel[1] = charVel[2] = 0;
        moveX = moveZ = 0;
        onGround = false;
        touchedPrev.clear();
        touchedNow.clear();
        renderYaw = camYaw;
        grav();
        double[] spawn = spawnPoint();
        charPos[0] = spawn[0]; charPos[1] = spawn[1]; charPos[2] = spawn[2];
        charYaw = camYaw;
        camDist = firstPerson ? 1.0 : 16;
        if (camPitch > -0.15) camPitch = -Math.toRadians(18);
        spawnLivePlayerInTree();
        lua().startPlayScripts();
    }

    synchronized void stopPlay() {
        playing = false;
        if (luaRuntime != null) luaRuntime.stop();
        livePlayer = liveCharacter = liveHumanoid = liveRootPart = null;
        touchedPrev.clear();
        touchedNow.clear();
        if (playSnapshot != null) {
            String snap = playSnapshot;
            playSnapshot = null;
            restore(snap);
        }
        logOutput("info", "Playtest stopped");
    }

    synchronized void damageCharacter(double amount) {
        charHealth = Math.max(0, charHealth - amount);
        if (charHealth <= 0) respawnCharacter();
    }

    private void spawnLivePlayerInTree() {
        if (root == null) return;
        String uname = avatar != null && avatar.username != null ? avatar.username : "Player";
        Instance players = root.findFirst("Players");
        if (players == null) { players = new Instance("Players", "Players"); root.add(players); }
        livePlayer = new Instance("Player", uname);
        livePlayer.props.put("DisplayName", uname);
        livePlayer.props.put("UserId", avatar != null ? avatar.userId : 0L);
        livePlayer.props.put("Neutral", true);
        players.add(livePlayer);

        if (workspace != null) {
            liveCharacter = new Instance("Model", uname);
            liveHumanoid = new Instance("Humanoid", "Humanoid");
            liveHumanoid.props.put("WalkSpeed", walkSpeed);
            liveHumanoid.props.put("JumpPower", jumpPower);
            liveHumanoid.props.put("Health", charHealth);
            liveHumanoid.props.put("MaxHealth", charMaxHealth);
            liveHumanoid.props.put("RigType", avatar != null && !avatar.isR15 ? "R6" : "R15");
            liveCharacter.add(liveHumanoid);

            liveRootPart = makeCharNode("HumanoidRootPart", 2, 2, 1);
            liveRootPart.setPosition(charPos[0], charPos[1], charPos[2]);
            liveCharacter.add(liveRootPart);
            liveCharacter.add(makeCharNode("Head", 1.2, 1.2, 1.2));
            liveCharacter.add(makeCharNode("Torso", 2, 2, 1));
            liveCharacter.add(makeCharNode("Left Arm", 1, 2, 1));
            liveCharacter.add(makeCharNode("Right Arm", 1, 2, 1));
            liveCharacter.add(makeCharNode("Left Leg", 1, 2, 1));
            liveCharacter.add(makeCharNode("Right Leg", 1, 2, 1));
            liveCharacter.add(new Instance("BodyColors", "BodyColors"));
            workspace.add(liveCharacter);
        }
        markDirty();
    }

    private Instance makeCharNode(String name, double sx, double sy, double sz) {
        Instance p = Instance.create("Part", name);
        p.props.put("__playChar", true);
        p.props.put("size", new Instance.Vector3(sx, sy, sz));
        p.props.put("Anchored", false);
        p.props.put("CanCollide", false);
        return p;
    }

    synchronized void respawnCharacter() {
        charHealth = charMaxHealth;
        double[] spawn = spawnPoint();
        charPos[0] = spawn[0]; charPos[1] = spawn[1]; charPos[2] = spawn[2];
        charVel[0] = charVel[1] = charVel[2] = 0;
        onGround = false;
    }

    double[] spawnPoint() {
        Instance spawn = root == null ? null : root.findFirst("SpawnLocation");
        if (spawn != null && spawn.getBool("Enabled", true)) {
            double[] c = spawn.cframe();
            return new double[]{c[0], c[1] + spawn.size()[1] / 2 + charHy + 0.1, c[2]};
        }
        double y = 10;
        for (double[] box : colliders) y = Math.max(y, box[1] + box[4]);
        return new double[]{0, y + charHy + 2, 0};
    }

    void grav() {
        Object g = workspace == null ? null : workspace.props.get("Gravity");
        if (g instanceof Number) gravity = ((Number) g).doubleValue();
    }

    /** Roblox material default physical properties: {density, friction, elasticity}. */
    private static double[] materialPhysics(int materialEnum) {
        switch (materialEnum) {
            case 1536: case 1344: return new double[]{0.92, 0.04, 0.15}; // Ice / Glacier (slippery!)
            case 1088: case 1040: case 1056: return new double[]{7.80, 0.40, 0.25}; // Metal / DiamondPlate / Foil
            case 512:  case 528:  return new double[]{0.35, 0.48, 0.20}; // Wood / WoodPlanks
            case 816:  case 800:  case 848: return new double[]{2.40, 0.70, 0.10}; // Concrete / Slate / Brick
            case 1280: case 1296: return new double[]{0.70, 0.65, 0.10}; // Grass / Sand
            case 1312: return new double[]{0.70, 0.72, 0.08}; // Fabric
            case 288:  return new double[]{0.70, 0.30, 0.55}; // Neon / bouncy
            default:   return new double[]{0.70, 0.30, 0.50}; // Plastic / SmoothPlastic
        }
    }

    synchronized void step(double dt) {
        if (!playing) return;
        dt = Math.min(dt, 0.05);
        idleTime += dt;
        if (landTimer > 0) landTimer = Math.max(0, landTimer - dt);

        // Moving platform & Roblox Conveyor Velocity carry
        if (onGround && standingPart != null && standingPart.parent != null) {
            double[] pc = standingPart.cframe();
            double dx = pc[0] - standingPartPrev[0];
            double dy = pc[1] - standingPartPrev[1];
            double dz = pc[2] - standingPartPrev[2];
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1e-6 && Math.abs(dx) < 20 && Math.abs(dy) < 20 && Math.abs(dz) < 20) {
                charPos[0] += dx;
                charPos[1] += dy;
                charPos[2] += dz;
            }
            standingPartPrev[0] = pc[0];
            standingPartPrev[1] = pc[1];
            standingPartPrev[2] = pc[2];
            Object velObj = standingPart.props.get("AssemblyLinearVelocity");
            if (!(velObj instanceof Instance.Vector3)) velObj = standingPart.props.get("Velocity");
            if (velObj instanceof Instance.Vector3) {
                Instance.Vector3 cv = (Instance.Vector3) velObj;
                charPos[0] += cv.x * dt;
                charPos[2] += cv.z * dt;
            }
        }

        // Roblox Humanoid input -> world wish velocity & facing (-Z is character forward)
        double speed = walkSpeed;
        double wishX = 0, wishZ = 0;
        double len = Math.min(1, Math.hypot(moveX, moveZ));
        if (len > 0.06) {
            double cy = Math.cos(camYaw), sy = Math.sin(camYaw);
            wishX = (cy * moveX - sy * moveZ) * speed * len;
            wishZ = (-sy * moveX - cy * moveZ) * speed * len;
            // Avatar forward is local -Z; align (-sin(yaw), -cos(yaw)) with (wishX, wishZ)
            charYaw = Math.atan2(-wishX, -wishZ);
        }
        double surfaceFriction = 0.30;
        if (onGround && standingPart != null) {
            surfaceFriction = materialPhysics(standingPart.getInt("Material", 256))[1];
        }
        // Roblox Humanoid traction: crisp ground control, slippery on Ice, lower air control
        double groundAccel = surfaceFriction < 0.10 ? 3.2 : 24.0;
        double accel = onGround ? groundAccel : 7.5;
        charVel[0] += (wishX - charVel[0]) * Math.min(1.0, dt * accel);
        charVel[2] += (wishZ - charVel[2]) * Math.min(1.0, dt * accel);
        charSpeed = Math.hypot(charVel[0], charVel[2]);
        if (charSpeed > 0.25 && onGround) {
            charWalkPhase += dt * (charSpeed * 0.56);
        } else if (onGround) {
            charWalkPhase *= Math.max(0, 1.0 - dt * 10.0);
        }
        if (Double.isNaN(renderYaw)) renderYaw = charYaw;
        else {
            double diff = charYaw - renderYaw;
            while (diff > Math.PI) diff -= 2 * Math.PI;
            while (diff < -Math.PI) diff += 2 * Math.PI;
            renderYaw += diff * Math.min(1.0, dt * 16.0);
        }

        if (onGround) coyoteTimer = 0.10;
        else coyoteTimer = Math.max(0, coyoteTimer - dt);

        if (jumpQueued) {
            jumpBufferTimer = 0.12;
            jumpQueued = false;
        } else {
            jumpBufferTimer = Math.max(0, jumpBufferTimer - dt);
        }

        if (jumpBufferTimer > 0 && coyoteTimer > 0) {
            // Official Roblox jump formula: JumpPower or sqrt(2 * Gravity * JumpHeight)
            double v0 = useJumpPower ? jumpPower : Math.sqrt(Math.max(0, 2.0 * gravity * jumpHeight));
            charVel[1] = v0;
            onGround = false;
            coyoteTimer = 0;
            jumpBufferTimer = 0;
            standingPart = null;
            humanoidState = "Jumping";
        }

        // Roblox 240 Hz fixed-timestep sub-stepping (prevents tunneling on high-speed falls & jumps)
        touchedNow.clear();
        wasOnGround = onGround;
        int substeps = Math.max(1, Math.min(12, (int) Math.ceil(dt * 240.0)));
        double subDt = dt / substeps;
        for (int s = 0; s < substeps; s++) {
            charVel[1] -= gravity * subDt;
            if (charVel[1] < -350.0) charVel[1] = -350.0;
            onGround = false;
            standingPart = null;
            moveAxis(0, charVel[0] * subDt);
            moveAxis(2, charVel[2] * subDt);
            moveAxis(1, charVel[1] * subDt);
        }
        if (onGround && !wasOnGround) {
            landTimer = 0.16;
            humanoidState = "Landed";
        } else if (!onGround) {
            humanoidState = charVel[1] > 1.0 ? "Jumping" : "Freefall";
        } else {
            humanoidState = charSpeed > 0.35 ? "Running" : "Idle";
        }

        stepDynamicParts(dt);

        if (luaRuntime != null && luaRuntime.isRunning()) {
            for (Instance part : touchedNow) {
                if (!touchedPrev.contains(part)) luaRuntime.fireTouched(part);
            }
            touchedPrev.clear();
            touchedPrev.addAll(touchedNow);
            luaRuntime.stepHeartbeat(dt);
        }

        if (liveRootPart != null) liveRootPart.setPosition(charPos[0], charPos[1], charPos[2]);
        if (liveHumanoid != null) {
            liveHumanoid.props.put("Health", charHealth);
            liveHumanoid.props.put("WalkSpeed", walkSpeed);
            liveHumanoid.props.put("JumpPower", jumpPower);
            liveHumanoid.props.put("JumpHeight", jumpHeight);
            liveHumanoid.props.put("MaxSlopeAngle", maxSlopeAngle);
        }
        if (charPos[1] < -150) respawnCharacter();
    }

    private void stepDynamicParts(double dt) {
        boolean anyMoved = false;
        double[] charBox = {charPos[0], charPos[1], charPos[2], charHx + 0.15, charHy, charHz + 0.15};
        for (int i = 0; i < renderList.size(); i++) {
            Instance part = renderList.get(i);
            if (part.getBool("Anchored", true)) continue;
            if ("SpawnLocation".equals(part.className)) continue;
            double[] v = partVel.get(part);
            if (v == null) { v = new double[]{0, 0, 0}; partVel.put(part, v); }
            double[] phys = materialPhysics(part.getInt("Material", 256));
            double density = phys[0], friction = phys[1], elasticity = phys[2];
            double[] c = part.cframe();
            double[] s = part.size();
            double hx = Math.abs(s[0]) * 0.5, hy = Math.abs(s[1]) * 0.5, hz = Math.abs(s[2]) * 0.5;
            double mass = Math.max(0.5, Math.abs(s[0] * s[1] * s[2]) * density);

            // Player-to-part momentum push (mass-weighted like Roblox assemblies)
            double[] pBox = {c[0], c[1], c[2], hx, hy, hz};
            if (overlaps(charBox, pBox) && charSpeed > 0.5) {
                double push = Math.min(1.0, 12.0 / (mass + 8.0));
                v[0] += (charVel[0] - v[0]) * push * 0.45;
                v[2] += (charVel[2] - v[2]) * push * 0.45;
            }

            v[1] = Math.max(-250.0, v[1] - gravity * dt);
            double nx = c[0] + v[0] * dt;
            double ny = c[1] + v[1] * dt;
            double nz = c[2] + v[2] * dt;
            boolean grounded = false;
            double[] boxNext = {nx, ny, nz, hx, hy, hz};
            for (int j = 0; j < colliders.size(); j++) {
                if (colliderOwners.get(j) == part) continue;
                double[] other = colliders.get(j);
                if (overlaps(boxNext, other) && v[1] < 0 && c[1] >= other[1]) {
                    ny = other[1] + other[4] + hy;
                    if (Math.abs(v[1]) > 8.0 && elasticity > 0.15) {
                        v[1] = -v[1] * elasticity * 0.55;
                    } else {
                        v[1] = 0;
                        grounded = true;
                    }
                    break;
                }
            }
            if (grounded) {
                double drag = Math.max(0.0, 1.0 - friction * 14.0 * dt);
                v[0] *= drag;
                v[2] *= drag;
                if (Math.abs(v[0]) < 0.05) v[0] = 0;
                if (Math.abs(v[2]) < 0.05) v[2] = 0;
            }
            if (Math.abs(nx - c[0]) + Math.abs(ny - c[1]) + Math.abs(nz - c[2]) > 1e-4) {
                part.setPosition(nx, ny, nz);
                anyMoved = true;
            }
        }
        if (anyMoved) markDirty();
    }

    void moveAxis(int axis, double delta) {
        if (delta == 0) return;
        charPos[axis] += delta;
        double[] self = {charPos[0], charPos[1], charPos[2], charHx, charHy, charHz};
        for (int i = 0; i < colliders.size(); i++) {
            double[] box = colliders.get(i);
            if (!overlaps(self, box)) continue;
            Instance owner = i < colliderOwners.size() ? colliderOwners.get(i) : null;
            if (owner != null) touchedNow.add(owner);
            // Roblox-style automatic step-up for low ledges (<= 1.25 studs)
            if (axis != 1 && wasOnGround) {
                double stepHeight = (box[1] + box[4]) - (charPos[1] - charHy);
                if (stepHeight > 0 && stepHeight <= 1.25) {
                    charPos[1] += stepHeight + 0.02;
                    self[1] = charPos[1];
                    continue;
                }
            }
            double comp = axis == 0 ? self[3] + box[3] : axis == 1 ? self[4] + box[4] : self[5] + box[5];
            double dist = charPos[axis] - box[axis];
            if (dist > 0) charPos[axis] = box[axis] + comp;
            else charPos[axis] = box[axis] - comp;
            self[axis] = charPos[axis];
            if (axis == 1) {
                if (delta < 0) {
                    onGround = true;
                    charVel[1] = 0;
                    if (owner != null) {
                        standingPart = owner;
                        double[] pc = owner.cframe();
                        standingPartPrev[0] = pc[0];
                        standingPartPrev[1] = pc[1];
                        standingPartPrev[2] = pc[2];
                    }
                } else {
                    charVel[1] = Math.min(0, charVel[1]);
                }
            }
        }
    }

    static boolean overlaps(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < a[3] + b[3] && Math.abs(a[1] - b[1]) < a[4] + b[4] && Math.abs(a[2] - b[2]) < a[5] + b[5];
    }

    void moveCamera(double dYaw, double dPitch, double dZoom, boolean pan, double px, double py) {
        camYaw += dYaw;
        camPitch = Math.max(-Math.toRadians(88), Math.min(Math.toRadians(20), camPitch + dPitch));
        camDist = Math.max(playing ? 1.0 : 8.0, Math.min(220, camDist * (1 + dZoom)));
        if (playing) firstPerson = camDist < 2.2;
        if (pan) {
            double cy = Math.cos(camYaw), sy = Math.sin(camYaw);
            camTarget[0] -= (px * cy - py * sy) * camDist * 0.0015;
            camTarget[2] -= (px * sy + py * cy) * camDist * 0.0015;
        }
    }

    void flyEditCamera(double dt) {
        if (playing || dt <= 0) return;
        double mx = flyX, mz = flyZ, mu = flyUp;
        double magnitude = Math.sqrt(mx * mx + mz * mz + mu * mu);
        if (magnitude < 0.05) return;
        dt = Math.min(dt, 0.05);
        double[] forward = normalize(camTarget[0] - camEye[0], camTarget[1] - camEye[1], camTarget[2] - camEye[2]);
        double[] right = normalize(cross(forward, new double[]{0, 1, 0}));
        double[] up = cross(right, forward);
        double step = flySpeed * flySpeedMultiplier * Math.min(1, magnitude) * dt;
        camTarget[0] += (right[0] * mx + forward[0] * mz + up[0] * mu) * step;
        camTarget[1] += (right[1] * mx + forward[1] * mz + up[1] * mu) * step;
        camTarget[2] += (right[2] * mx + forward[2] * mz + up[2] * mu) * step;
    }

    private boolean dragging;
    private int dragTool = -1, dragAxis = -1;
    private double[] dragStartCframe, dragStartAxis, dragCenter;
    private double dragStartParameter, dragStartAngle, dragHandleLength, dragStartScale;
    private double[] dragPlaneNormal, dragPlanePoint;
    private boolean dragPushed;

    boolean isDragging() { return dragging; }
    int dragTool() { return dragTool; }
    int dragAxis() { return dragAxis; }

    synchronized boolean beginGizmoDrag(int tool, int axis, double[] rayOrigin, double[] rayDir, double handleLength) {
        if (selection == null || selection.isService() || playing) return false;
        if (tool != EngineGizmo.GRAB && !selection.isRenderable()) return false;
        if (tool == EngineGizmo.GRAB && !selection.isRenderable() && !selection.className.equals("Model") && !selection.className.equals("Folder")) return false;
        double[] c = selection.cframe();
        if (c == null) return false;
        dragStartCframe = c.clone();
        dragCenter = new double[]{c[0], c[1], c[2]};
        dragTool = tool;
        dragAxis = axis;
        dragHandleLength = Math.max(0.35, handleLength);
        dragStartAxis = axis >= 0 && axis < 3 ? EngineGizmo.axis(axis) : null;
        if (tool == EngineGizmo.MOVE || tool == EngineGizmo.SCALE) {
            if (dragStartAxis == null) return false;
            dragStartParameter = EngineGizmo.rayAxisParameter(rayOrigin, rayDir, dragCenter, dragStartAxis);
            dragStartScale = dragStartAxis[0] != 0 ? Math.abs(selection.size()[0])
                    : dragStartAxis[1] != 0 ? Math.abs(selection.size()[1]) : Math.abs(selection.size()[2]);
        } else if (tool == EngineGizmo.ROTATE) {
            if (dragStartAxis == null) return false;
            dragStartAngle = EngineGizmo.ringAngle(rayOrigin, rayDir, dragCenter, axis);
            if (Double.isNaN(dragStartAngle)) return false;
        } else {
            double[] normal = normalize(camEye[0] - c[0], camEye[1] - c[1], camEye[2] - c[2]);
            dragPlaneNormal = normal;
            dragPlanePoint = EngineGizmo.rayPlanePoint(rayOrigin, rayDir, dragCenter, normal);
            if (dragPlanePoint == null) return false;
        }
        dragging = true;
        dragPushed = false;
        return true;
    }

    synchronized void updateGizmoDrag(double[] rayOrigin, double[] rayDir) {
        if (!dragging || selection == null || dragStartCframe == null) return;
        if (!dragPushed) { pushUndo(); dragPushed = true; }
        double[] c = dragStartCframe.clone();
        if (dragTool == EngineGizmo.MOVE) {
            double parameter = EngineGizmo.rayAxisParameter(rayOrigin, rayDir, dragCenter, dragStartAxis);
            double delta = parameter - dragStartParameter;
            if (gridSnap > 0) delta = Math.round(delta / gridSnap) * gridSnap;
            c[0] += dragStartAxis[0] * delta;
            c[1] += dragStartAxis[1] * delta;
            c[2] += dragStartAxis[2] * delta;
            selection.setCframe(c);
            markDirty();
        } else if (dragTool == EngineGizmo.SCALE) {
            double parameter = EngineGizmo.rayAxisParameter(rayOrigin, rayDir, dragCenter, dragStartAxis);
            double factor = Math.max(0.05, Math.min(20, 1 + (parameter - dragStartParameter) / dragHandleLength));
            double[] size = selection.size();
            double value = Math.max(0.05, Math.min(4096, dragStartScale * factor));
            if (gridSnap > 0) value = Math.max(gridSnap, Math.round(value / gridSnap) * gridSnap);
            size[dragAxis] = value;
            selection.props.put("size", new Instance.Vector3(size[0], size[1], size[2]));
            markDirty();
        } else if (dragTool == EngineGizmo.ROTATE) {
            double angle = EngineGizmo.ringAngle(rayOrigin, rayDir, dragCenter, dragAxis);
            if (Double.isNaN(angle)) return;
            double delta = angle - dragStartAngle;
            while (delta > Math.PI) delta -= 2 * Math.PI;
            while (delta < -Math.PI) delta += 2 * Math.PI;
            if (gridSnap > 0) delta = Math.round(delta / (Math.PI / 12)) * (Math.PI / 12);
            double[] rotation = EngineGizmo.multiply(EngineGizmo.rotationMatrix(dragAxis, delta), EngineGizmo.rotationOf(dragStartCframe));
            selection.setCframe(EngineGizmo.withRotation(c, EngineGizmo.rotationToCframeLayout(rotation)));
            markDirty();
        } else {
            double[] point = EngineGizmo.rayPlanePoint(rayOrigin, rayDir, dragCenter, dragPlaneNormal);
            if (point == null) return;
            c[0] += point[0] - dragPlanePoint[0];
            c[1] += point[1] - dragPlanePoint[1];
            c[2] += point[2] - dragPlanePoint[2];
            selection.setCframe(c);
            markDirty();
        }
    }

    void endGizmoDrag() {
        dragging = false;
        dragPushed = false;
        dragTool = -1;
        dragAxis = -1;
        dragStartCframe = null;
    }

    void focus(Instance target) {
        if (target == null) return;
        double[] c = target.cframe();
        focusPoint = new double[]{c[0], c[1], c[2]};
        camTarget[0] = c[0]; camTarget[1] = c[1]; camTarget[2] = c[2];
        double radius = Math.max(target.size()[0], Math.max(target.size()[1], target.size()[2]));
        camDist = Math.max(14, radius * 3.2);
        camPitch = -Math.toRadians(25);
    }

    double[][] screenRay(double ndcX, double ndcY, double aspect, double fovDeg) {
        double[] eye = camEye;
        double[] forward = normalize(camTarget[0] - eye[0], camTarget[1] - eye[1], camTarget[2] - eye[2]);
        double[] right = normalize(cross(forward, new double[]{0, 1, 0}));
        double[] up = cross(right, forward);
        double tan = Math.tan(Math.toRadians(fovDeg) / 2);
        double[] dir = normalize(
                forward[0] + right[0] * ndcX * tan * aspect + up[0] * ndcY * tan,
                forward[1] + right[1] * ndcX * tan * aspect + up[1] * ndcY * tan,
                forward[2] + right[2] * ndcX * tan * aspect + up[2] * ndcY * tan);
        return new double[][]{eye.clone(), dir};
    }

    static double[] normalize(double[] v) { return normalize(v[0], v[1], v[2]); }

    static double[] normalize(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-9) return new double[]{0, 0, 1};
        return new double[]{x / l, y / l, z / l};
    }
    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    void updateEye() {
        double rx = camDist * Math.cos(camPitch) * Math.sin(camYaw);
        double ry = camDist * -Math.sin(camPitch);
        double rz = camDist * Math.cos(camPitch) * Math.cos(camYaw);
        if (playing) {
            if (firstPerson) {
                double hy = charPos[1] - charHy + 4.55;
                camEye[0] = charPos[0];
                camEye[1] = hy;
                camEye[2] = charPos[2];
                camTarget[0] = camEye[0] - Math.cos(camPitch) * Math.sin(camYaw) * 4.0;
                camTarget[1] = camEye[1] + Math.sin(camPitch) * 4.0;
                camTarget[2] = camEye[2] - Math.cos(camPitch) * Math.cos(camYaw) * 4.0;
            } else {
                double tx = charPos[0], ty = charPos[1] + 1.5, tz = charPos[2];
                camEye[0] = tx + rx; camEye[1] = ty + Math.max(ry, -2) + 3; camEye[2] = tz + rz;
                camTarget[0] = tx; camTarget[1] = ty; camTarget[2] = tz;
            }
        } else {
            camEye[0] = camTarget[0] + rx; camEye[1] = camTarget[1] + ry; camEye[2] = camTarget[2] + rz;
        }
    }
}
