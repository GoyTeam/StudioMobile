package dev.falix.studiomobile;

import java.util.*;

/**
 * Editor + play state for one open place. UI thread issues edits; the GL thread renders and
 * steps character physics. Collision and picking use world AABBs (rotated parts are tested by
 * their bounding box), which keeps mobile physics fast and predictable.
 */
final class EngineScene {
    Instance root;
    Instance workspace;
    Instance selection;
    volatile int version = 1;                 // bumped on every tree/property change
    String placeName = "Place";
    final ArrayList<String> warnings = new ArrayList<>();
    final ArrayList<String> unmodeledWarnings = new ArrayList<>();

    // ---- camera (orbit)
    volatile double camYaw = Math.toRadians(35), camPitch = Math.toRadians(-30), camDist = 34;
    final double[] camTarget = {0, 4, 0};
    final double[] camEye = {0, 20, 34};

    // ---- edit tools
    double[] focusPoint = {0, 0, 0};

    // ---- play state (fields touched by both UI and GL threads are volatile)
    volatile boolean playing;
    final double[] charPos = {0, 6, 0};
    final double[] charVel = {0, 0, 0};
    double charYaw;
    boolean onGround;
    double walkSpeed = 16, jumpPower = 50, gravity = 196.2;
    double charHx = 1, charHy = 2.5, charHz = 1;
    volatile double moveX, moveZ;               // joystick input -1..1
    volatile boolean jumpQueued;
    double renderYaw = Double.NaN;

    // ---- caches
    int cacheVersion = -1;
    /** Roblox Lighting/Sky/Atmosphere/Clouds/post-effects for this place; rebuilt with the cache. */
    volatile EngineLighting lighting = EngineLighting.from(null);
    final ArrayList<Instance> renderList = new ArrayList<>();
    final ArrayList<double[]> colliders = new ArrayList<>();   // {cx,cy,cz,hx,hy,hz}
    final ArrayList<Instance> colliderOwners = new ArrayList<>();

    // ---- undo
    final ArrayDeque<String> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
    static final int UNDO_LIMIT = 40;

    void setRoot(Instance dataModel, String name) {
        root = dataModel;
        workspace = root.findFirst("Workspace");
        if (workspace == null) { workspace = new Instance("Workspace", "Workspace"); root.add(0, workspace); }
        placeName = name;
        for (Instance i : root.all(null)) {
            if (i.className.equals("SpawnLocation")) { charPos[0] = i.cframe()[0]; charPos[1] = i.cframe()[1] + i.size()[1] / 2 + charHy + 1; charPos[2] = i.cframe()[2]; break; }
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
        for (Instance i : root.all(null)) {
            if (i.isRenderable()) {
                renderList.add(i);
                if (i.getBool("CanCollide", true) && i.getNumber("Transparency", 0) < 0.95) {
                    double[] b = i.bounds();
                    colliders.add(new double[]{b[0], b[1], b[2], Math.max(b[3], .1), Math.max(b[4], .1), Math.max(b[5], .1)});
                    colliderOwners.add(i);
                }
            }
        }
        cacheVersion = version;
    }

    // ------------------------------------------------------------------ editing
    synchronized Instance insert(String cls) {
        pushUndo();
        String base = cls.equals("Part") ? "Part" : cls;
        String name = base;
        int n = 1;
        while (workspace.childNamed(name) != null) name = base + " " + (++n);
        Instance inst = Instance.create(cls, name);
        double x = focusPoint[0], y = focusPoint[1], z = focusPoint[2];
        if (selection != null && selection.isRenderable()) {
            double[] p = selection.cframe();
            x = p[0]; y = p[1]; z = p[2];
            if (inst.props.containsKey("CFrame")) y += selection.size()[1] / 2 + inst.size()[1] / 2 + 0.1;
        } else if (inst.props.containsKey("CFrame")) y += inst.size()[1] / 2 + 0.25;
        Instance parent = (selection != null && (selection.className.equals("Model") || selection.className.equals("Folder"))) ? selection : workspace;
        parent.add(inst);
        selection = inst;
        markDirty();
        return inst;
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
        double[] c = source.cframe();
        copy.setCframe(c.clone());
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
        } else target.props.put(key.intern(), value);
        markDirty();
    }

    synchronized void pushUndo() {
        if (root == null) return;
        if (count(root) > 4000) { undoStack.clear(); redoStack.clear(); return; }   // snapshots stay affordable
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
        Instance copy = root;
        return PlaceWriter.generate(copy);
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

    /** Parses a full <roblox> document produced by capture(); reuses the XML reader on a temp file. */
    static Instance parseFragment(String xml) throws Exception {
        java.io.File tmp = java.io.File.createTempFile("undo-", ".rbxlx");
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(xml.getBytes("UTF-8")); }
            PlaceDecoder.Result result = PlaceDecoder.decode(tmp);
            result.root.props.clear();
            return result.root;
        } finally { tmp.delete(); }
    }

    // ------------------------------------------------------------------ picking
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

    // ------------------------------------------------------------------ play mode
    synchronized void startPlay() {
        playing = true;
        charVel[0] = charVel[1] = charVel[2] = 0;
        moveX = moveZ = 0;
        onGround = false;
        renderYaw = Double.NaN;
        grav();
        double[] spawn = spawnPoint();
        charPos[0] = spawn[0]; charPos[1] = spawn[1]; charPos[2] = spawn[2];
        charYaw = camYaw;
        camDist = 16;
        if (camPitch > -0.15) camPitch = -Math.toRadians(18);
    }

    synchronized void stopPlay() { playing = false; }

    double[] spawnPoint() {
        Instance spawn = root.findFirst("SpawnLocation");
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

    /** Advances character physics; called from the GL thread. */
    synchronized void step(double dt) {
        if (!playing) return;
        dt = Math.min(dt, 0.05);
        double speed = walkSpeed;
        double wishX = 0, wishZ = 0;
        double len = Math.min(1, Math.hypot(moveX, moveZ));
        if (len > 0.06) {
            double cy = Math.cos(camYaw), sy = Math.sin(camYaw);
            wishX = (cy * moveX - sy * moveZ) * speed * len;
            wishZ = (-sy * moveX - cy * moveZ) * speed * len;
            charYaw = Math.atan2(wishX, wishZ);
        }
        charVel[0] = wishX;
        charVel[2] = wishZ;
        charVel[1] -= gravity * dt;
        if (jumpQueued && onGround) { charVel[1] = jumpPower; onGround = false; }
        jumpQueued = false;
        if (charVel[1] < -300) charVel[1] = -300;

        onGround = false;
        moveAxis(0, charVel[0] * dt);
        moveAxis(2, charVel[2] * dt);
        moveAxis(1, charVel[1] * dt);
        if (charPos[1] < -150) { double[] spawn = spawnPoint(); charPos[0] = spawn[0]; charPos[1] = spawn[1]; charPos[2] = spawn[2]; charVel[0] = charVel[1] = charVel[2] = 0; }
    }

    void moveAxis(int axis, double delta) {
        if (delta == 0) return;
        charPos[axis] += delta;
        double[] self = {charPos[0], charPos[1], charPos[2], charHx, charHy, charHz};
        for (int i = 0; i < colliders.size(); i++) {
            double[] box = colliders.get(i);
            if (!overlaps(self, box)) continue;
            double comp = axis == 0 ? self[3] + box[3] : axis == 1 ? self[4] + box[4] : self[5] + box[5];
            double dist = charPos[axis] - box[axis];
            if (dist > 0) charPos[axis] = box[axis] + comp;
            else charPos[axis] = box[axis] - comp;
            self[axis] = charPos[axis];
            if (axis == 1) {
                if (delta < 0) { onGround = true; charVel[1] = 0; } else charVel[1] = Math.min(0, charVel[1]);
            }
        }
    }

    static boolean overlaps(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < a[3] + b[3] && Math.abs(a[1] - b[1]) < a[4] + b[4] && Math.abs(a[2] - b[2]) < a[5] + b[5];
    }

    void moveCamera(double dYaw, double dPitch, double dZoom, boolean pan, double px, double py) {
        camYaw += dYaw;
        camPitch = Math.max(-Math.toRadians(88), Math.min(Math.toRadians(20), camPitch + dPitch));
        camDist = Math.max(8, Math.min(220, camDist * (1 + dZoom)));
        if (pan) {
            double cy = Math.cos(camYaw), sy = Math.sin(camYaw);
            camTarget[0] -= (px * cy - py * sy) * camDist * 0.0015;
            camTarget[2] -= (px * sy + py * cy) * camDist * 0.0015;
        }
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

    /** Ray through a normalized device point (-1..1), from the current camera. Returns {origin, dir}. */
    double[][] screenRay(double ndcX, double ndcY, double aspect, double fovDeg) {
        double[] eye = camEye;
        double[] forward = normalize(camTarget[0] - eye[0], camTarget[1] - eye[1], camTarget[2] - eye[2]);
        double[] right = normalize(forward[2], 0, -forward[0]);
        double[] up = cross(right, forward);
        double tan = Math.tan(Math.toRadians(fovDeg) / 2);
        double[] dir = normalize(
                forward[0] + right[0] * ndcX * tan * aspect + up[0] * ndcY * tan,
                forward[1] + right[1] * ndcX * tan * aspect + up[1] * ndcY * tan,
                forward[2] + right[2] * ndcX * tan * aspect + up[2] * ndcY * tan);
        return new double[][]{eye.clone(), dir};
    }

    static double[] normalize(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-9) return new double[]{0, 0, 1};
        return new double[]{x / l, y / l, z / l};
    }
    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /** Updates camEye from yaw/pitch/dist; called by the renderer each frame. */
    void updateEye() {
        double rx = camDist * Math.cos(camPitch) * Math.sin(camYaw);
        double ry = camDist * -Math.sin(camPitch);
        double rz = camDist * Math.cos(camPitch) * Math.cos(camYaw);
        if (playing) {
            double tx = charPos[0], ty = charPos[1] + 1.5, tz = charPos[2];
            camEye[0] = tx + rx; camEye[1] = ty + Math.max(ry, -2) + 3; camEye[2] = tz + rz;
            camTarget[0] = tx; camTarget[1] = ty; camTarget[2] = tz;
        } else {
            camEye[0] = camTarget[0] + rx; camEye[1] = camTarget[1] + ry; camEye[2] = camTarget[2] + rz;
        }
    }
}
