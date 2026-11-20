package dev.falix.studiomobile;

import java.util.*;
import java.util.concurrent.*;

final class LuaRuntime {
    interface LogListener {
        void onLog(String level, String message);
    }

    interface UiRefreshListener {
        void onUiChanged();
    }

    static final class LuaError extends RuntimeException {
        LuaError(String msg) { super(msg); }
    }

    private static final class ReturnSignal extends RuntimeException {
        final Object[] values;
        ReturnSignal(Object[] values) { this.values = values; }
    }

    private static final class BreakSignal extends RuntimeException {}
    private static final class ContinueSignal extends RuntimeException {}
    private static final class StopSignal extends RuntimeException {}

    static final class Vec3 {
        final double x, y, z;
        Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        double magnitude() { return Math.sqrt(x * x + y * y + z * z); }
        Vec3 unit() {
            double m = magnitude();
            return m < 1e-9 ? new Vec3(0, 0, 0) : new Vec3(x / m, y / m, z / m);
        }
        @Override public String toString() {
            return PlaceWriter.num(x) + ", " + PlaceWriter.num(y) + ", " + PlaceWriter.num(z);
        }
    }

    static final class CF {
        final double[] m;
        CF(double[] m) { this.m = m.clone(); }
        static CF identity() { return new CF(new double[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1}); }
        static CF translate(double x, double y, double z) { return new CF(new double[]{x, y, z, 1, 0, 0, 0, 1, 0, 0, 0, 1}); }
        static CF angles(double rx, double ry, double rz) {
            double cx = Math.cos(rx), sx = Math.sin(rx);
            double cy = Math.cos(ry), sy = Math.sin(ry);
            double cz = Math.cos(rz), sz = Math.sin(rz);
            double r00 = cy * cz;
            double r01 = -cy * sz;
            double r02 = sy;
            double r10 = cx * sz + sx * sy * cz;
            double r11 = cx * cz - sx * sy * sz;
            double r12 = -sx * cy;
            double r20 = sx * sz - cx * sy * cz;
            double r21 = sx * cz + cx * sy * sz;
            double r22 = cx * cy;
            return new CF(new double[]{0, 0, 0, r00, r01, r02, r10, r11, r12, r20, r21, r22});
        }
        CF mul(CF o) {
            double[] a = this.m, b = o.m, r = new double[12];
            r[0] = a[0] + a[3] * b[0] + a[4] * b[1] + a[5] * b[2];
            r[1] = a[1] + a[6] * b[0] + a[7] * b[1] + a[8] * b[2];
            r[2] = a[2] + a[9] * b[0] + a[10] * b[1] + a[11] * b[2];
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    r[3 + row * 3 + col] = a[3 + row * 3] * b[3 + col]
                            + a[4 + row * 3] * b[6 + col]
                            + a[5 + row * 3] * b[9 + col];
                }
            }
            return new CF(r);
        }
        Vec3 position() { return new Vec3(m[0], m[1], m[2]); }
        Vec3 lookVector() { return new Vec3(-m[5], -m[8], -m[11]); }
    }

    static final class Col3 {
        final double r, g, b;
        Col3(double r, double g, double b) {
            this.r = clamp01(r);
            this.g = clamp01(g);
            this.b = clamp01(b);
        }
        int toRgbInt() {
            int ri = (int) Math.round(r * 255.0);
            int gi = (int) Math.round(g * 255.0);
            int bi = (int) Math.round(b * 255.0);
            return ((ri & 0xFF) << 16) | ((gi & 0xFF) << 8) | (bi & 0xFF);
        }
        static Col3 fromRgbInt(int c) {
            return new Col3(((c >> 16) & 0xFF) / 255.0, ((c >> 8) & 0xFF) / 255.0, (c & 0xFF) / 255.0);
        }
        static Col3 fromHSV(double h, double s, double v) {
            h = ((h % 1.0) + 1.0) % 1.0;
            int i = (int) Math.floor(h * 6.0);
            double f = h * 6.0 - i;
            double p = v * (1.0 - s);
            double q = v * (1.0 - f * s);
            double t = v * (1.0 - (1.0 - f) * s);
            switch (i % 6) {
                case 0: return new Col3(v, t, p);
                case 1: return new Col3(q, v, p);
                case 2: return new Col3(p, v, t);
                case 3: return new Col3(p, q, v);
                case 4: return new Col3(t, p, v);
                default: return new Col3(v, p, q);
            }
        }
        private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    }

    static final class UDim2 {
        final double xs, xo, ys, yo;
        UDim2(double xs, double xo, double ys, double yo) {
            this.xs = xs; this.xo = xo; this.ys = ys; this.yo = yo;
        }
    }

    interface Callable {
        Object call(Object[] args);
    }

    static final class Signal {
        final CopyOnWriteArrayList<Callable> listeners = new CopyOnWriteArrayList<>();
        LuaTable toTable(final LuaRuntime rt) {
            LuaTable t = new LuaTable();
            t.set("Connect", (Callable) args -> {
                Callable fn = asCallable(arg(args, 1, arg(args, 0, null)));
                if (fn != null) listeners.add(fn);
                LuaTable conn = new LuaTable();
                conn.set("Connected", Boolean.TRUE);
                conn.set("Disconnect", (Callable) a -> {
                    if (fn != null) listeners.remove(fn);
                    conn.set("Connected", Boolean.FALSE);
                    return null;
                });
                return conn;
            });
            t.set("Wait", (Callable) args -> {
                rt.sleepMs(50);
                return null;
            });
            return t;
        }
        void fire(LuaRuntime rt, Object... args) {
            for (Callable c : listeners) {
                if (!rt.running) return;
                rt.runAsync(() -> {
                    try { c.call(args); }
                    catch (StopSignal ignored) { }
                    catch (Throwable err) { rt.log("error", String.valueOf(err.getMessage())); }
                });
            }
        }
        void fireSync(LuaRuntime rt, Object... args) {
            for (Callable c : listeners) {
                if (!rt.running) return;
                try { c.call(args); }
                catch (StopSignal ignored) { }
                catch (Throwable err) { rt.log("error", String.valueOf(err.getMessage())); }
            }
        }
    }

    static final class LuaTable {
        final LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        Object get(Object k) { return map.get(k); }
        void set(Object k, Object v) {
            if (v == null) map.remove(k);
            else map.put(k, v);
        }
        int length() {
            int n = 0;
            while (map.containsKey((double) (n + 1))) n++;
            return n;
        }
    }

    final class InstanceProxy {
        final Instance inst;
        InstanceProxy(Instance inst) { this.inst = inst; }
        @Override public String toString() { return inst.name; }
        @Override public boolean equals(Object o) { return o instanceof InstanceProxy && ((InstanceProxy) o).inst == inst; }
        @Override public int hashCode() { return System.identityHashCode(inst); }
    }

    private final EngineScene scene;
    private final LogListener logListener;
    private final UiRefreshListener uiListener;
    private volatile boolean running;
    private ExecutorService pool;
    private final Signal heartbeat = new Signal();
    private final Signal renderStepped = new Signal();
    private final Signal playerAdded = new Signal();
    private final Signal characterAdded = new Signal();
    private final Signal playerChatted = new Signal();
    private final ConcurrentHashMap<Instance, Signal> touchedSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Instance, Signal> clickSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Instance, Signal> changedSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Instance, Object> moduleCache = new ConcurrentHashMap<>();
    private final Instance localCharacter = new Instance("Model", "LocalPlayer");
    private final Instance localHumanoid = new Instance("Humanoid", "Humanoid");
    private final Instance localRootPart = new Instance("Part", "HumanoidRootPart");
    private final Instance localPlayerInst = new Instance("Player", "LocalPlayer");

    LuaRuntime(EngineScene scene, LogListener logListener, UiRefreshListener uiListener) {
        this.scene = scene;
        this.logListener = logListener;
        this.uiListener = uiListener;
        localCharacter.add(localHumanoid);
        localCharacter.add(localRootPart);
        localPlayerInst.props.put("DisplayName", "Player");
    }

    boolean isRunning() { return running; }

    void log(String level, String message) {
        if (logListener != null) logListener.onLog(level, message);
    }

    void startPlayScripts() {
        stop();
        running = true;
        pool = Executors.newCachedThreadPool();
        moduleCache.clear();
        touchedSignals.clear();
        clickSignals.clear();
        changedSignals.clear();
        heartbeat.listeners.clear();
        renderStepped.listeners.clear();
        playerAdded.listeners.clear();
        characterAdded.listeners.clear();
        playerChatted.listeners.clear();
        if (scene.avatar != null) {
            localPlayerInst.name = scene.avatar.username;
            localPlayerInst.props.put("DisplayName", scene.avatar.username);
            localCharacter.name = scene.avatar.username;
        }
        if (scene.root == null) return;
        ArrayList<Instance> scripts = new ArrayList<>();
        for (Instance inst : scene.root.all(null)) {
            if (("Script".equals(inst.className) || "LocalScript".equals(inst.className))
                    && !inst.getBool("Disabled", false)) {
                String src = inst.getString("Source", "");
                if (!src.trim().isEmpty()) scripts.add(inst);
            }
        }
        log("info", "Playtest started — running " + scripts.size() + " script(s)");
        for (final Instance s : scripts) {
            runAsync(() -> executeScript(s, s.getString("Source", "")));
        }
        // Fire PlayerAdded & CharacterAdded after scripts register listeners (matches Roblox Playtest boot)
        runAsync(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
            if (!running) return;
            if (!playerAdded.listeners.isEmpty()) playerAdded.fire(this, new InstanceProxy(localPlayerInst));
            if (!characterAdded.listeners.isEmpty()) characterAdded.fire(this, new InstanceProxy(localCharacter));
        });
    }

    void runCommand(final String source) {
        if (source == null || source.trim().isEmpty()) return;
        boolean wasRunning = running;
        if (!wasRunning) {
            running = true;
            pool = Executors.newCachedThreadPool();
        }
        final Instance cmdScript = new Instance("Script", "CommandBar");
        log("info", "> " + source.trim());
        runAsync(() -> {
            executeScript(cmdScript, source);
        });
    }

    void stepHeartbeat(double dt) {
        if (!running) return;
        if (!heartbeat.listeners.isEmpty()) heartbeat.fireSync(this, dt);
        if (!renderStepped.listeners.isEmpty()) renderStepped.fireSync(this, dt);
    }

    void fireTouched(Instance part) {
        if (!running || part == null) return;
        Signal sig = touchedSignals.get(part);
        if (sig != null && !sig.listeners.isEmpty()) {
            sig.fire(this, new InstanceProxy(localRootPart));
        }
    }

    void fireGuiClick(Instance button) {
        if (!running || button == null) return;
        Signal sig = clickSignals.get(button);
        if (sig != null && !sig.listeners.isEmpty()) {
            sig.fire(this);
        }
    }

    void firePlayerChatted(String message) {
        if (!running || message == null) return;
        if (!playerChatted.listeners.isEmpty()) {
            playerChatted.fire(this, message);
        }
    }

    void stop() {
        running = false;
        heartbeat.listeners.clear();
        renderStepped.listeners.clear();
        touchedSignals.clear();
        clickSignals.clear();
        changedSignals.clear();
        moduleCache.clear();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    void runAsync(Runnable task) {
        ExecutorService p = pool;
        if (p == null || p.isShutdown()) return;
        try { p.execute(task); } catch (RejectedExecutionException ignored) { }
    }

    void sleepMs(long ms) {
        if (!running) throw new StopSignal();
        try {
            long end = System.currentTimeMillis() + Math.max(10L, ms);
            while (running && System.currentTimeMillis() < end) {
                Thread.sleep(Math.min(25L, Math.max(1L, end - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            throw new StopSignal();
        }
        if (!running) throw new StopSignal();
    }

    /** Preprocesses modern Roblox Luau syntax (+=, -=, *=, /=, ..=, type annotations, :: casts, export type). */
    static String preprocessLuau(String src) {
        if (src == null || src.isEmpty()) return "";
        String[] lines = src.split("\r?\n", -1);
        StringBuilder out = new StringBuilder(src.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("export type ") || (trimmed.startsWith("type ") && trimmed.contains("="))) {
                out.append("\n");
                continue;
            }
            line = line.replaceAll("(\b[A-Za-z_][A-Za-z0-9_\.\[\]]*)\s*\+=\s*([^;\n]+)", "$1 = $1 + ($2)");
            line = line.replaceAll("(\b[A-Za-z_][A-Za-z0-9_\.\[\]]*)\s*-=\s*([^;\n]+)", "$1 = $1 - ($2)");
            line = line.replaceAll("(\b[A-Za-z_][A-Za-z0-9_\.\[\]]*)\s*\*=\s*([^;\n]+)", "$1 = $1 * ($2)");
            line = line.replaceAll("(\b[A-Za-z_][A-Za-z0-9_\.\[\]]*)\s*/=\s*([^;\n]+)", "$1 = $1 / ($2)");
            line = line.replaceAll("(\b[A-Za-z_][A-Za-z0-9_\.\[\]]*)\s*\.\.=\s*([^;\n]+)", "$1 = $1 .. ($2)");
            line = line.replaceAll("(\blocal\s+[A-Za-z_][A-Za-z0-9_]*)\s*:\s*[A-Za-z_][A-Za-z0-9_\.\?]*(\s*=)", "$1$2");
            line = line.replaceAll("\s*::\s*[A-Za-z_][A-Za-z0-9_\.\?]*", "");
            out.append(line).append("\n");
        }
        return out.toString();
    }

    Object executeScript(Instance scriptInst, String source) {
        boolean prevRunning = running;
        running = true;
        try {
            List<Token> tokens = tokenize(preprocessLuau(source));
            Parser parser = new Parser(tokens);
            List<Stmt> stmts = parser.parseBlock();
            Env env = createGlobalEnv(scriptInst);
            try {
                execBlock(stmts, env, 0);
            } catch (ReturnSignal ret) {
                return ret.values.length > 0 ? ret.values[0] : null;
            }
        } catch (StopSignal ignored) {
        } catch (Throwable err) {
            String msg = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
            log("error", "[" + scriptInst.name + "] " + msg);
        } finally {
            running = prevRunning;
        }
        return null;
    }

    private Env createGlobalEnv(final Instance scriptInst) {
        Env g = new Env(null);
        g.define("script", new InstanceProxy(scriptInst));
        if (scene.root != null) g.define("game", new InstanceProxy(scene.root));
        if (scene.workspace != null) g.define("workspace", new InstanceProxy(scene.workspace));

        g.define("print", (Callable) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(luaToString(args[i]));
            }
            log("print", sb.toString());
            return null;
        });
        g.define("warn", (Callable) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(luaToString(args[i]));
            }
            log("warn", sb.toString());
            return null;
        });
        g.define("error", (Callable) args -> {
            throw new LuaError(args.length > 0 ? luaToString(args[0]) : "Script error");
        });
        g.define("assert", (Callable) args -> {
            if (args.length == 0 || !isTruthy(args[0])) {
                throw new LuaError(args.length > 1 ? luaToString(args[1]) : "assertion failed!");
            }
            return args[0];
        });
        g.define("tostring", (Callable) args -> args.length > 0 ? luaToString(args[0]) : "nil");
        g.define("tonumber", (Callable) args -> {
            if (args.length == 0 || args[0] == null) return null;
            if (args[0] instanceof Number) return ((Number) args[0]).doubleValue();
            try { return Double.parseDouble(String.valueOf(args[0]).trim()); } catch (Exception e) { return null; }
        });
        g.define("type", (Callable) args -> luaType(args.length > 0 ? args[0] : null, false));
        g.define("typeof", (Callable) args -> luaType(args.length > 0 ? args[0] : null, true));
        g.define("tick", (Callable) args -> System.currentTimeMillis() / 1000.0);
        g.define("wait", (Callable) args -> {
            double sec = args.length > 0 ? asNum(args[0], 0.03) : 0.03;
            long t0 = System.nanoTime();
            sleepMs((long) Math.max(15, sec * 1000.0));
            return (System.nanoTime() - t0) / 1e9;
        });
        g.define("pcall", (Callable) args -> {
            Callable fn = asCallable(arg(args, 0, null));
            if (fn == null) return new Object[]{Boolean.FALSE, "attempt to call a nil value"};
            Object[] sub = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new Object[0];
            try {
                Object res = fn.call(sub);
                return new Object[]{Boolean.TRUE, res};
            } catch (StopSignal s) {
                throw s;
            } catch (Throwable e) {
                return new Object[]{Boolean.FALSE, e.getMessage() == null ? e.toString() : e.getMessage()};
            }
        });
        g.define("require", (Callable) args -> {
            Object target = arg(args, 0, null);
            if (target instanceof InstanceProxy) {
                Instance mod = ((InstanceProxy) target).inst;
                if (moduleCache.containsKey(mod)) return moduleCache.get(mod);
                Object res = executeScript(mod, mod.getString("Source", ""));
                if (res != null) moduleCache.put(mod, res);
                return res;
            }
            return null;
        });
        g.define("pairs", (Callable) args -> {
            Object obj = arg(args, 0, null);
            if (!(obj instanceof LuaTable)) return new ArrayList<Map.Entry<Object, Object>>();
            return new ArrayList<>(((LuaTable) obj).map.entrySet());
        });
        g.define("ipairs", (Callable) args -> {
            Object obj = arg(args, 0, null);
            ArrayList<Map.Entry<Object, Object>> list = new ArrayList<>();
            if (obj instanceof LuaTable) {
                LuaTable t = (LuaTable) obj;
                int i = 1;
                while (t.map.containsKey((double) i)) {
                    list.add(new AbstractMap.SimpleEntry<Object, Object>((double) i, t.map.get((double) i)));
                    i++;
                }
            }
            return list;
        });

        LuaTable taskTbl = new LuaTable();
        taskTbl.set("wait", (Callable) args -> {
            double sec = args.length > 0 ? asNum(args[0], 0.016) : 0.016;
            long t0 = System.nanoTime();
            sleepMs((long) Math.max(15, sec * 1000.0));
            return (System.nanoTime() - t0) / 1e9;
        });
        taskTbl.set("spawn", (Callable) args -> {
            Callable fn = asCallable(arg(args, 0, null));
            if (fn != null) {
                final Object[] sub = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new Object[0];
                runAsync(() -> {
                    try { fn.call(sub); }
                    catch (StopSignal ignored) { }
                    catch (Throwable e) { log("error", String.valueOf(e.getMessage())); }
                });
            }
            return null;
        });
        taskTbl.set("delay", (Callable) args -> {
            final double sec = asNum(arg(args, 0, 0.0), 0.0);
            final Callable fn = asCallable(arg(args, 1, null));
            if (fn != null) {
                final Object[] sub = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new Object[0];
                runAsync(() -> {
                    try {
                        sleepMs((long) (sec * 1000.0));
                        fn.call(sub);
                    } catch (StopSignal ignored) { }
                    catch (Throwable e) { log("error", String.valueOf(e.getMessage())); }
                });
            }
            return null;
        });
        g.define("task", taskTbl);

        LuaTable osTbl = new LuaTable();
        osTbl.set("clock", (Callable) args -> System.nanoTime() / 1e9);
        osTbl.set("time", (Callable) args -> (double) (System.currentTimeMillis() / 1000L));
        g.define("os", osTbl);

        LuaTable mathTbl = new LuaTable();
        mathTbl.set("pi", Math.PI);
        mathTbl.set("huge", Double.POSITIVE_INFINITY);
        mathTbl.set("abs", (Callable) a -> Math.abs(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("sin", (Callable) a -> Math.sin(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("cos", (Callable) a -> Math.cos(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("tan", (Callable) a -> Math.tan(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("asin", (Callable) a -> Math.asin(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("acos", (Callable) a -> Math.acos(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("atan2", (Callable) a -> Math.atan2(asNum(arg(a, 0, 0), 0), asNum(arg(a, 1, 1), 1)));
        mathTbl.set("sqrt", (Callable) a -> Math.sqrt(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("floor", (Callable) a -> Math.floor(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("ceil", (Callable) a -> Math.ceil(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("round", (Callable) a -> (double) Math.round(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("rad", (Callable) a -> Math.toRadians(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("deg", (Callable) a -> Math.toDegrees(asNum(arg(a, 0, 0), 0)));
        mathTbl.set("clamp", (Callable) a -> Math.max(asNum(arg(a, 1, 0), 0), Math.min(asNum(arg(a, 2, 1), 1), asNum(arg(a, 0, 0), 0))));
        mathTbl.set("min", (Callable) a -> {
            double m = Double.POSITIVE_INFINITY;
            for (Object o : a) m = Math.min(m, asNum(o, 0));
            return m;
        });
        mathTbl.set("max", (Callable) a -> {
            double m = Double.NEGATIVE_INFINITY;
            for (Object o : a) m = Math.max(m, asNum(o, 0));
            return m;
        });
        mathTbl.set("random", (Callable) a -> {
            if (a.length == 0) return Math.random();
            if (a.length == 1) return (double) (1 + (int) Math.floor(Math.random() * asNum(a[0], 1)));
            int lo = (int) asNum(a[0], 0), hi = (int) asNum(a[1], 1);
            return (double) (lo + (int) Math.floor(Math.random() * (hi - lo + 1)));
        });
        g.define("math", mathTbl);

        LuaTable stringTbl = new LuaTable();
        stringTbl.set("len", (Callable) a -> (double) luaToString(arg(a, 0, "")).length());
        stringTbl.set("upper", (Callable) a -> luaToString(arg(a, 0, "")).toUpperCase(Locale.US));
        stringTbl.set("lower", (Callable) a -> luaToString(arg(a, 0, "")).toLowerCase(Locale.US));
        stringTbl.set("sub", (Callable) a -> {
            String s = luaToString(arg(a, 0, ""));
            int start = (int) asNum(arg(a, 1, 1), 1);
            int end = (int) asNum(arg(a, 2, s.length()), s.length());
            if (start < 0) start = s.length() + start + 1;
            if (end < 0) end = s.length() + end + 1;
            start = Math.max(1, start);
            end = Math.min(s.length(), end);
            return start > end ? "" : s.substring(start - 1, end);
        });
        stringTbl.set("format", (Callable) a -> {
            if (a.length == 0) return "";
            String fmt = luaToString(a[0]);
            Object[] rest = Arrays.copyOfRange(a, 1, a.length);
            try { return String.format(Locale.US, fmt, rest); } catch (Exception e) { return fmt; }
        });
        g.define("string", stringTbl);

        LuaTable tableTbl = new LuaTable();
        tableTbl.set("insert", (Callable) a -> {
            if (a.length >= 2 && a[0] instanceof LuaTable) {
                LuaTable t = (LuaTable) a[0];
                if (a.length == 2) t.set((double) (t.length() + 1), a[1]);
                else {
                    int idx = (int) asNum(a[1], t.length() + 1);
                    int len = t.length();
                    for (int i = len; i >= idx; i--) t.set((double) (i + 1), t.get((double) i));
                    t.set((double) idx, a[2]);
                }
            }
            return null;
        });
        tableTbl.set("remove", (Callable) a -> {
            if (a.length >= 1 && a[0] instanceof LuaTable) {
                LuaTable t = (LuaTable) a[0];
                int len = t.length();
                int idx = a.length >= 2 ? (int) asNum(a[1], len) : len;
                Object old = t.get((double) idx);
                for (int i = idx; i < len; i++) t.set((double) i, t.get((double) (i + 1)));
                t.set((double) len, null);
                return old;
            }
            return null;
        });
        g.define("table", tableTbl);

        LuaTable vec3Ctor = new LuaTable();
        vec3Ctor.set("new", (Callable) a -> new Vec3(asNum(arg(a, 0, 0), 0), asNum(arg(a, 1, 0), 0), asNum(arg(a, 2, 0), 0)));
        vec3Ctor.set("zero", new Vec3(0, 0, 0));
        vec3Ctor.set("one", new Vec3(1, 1, 1));
        g.define("Vector3", vec3Ctor);

        LuaTable cfCtor = new LuaTable();
        cfCtor.set("new", (Callable) a -> {
            if (a.length >= 3) return CF.translate(asNum(a[0], 0), asNum(a[1], 0), asNum(a[2], 0));
            if (a.length == 1 && a[0] instanceof Vec3) {
                Vec3 v = (Vec3) a[0];
                return CF.translate(v.x, v.y, v.z);
            }
            return CF.identity();
        });
        cfCtor.set("Angles", (Callable) a -> CF.angles(asNum(arg(a, 0, 0), 0), asNum(arg(a, 1, 0), 0), asNum(arg(a, 2, 0), 0)));
        cfCtor.set("identity", CF.identity());
        g.define("CFrame", cfCtor);

        LuaTable colCtor = new LuaTable();
        colCtor.set("fromRGB", (Callable) a -> new Col3(asNum(arg(a, 0, 0), 0) / 255.0, asNum(arg(a, 1, 0), 0) / 255.0, asNum(arg(a, 2, 0), 0) / 255.0));
        colCtor.set("new", (Callable) a -> new Col3(asNum(arg(a, 0, 0), 0), asNum(arg(a, 1, 0), 0), asNum(arg(a, 2, 0), 0)));
        colCtor.set("fromHSV", (Callable) a -> Col3.fromHSV(asNum(arg(a, 0, 0), 0), asNum(arg(a, 1, 1), 1), asNum(arg(a, 2, 1), 1)));
        g.define("Color3", colCtor);

        LuaTable brickCtor = new LuaTable();
        brickCtor.set("new", (Callable) a -> {
            String n = luaToString(arg(a, 0, "Medium stone grey")).toLowerCase(Locale.US);
            if (n.contains("red")) return Col3.fromRgbInt(0xE53935);
            if (n.contains("blue")) return Col3.fromRgbInt(0x1E88E5);
            if (n.contains("green")) return Col3.fromRgbInt(0x43A047);
            if (n.contains("yellow")) return Col3.fromRgbInt(0xFDD835);
            if (n.contains("white")) return Col3.fromRgbInt(0xFFFFFF);
            if (n.contains("black")) return Col3.fromRgbInt(0x1B2A34);
            return Col3.fromRgbInt(0xA3A2A5);
        });
        brickCtor.set("Random", (Callable) a -> Col3.fromHSV(Math.random(), 0.75, 0.95));
        g.define("BrickColor", brickCtor);

        LuaTable udimCtor = new LuaTable();
        udimCtor.set("new", (Callable) a -> new UDim2(asNum(arg(a, 0, 0), 0), asNum(arg(a, 1, 0), 0), asNum(arg(a, 2, 0), 0), asNum(arg(a, 3, 0), 0)));
        udimCtor.set("fromScale", (Callable) a -> new UDim2(asNum(arg(a, 0, 0), 0), 0, asNum(arg(a, 1, 0), 0), 0));
        udimCtor.set("fromOffset", (Callable) a -> new UDim2(0, asNum(arg(a, 0, 0), 0), 0, asNum(arg(a, 1, 0), 0)));
        g.define("UDim2", udimCtor);

        LuaTable tweenInfoCtor = new LuaTable();
        tweenInfoCtor.set("new", (Callable) a -> {
            LuaTable ti = new LuaTable();
            ti.set("Time", asNum(arg(a, 0, 1.0), 1.0));
            return ti;
        });
        g.define("TweenInfo", tweenInfoCtor);

        LuaTable instCtor = new LuaTable();
        instCtor.set("new", (Callable) a -> {
            String cls = luaToString(arg(a, 0, "Part"));
            Instance created = Instance.create(cls, cls);
            if (a.length > 1 && a[1] instanceof InstanceProxy) {
                ((InstanceProxy) a[1]).inst.add(created);
                scene.markDirty();
                notifyUi();
            }
            return new InstanceProxy(created);
        });
        g.define("Instance", instCtor);

        LuaTable enumTbl = new LuaTable();
        LuaTable matEnum = new LuaTable();
        for (int i = 0; i < EngineMaterial.PICKER_IDS.length; i++) {
            matEnum.set(EngineMaterial.nameOf(EngineMaterial.PICKER_IDS[i]), (double) EngineMaterial.PICKER_IDS[i]);
        }
        enumTbl.set("Material", matEnum);
        g.define("Enum", enumTbl);
        return g;
    }

    private void notifyUi() {
        if (uiListener != null) uiListener.onUiChanged();
    }

    private Object getMember(Object obj, Object key) {
        if (obj == null) throw new LuaError("attempt to index nil with '" + key + "'");
        String k = String.valueOf(key);
        if (obj instanceof LuaTable) return ((LuaTable) obj).get(key);
        if (obj instanceof Vec3) {
            Vec3 v = (Vec3) obj;
            switch (k) {
                case "X": case "x": return v.x;
                case "Y": case "y": return v.y;
                case "Z": case "z": return v.z;
                case "Magnitude": return v.magnitude();
                case "Unit": return v.unit();
                case "Lerp": return (Callable) a -> {
                    Vec3 target = (Vec3) arg(a, 1, v);
                    double alpha = asNum(arg(a, 2, 0.5), 0.5);
                    return new Vec3(v.x + (target.x - v.x) * alpha, v.y + (target.y - v.y) * alpha, v.z + (target.z - v.z) * alpha);
                };
            }
            return null;
        }
        if (obj instanceof CF) {
            CF cf = (CF) obj;
            switch (k) {
                case "Position": case "p": return cf.position();
                case "X": return cf.m[0];
                case "Y": return cf.m[1];
                case "Z": return cf.m[2];
                case "LookVector": return cf.lookVector();
            }
            return null;
        }
        if (obj instanceof Col3) {
            Col3 c = (Col3) obj;
            switch (k) {
                case "R": return c.r;
                case "G": return c.g;
                case "B": return c.b;
            }
            return null;
        }
        if (obj instanceof InstanceProxy) return getInstanceMember(((InstanceProxy) obj).inst, k);
        return null;
    }

    private Object getInstanceMember(final Instance inst, String k) {
        if (inst == localHumanoid) {
            switch (k) {
                case "WalkSpeed": return scene.walkSpeed;
                case "JumpPower": return scene.jumpPower;
                case "Health": return scene.charHealth;
                case "MaxHealth": return scene.charMaxHealth;
                case "TakeDamage": return (Callable) a -> {
                    double dmg = asNum(arg(a, 1, 0), 0);
                    scene.damageCharacter(dmg);
                    return null;
                };
            }
        }
        if (inst == localRootPart) {
            if ("Position".equals(k)) return new Vec3(scene.charPos[0], scene.charPos[1], scene.charPos[2]);
            if ("CFrame".equals(k)) return CF.translate(scene.charPos[0], scene.charPos[1], scene.charPos[2]);
        }
        if (inst == localPlayerInst) {
            if ("Character".equals(k)) return new InstanceProxy(localCharacter);
            if ("CharacterAdded".equals(k)) return characterAdded.toTable(this);
            if ("Chatted".equals(k)) return playerChatted.toTable(this);
            if ("UserId".equals(k)) return scene.avatar != null ? (double) scene.avatar.userId : 1.0;
            if ("PlayerGui".equals(k)) {
                Instance sg = scene.root == null ? null : scene.root.findFirst("StarterGui");
                return sg != null ? new InstanceProxy(sg) : null;
            }
            if ("Name".equals(k)) return localPlayerInst.name;
            if ("DisplayName".equals(k)) return localPlayerInst.getString("DisplayName", "Player");
        }
        if ("Players".equals(inst.className)) {
            if ("LocalPlayer".equals(k)) return new InstanceProxy(localPlayerInst);
            if ("PlayerAdded".equals(k)) return playerAdded.toTable(this);
            if ("GetPlayers".equals(k)) return (Callable) a -> {
                LuaTable t = new LuaTable();
                t.set(1.0, new InstanceProxy(localPlayerInst));
                return t;
            };
        }
        if ("RunService".equals(inst.className)) {
            if ("Heartbeat".equals(k)) return heartbeat.toTable(this);
            if ("RenderStepped".equals(k)) return renderStepped.toTable(this);
            if ("IsClient".equals(k) || "IsStudio".equals(k) || "IsRunning".equals(k)) return (Callable) a -> Boolean.TRUE;
        }
        if ("TweenService".equals(inst.className) && "Create".equals(k)) {
            return (Callable) args -> createTween(arg(args, 1, null), arg(args, 2, null), arg(args, 3, null));
        }

        switch (k) {
            case "Name": return inst.name;
            case "ClassName": return inst.className;
            case "Parent": return inst.parent == null ? null : new InstanceProxy(inst.parent);
            case "Position": {
                double[] c = inst.cframe();
                return new Vec3(c[0], c[1], c[2]);
            }
            case "Size": {
                double[] s = inst.size();
                return new Vec3(s[0], s[1], s[2]);
            }
            case "CFrame": return new CF(inst.cframe());
            case "Color": return Col3.fromRgbInt(inst.getColor());
            case "Transparency": return inst.getNumber("Transparency", 0.0);
            case "Reflectance": return inst.getNumber("Reflectance", 0.0);
            case "Anchored": return inst.getBool("Anchored", false);
            case "CanCollide": return inst.getBool("CanCollide", true);
            case "Material": return (double) inst.getInt("Material", 256);
            case "Source": return inst.getString("Source", "");
            case "Text": return inst.getString("Text", "");
            case "Visible": return inst.getBool("Visible", true);
            case "Enabled": return inst.getBool("Enabled", true);
            case "ClockTime": return inst.getNumber("ClockTime", 14.0);
            case "Brightness": return inst.getNumber("Brightness", 2.0);
            case "Gravity": return inst.getNumber("Gravity", 196.2);
            case "Value": return inst.props.get("Value");
            case "Touched":
                return touchedSignals.computeIfAbsent(inst, x -> new Signal()).toTable(this);
            case "MouseButton1Click":
                return clickSignals.computeIfAbsent(inst, x -> new Signal()).toTable(this);
            case "Changed":
                return changedSignals.computeIfAbsent(inst, x -> new Signal()).toTable(this);
            case "GetService":
                return (Callable) a -> {
                    String svc = luaToString(arg(a, 1, ""));
                    if ("RunService".equals(svc)) return new InstanceProxy(new Instance("RunService", "RunService"));
                    if ("TweenService".equals(svc)) return new InstanceProxy(new Instance("TweenService", "TweenService"));
                    if ("Players".equals(svc)) {
                        Instance p = scene.root.findFirst("Players");
                        if (p == null) { p = new Instance("Players", "Players"); scene.root.add(p); }
                        return new InstanceProxy(p);
                    }
                    Instance found = scene.root.findFirst(svc);
                    if (found == null) { found = new Instance(svc, svc); scene.root.add(found); }
                    return new InstanceProxy(found);
                };
            case "FindFirstChild":
                return (Callable) a -> {
                    String name = luaToString(arg(a, 1, ""));
                    boolean rec = isTruthy(arg(a, 2, Boolean.FALSE));
                    Instance f = findChild(inst, name, rec);
                    return f == null ? null : new InstanceProxy(f);
                };
            case "WaitForChild":
                return (Callable) a -> {
                    String name = luaToString(arg(a, 1, ""));
                    Instance f = inst.childNamed(name);
                    return f == null ? null : new InstanceProxy(f);
                };
            case "GetChildren":
                return (Callable) a -> {
                    LuaTable t = new LuaTable();
                    for (int i = 0; i < inst.children.size(); i++) t.set((double) (i + 1), new InstanceProxy(inst.children.get(i)));
                    return t;
                };
            case "GetDescendants":
                return (Callable) a -> {
                    LuaTable t = new LuaTable();
                    ArrayList<Instance> all = inst.all(null);
                    for (int i = 0; i < all.size(); i++) t.set((double) (i + 1), new InstanceProxy(all.get(i)));
                    return t;
                };
            case "IsA":
                return (Callable) a -> {
                    String cls = luaToString(arg(a, 1, ""));
                    if ("BasePart".equals(cls)) return inst.isRenderable();
                    return inst.className.equals(cls);
                };
            case "Clone":
                return (Callable) a -> new InstanceProxy(EngineScene.deepCopy(inst, inst.name));
            case "Destroy":
                return (Callable) a -> {
                    inst.remove();
                    scene.markDirty();
                    notifyUi();
                    return null;
                };
            default: {
                Instance child = inst.childNamed(k);
                if (child != null) return new InstanceProxy(child);
                Object raw = inst.props.get(k);
                return raw;
            }
        }
    }

    private LuaTable createTween(final Object targetObj, final Object infoObj, final Object goalsObj) {
        LuaTable tw = new LuaTable();
        tw.set("Play", (Callable) a -> {
            if (!(targetObj instanceof InstanceProxy) || !(goalsObj instanceof LuaTable)) return null;
            final Instance inst = ((InstanceProxy) targetObj).inst;
            final double duration = infoObj instanceof LuaTable ? asNum(((LuaTable) infoObj).get("Time"), 1.0) : 1.0;
            final LuaTable goals = (LuaTable) goalsObj;
            runAsync(() -> {
                int steps = Math.max(4, (int) (duration * 30.0));
                long delay = Math.max(15L, (long) (duration * 1000.0 / steps));
                double[] startPos = inst.cframe();
                double[] startSize = inst.size();
                double startTrans = inst.getNumber("Transparency", 0.0);
                Col3 startCol = Col3.fromRgbInt(inst.getColor());
                for (int i = 1; i <= steps && running; i++) {
                    double t = (double) i / steps;
                    for (Map.Entry<Object, Object> e : goals.map.entrySet()) {
                        String key = String.valueOf(e.getKey());
                        Object val = e.getValue();
                        if ("Position".equals(key) && val instanceof Vec3) {
                            Vec3 v = (Vec3) val;
                            inst.setPosition(startPos[0] + (v.x - startPos[0]) * t, startPos[1] + (v.y - startPos[1]) * t, startPos[2] + (v.z - startPos[2]) * t);
                        } else if ("Size".equals(key) && val instanceof Vec3) {
                            Vec3 v = (Vec3) val;
                            inst.props.put("size", new Instance.Vector3(startSize[0] + (v.x - startSize[0]) * t, startSize[1] + (v.y - startSize[1]) * t, startSize[2] + (v.z - startSize[2]) * t));
                        } else if ("Transparency".equals(key) && val instanceof Number) {
                            inst.props.put("Transparency", startTrans + (((Number) val).doubleValue() - startTrans) * t);
                        } else if ("Color".equals(key) && val instanceof Col3) {
                            Col3 c = (Col3) val;
                            inst.props.put("Color3uint8", new Col3(startCol.r + (c.r - startCol.r) * t, startCol.g + (c.g - startCol.g) * t, startCol.b + (c.b - startCol.b) * t).toRgbInt());
                        }
                    }
                    scene.markDirty();
                    sleepMs(delay);
                }
            });
            return null;
        });
        return tw;
    }

    private Instance findChild(Instance root, String name, boolean rec) {
        for (Instance c : root.children) {
            if (name.equals(c.name)) return c;
            if (rec) {
                Instance sub = findChild(c, name, true);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    private void setMember(Object obj, Object key, Object val) {
        if (obj == null) throw new LuaError("attempt to index nil with '" + key + "'");
        if (obj instanceof LuaTable) {
            ((LuaTable) obj).set(key, val);
            return;
        }
        if (obj instanceof InstanceProxy) {
            Instance inst = ((InstanceProxy) obj).inst;
            String k = String.valueOf(key);
            if (inst == localHumanoid) {
                if ("WalkSpeed".equals(k)) { scene.walkSpeed = asNum(val, 16); return; }
                if ("JumpPower".equals(k)) { scene.jumpPower = asNum(val, 50); scene.useJumpPower = true; return; }
                if ("JumpHeight".equals(k)) { scene.jumpHeight = asNum(val, 7.2); scene.useJumpPower = false; return; }
                if ("Jump".equals(k) && isTruthy(val)) { scene.jumpQueued = true; return; }
                if ("Health".equals(k)) {
                    double h = asNum(val, 100);
                    scene.charHealth = h;
                    if (h <= 0) scene.respawnCharacter();
                    return;
                }
            }
            if (inst == localRootPart) {
                if ("Position".equals(k) && val instanceof Vec3) {
                    Vec3 v = (Vec3) val;
                    scene.charPos[0] = v.x; scene.charPos[1] = v.y; scene.charPos[2] = v.z;
                    return;
                }
                if ("CFrame".equals(k) && val instanceof CF) {
                    CF cf = (CF) val;
                    scene.charPos[0] = cf.m[0]; scene.charPos[1] = cf.m[1]; scene.charPos[2] = cf.m[2];
                    return;
                }
            }
            switch (k) {
                case "Name": inst.name = luaToString(val); break;
                case "Parent":
                    if (val instanceof InstanceProxy) ((InstanceProxy) val).inst.add(inst);
                    else if (val == null) inst.remove();
                    break;
                case "Position":
                    if (val instanceof Vec3) { Vec3 v = (Vec3) val; inst.setPosition(v.x, v.y, v.z); }
                    break;
                case "Size":
                    if (val instanceof Vec3) { Vec3 v = (Vec3) val; inst.props.put("size", new Instance.Vector3(Math.max(0.05, v.x), Math.max(0.05, v.y), Math.max(0.05, v.z))); }
                    break;
                case "CFrame":
                    if (val instanceof CF) inst.setCframe(((CF) val).m);
                    break;
                case "Color": case "BrickColor":
                    if (val instanceof Col3) inst.props.put("Color3uint8", ((Col3) val).toRgbInt());
                    break;
                case "Transparency": inst.props.put("Transparency", asNum(val, 0)); break;
                case "Reflectance": inst.props.put("Reflectance", asNum(val, 0)); break;
                case "Anchored": inst.props.put("Anchored", isTruthy(val)); break;
                case "CanCollide": inst.props.put("CanCollide", isTruthy(val)); break;
                case "Material": inst.props.put("Material", (int) asNum(val, 256)); break;
                case "Text": inst.props.put("Text", luaToString(val)); notifyUi(); break;
                case "Visible": inst.props.put("Visible", isTruthy(val)); notifyUi(); break;
                case "Enabled": inst.props.put("Enabled", isTruthy(val)); notifyUi(); break;
                case "ClockTime": inst.props.put("ClockTime", asNum(val, 14)); break;
                case "Brightness": inst.props.put("Brightness", asNum(val, 2)); break;
                case "Gravity": inst.props.put("Gravity", asNum(val, 196.2)); scene.grav(); break;
                case "BackgroundColor3":
                case "TextColor3":
                    if (val instanceof Col3) {
                        Col3 c = (Col3) val;
                        inst.props.put(k, new Instance.Vector3(c.r, c.g, c.b));
                        notifyUi();
                    }
                    break;
                default: inst.props.put(k, val); break;
            }
            scene.markDirty();
            Signal ch = changedSignals.get(inst);
            if (ch != null) ch.fire(this, k);
        }
    }

    // ---- Environment & Evaluator ----
    private static final class Env {
        final Env parent;
        final HashMap<String, Object> vars = new HashMap<>();
        Env(Env parent) { this.parent = parent; }
        void define(String name, Object val) { vars.put(name, val); }
        Object get(String name) {
            if (vars.containsKey(name)) return vars.get(name);
            return parent == null ? null : parent.get(name);
        }
        void assign(String name, Object val) {
            Env cur = this;
            while (cur != null) {
                if (cur.vars.containsKey(name)) { cur.vars.put(name, val); return; }
                cur = cur.parent;
            }
            Env root = this;
            while (root.parent != null) root = root.parent;
            root.vars.put(name, val);
        }
    }

    private void execBlock(List<Stmt> stmts, Env env, int depth) {
        if (depth > 96) throw new LuaError("Stack overflow");
        for (Stmt s : stmts) {
            if (!running) throw new StopSignal();
            execStmt(s, env, depth);
        }
    }

    private void execStmt(Stmt s, Env env, int depth) {
        if (s instanceof LocalStmt) {
            LocalStmt ls = (LocalStmt) s;
            Object[] vals = evalExprList(ls.values, env, depth);
            for (int i = 0; i < ls.names.size(); i++) {
                env.define(ls.names.get(i), i < vals.length ? vals[i] : null);
            }
        } else if (s instanceof AssignStmt) {
            AssignStmt as = (AssignStmt) s;
            Object[] vals = evalExprList(as.values, env, depth);
            for (int i = 0; i < as.targets.size(); i++) {
                Expr target = as.targets.get(i);
                Object val = i < vals.length ? vals[i] : null;
                if (as.compoundOp != null) {
                    Object cur = evalExpr(target, env, depth);
                    val = applyBinary(as.compoundOp, cur, val);
                }
                assignTarget(target, val, env, depth);
            }
        } else if (s instanceof ExprStmt) {
            evalExpr(((ExprStmt) s).expr, env, depth);
        } else if (s instanceof IfStmt) {
            IfStmt is = (IfStmt) s;
            for (int i = 0; i < is.conds.size(); i++) {
                if (isTruthy(evalExpr(is.conds.get(i), env, depth))) {
                    execBlock(is.bodies.get(i), new Env(env), depth + 1);
                    return;
                }
            }
            if (is.elseBody != null) execBlock(is.elseBody, new Env(env), depth + 1);
        } else if (s instanceof WhileStmt) {
            WhileStmt ws = (WhileStmt) s;
            int guard = 0;
            while (running && isTruthy(evalExpr(ws.cond, env, depth))) {
                if (++guard > 50000) throw new LuaError("Loop iteration limit exceeded (use task.wait)");
                try { execBlock(ws.body, new Env(env), depth + 1); }
                catch (BreakSignal b) { break; }
                catch (ContinueSignal ignored) { }
            }
        } else if (s instanceof RepeatStmt) {
            RepeatStmt rs = (RepeatStmt) s;
            int guard = 0;
            do {
                if (!running) throw new StopSignal();
                if (++guard > 50000) throw new LuaError("Loop iteration limit exceeded");
                Env sub = new Env(env);
                try { execBlock(rs.body, sub, depth + 1); }
                catch (BreakSignal b) { break; }
                catch (ContinueSignal ignored) { }
                if (isTruthy(evalExpr(rs.cond, sub, depth))) break;
            } while (true);
        } else if (s instanceof ForNumStmt) {
            ForNumStmt fs = (ForNumStmt) s;
            double start = asNum(evalExpr(fs.start, env, depth), 1);
            double stop = asNum(evalExpr(fs.stop, env, depth), 0);
            double step = fs.step == null ? 1.0 : asNum(evalExpr(fs.step, env, depth), 1);
            if (step == 0) throw new LuaError("for step cannot be 0");
            int guard = 0;
            for (double v = start; (step > 0 ? v <= stop : v >= stop); v += step) {
                if (!running) throw new StopSignal();
                if (++guard > 50000) throw new LuaError("Loop iteration limit exceeded");
                Env sub = new Env(env);
                sub.define(fs.var, v);
                try { execBlock(fs.body, sub, depth + 1); }
                catch (BreakSignal b) { break; }
                catch (ContinueSignal ignored) { }
            }
        } else if (s instanceof ForInStmt) {
            ForInStmt fi = (ForInStmt) s;
            Object iter = evalExpr(fi.iterExpr, env, depth);
            if (iter instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map.Entry<Object, Object>> entries = (List<Map.Entry<Object, Object>>) iter;
                for (Map.Entry<Object, Object> e : entries) {
                    if (!running) throw new StopSignal();
                    Env sub = new Env(env);
                    if (fi.vars.size() > 0) sub.define(fi.vars.get(0), e.getKey());
                    if (fi.vars.size() > 1) sub.define(fi.vars.get(1), e.getValue());
                    try { execBlock(fi.body, sub, depth + 1); }
                    catch (BreakSignal b) { break; }
                    catch (ContinueSignal ignored) { }
                }
            }
        } else if (s instanceof ReturnStmt) {
            ReturnStmt rs = (ReturnStmt) s;
            throw new ReturnSignal(evalExprList(rs.values, env, depth));
        } else if (s instanceof BreakStmt) {
            throw new BreakSignal();
        } else if (s instanceof ContinueStmt) {
            throw new ContinueSignal();
        }
    }

    private void assignTarget(Expr target, Object val, Env env, int depth) {
        if (target instanceof VarExpr) {
            env.assign(((VarExpr) target).name, val);
        } else if (target instanceof IndexExpr) {
            IndexExpr ix = (IndexExpr) target;
            setMember(evalExpr(ix.obj, env, depth), evalExpr(ix.key, env, depth), val);
        }
    }

    private Object[] evalExprList(List<Expr> exprs, Env env, int depth) {
        ArrayList<Object> out = new ArrayList<>();
        for (int i = 0; i < exprs.size(); i++) {
            Object v = evalExpr(exprs.get(i), env, depth);
            if (i == exprs.size() - 1 && v instanceof Object[]) {
                Collections.addAll(out, (Object[]) v);
            } else {
                out.add(v instanceof Object[] ? (((Object[]) v).length > 0 ? ((Object[]) v)[0] : null) : v);
            }
        }
        return out.toArray();
    }

    private Object evalExpr(Expr e, Env env, int depth) {
        if (e instanceof LitExpr) return ((LitExpr) e).value;
        if (e instanceof VarExpr) return env.get(((VarExpr) e).name);
        if (e instanceof UnaryExpr) {
            UnaryExpr ue = (UnaryExpr) e;
            Object v = unwrapSingle(evalExpr(ue.expr, env, depth));
            if ("not".equals(ue.op)) return !isTruthy(v);
            if ("-".equals(ue.op)) return -asNum(v, 0);
            if ("#".equals(ue.op)) {
                if (v instanceof String) return (double) ((String) v).length();
                if (v instanceof LuaTable) return (double) ((LuaTable) v).length();
                return 0.0;
            }
        }
        if (e instanceof BinaryExpr) {
            BinaryExpr be = (BinaryExpr) e;
            if ("and".equals(be.op)) {
                Object l = unwrapSingle(evalExpr(be.left, env, depth));
                return isTruthy(l) ? unwrapSingle(evalExpr(be.right, env, depth)) : l;
            }
            if ("or".equals(be.op)) {
                Object l = unwrapSingle(evalExpr(be.left, env, depth));
                return isTruthy(l) ? l : unwrapSingle(evalExpr(be.right, env, depth));
            }
            Object l = unwrapSingle(evalExpr(be.left, env, depth));
            Object r = unwrapSingle(evalExpr(be.right, env, depth));
            return applyBinary(be.op, l, r);
        }
        if (e instanceof IndexExpr) {
            IndexExpr ix = (IndexExpr) e;
            return getMember(unwrapSingle(evalExpr(ix.obj, env, depth)), unwrapSingle(evalExpr(ix.key, env, depth)));
        }
        if (e instanceof CallExpr) {
            CallExpr ce = (CallExpr) e;
            Object fnObj;
            ArrayList<Object> argList = new ArrayList<>();
            if (ce.method != null) {
                Object self = unwrapSingle(evalExpr(ce.callee, env, depth));
                fnObj = getMember(self, ce.method);
                argList.add(self);
            } else {
                fnObj = unwrapSingle(evalExpr(ce.callee, env, depth));
            }
            Object[] evaluated = evalExprList(ce.args, env, depth);
            Collections.addAll(argList, evaluated);
            Callable fn = asCallable(fnObj);
            if (fn == null) throw new LuaError("attempt to call a " + luaType(fnObj, false) + " value");
            return fn.call(argList.toArray());
        }
        if (e instanceof FuncExpr) {
            final FuncExpr fe = (FuncExpr) e;
            final Env closure = env;
            return (Callable) args -> {
                Env callEnv = new Env(closure);
                for (int i = 0; i < fe.params.size(); i++) {
                    callEnv.define(fe.params.get(i), i < args.length ? args[i] : null);
                }
                try {
                    execBlock(fe.body, callEnv, depth + 1);
                } catch (ReturnSignal ret) {
                    if (ret.values.length == 0) return null;
                    return ret.values.length == 1 ? ret.values[0] : ret.values;
                }
                return null;
            };
        }
        if (e instanceof TableExpr) {
            TableExpr te = (TableExpr) e;
            LuaTable tbl = new LuaTable();
            int arrIdx = 1;
            for (int i = 0; i < te.keys.size(); i++) {
                Expr k = te.keys.get(i);
                Object v = unwrapSingle(evalExpr(te.values.get(i), env, depth));
                if (k == null) tbl.set((double) (arrIdx++), v);
                else tbl.set(unwrapSingle(evalExpr(k, env, depth)), v);
            }
            return tbl;
        }
        return null;
    }

    private Object applyBinary(String op, Object l, Object r) {
        if ("..".equals(op)) return luaToString(l) + luaToString(r);
        if ("==".equals(op)) return Objects.equals(l, r);
        if ("~=".equals(op)) return !Objects.equals(l, r);
        if (l instanceof Vec3 && r instanceof Vec3) {
            Vec3 a = (Vec3) l, b = (Vec3) r;
            if ("+".equals(op)) return new Vec3(a.x + b.x, a.y + b.y, a.z + b.z);
            if ("-".equals(op)) return new Vec3(a.x - b.x, a.y - b.y, a.z - b.z);
        }
        if (l instanceof Vec3 && r instanceof Number) {
            Vec3 a = (Vec3) l; double s = ((Number) r).doubleValue();
            if ("*".equals(op)) return new Vec3(a.x * s, a.y * s, a.z * s);
            if ("/".equals(op)) return new Vec3(a.x / s, a.y / s, a.z / s);
        }
        if (l instanceof CF && r instanceof CF && "*".equals(op)) return ((CF) l).mul((CF) r);
        if (l instanceof CF && r instanceof Vec3 && "+".equals(op)) {
            CF cf = (CF) l; Vec3 v = (Vec3) r;
            double[] m = cf.m.clone(); m[0] += v.x; m[1] += v.y; m[2] += v.z;
            return new CF(m);
        }
        double a = asNum(l, 0), b = asNum(r, 0);
        switch (op) {
            case "+": return a + b;
            case "-": return a - b;
            case "*": return a * b;
            case "/": return b == 0 ? 0.0 : a / b;
            case "//": return b == 0 ? 0.0 : Math.floor(a / b);
            case "%": return b == 0 ? 0.0 : a - Math.floor(a / b) * b;
            case "^": return Math.pow(a, b);
            case "<": return a < b;
            case "<=": return a <= b;
            case ">": return a > b;
            case ">=": return a >= b;
            default: return null;
        }
    }

    private static Object unwrapSingle(Object v) {
        if (v instanceof Object[]) {
            Object[] arr = (Object[]) v;
            return arr.length > 0 ? arr[0] : null;
        }
        return v;
    }

    private static Object arg(Object[] args, int idx, Object def) {
        return idx < args.length ? args[idx] : def;
    }

    private static Callable asCallable(Object o) { return o instanceof Callable ? (Callable) o : null; }

    private static boolean isTruthy(Object o) { return o != null && !Boolean.FALSE.equals(o); }

    private static double asNum(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (Exception ignored) { }
        }
        return def;
    }

    private static String luaType(Object o, boolean roblox) {
        if (o == null) return "nil";
        if (o instanceof Boolean) return "boolean";
        if (o instanceof Number) return "number";
        if (o instanceof String) return "string";
        if (o instanceof Callable) return "function";
        if (o instanceof LuaTable) return "table";
        if (roblox) {
            if (o instanceof InstanceProxy) return "Instance";
            if (o instanceof Vec3) return "Vector3";
            if (o instanceof CF) return "CFrame";
            if (o instanceof Col3) return "Color3";
            if (o instanceof UDim2) return "UDim2";
        }
        return "userdata";
    }

    private static String luaToString(Object o) {
        if (o == null) return "nil";
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        }
        return String.valueOf(o);
    }

    // ---- Lexer & Parser ----
    private enum TT {
        EOF, IDENT, NUM, STR,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACK, RBRACK, COMMA, DOT, COLON, SEMI,
        PLUS, MINUS, STAR, SLASH, IDIV, PCT, CARET, HASH, CONCAT,
        EQ, EQEQ, NEQ, LT, LTE, GT, GTE, PLUSEQ, MINUSEQ, STAREQ, SLASHEQ, CONCATEQ,
        LOCAL, FUNCTION, END, IF, THEN, ELSEIF, ELSE, WHILE, DO, REPEAT, UNTIL,
        FOR, IN, RETURN, BREAK, CONTINUE, AND, OR, NOT, TRUE, FALSE, NIL
    }

    private static final class Token {
        final TT type; final String text; final int line;
        Token(TT type, String text, int line) { this.type = type; this.text = text; this.line = line; }
    }

    private static final HashMap<String, TT> KEYWORDS = new HashMap<>();
    static {
        KEYWORDS.put("local", TT.LOCAL); KEYWORDS.put("function", TT.FUNCTION); KEYWORDS.put("end", TT.END);
        KEYWORDS.put("if", TT.IF); KEYWORDS.put("then", TT.THEN); KEYWORDS.put("elseif", TT.ELSEIF);
        KEYWORDS.put("else", TT.ELSE); KEYWORDS.put("while", TT.WHILE); KEYWORDS.put("do", TT.DO);
        KEYWORDS.put("repeat", TT.REPEAT); KEYWORDS.put("until", TT.UNTIL); KEYWORDS.put("for", TT.FOR);
        KEYWORDS.put("in", TT.IN); KEYWORDS.put("return", TT.RETURN); KEYWORDS.put("break", TT.BREAK);
        KEYWORDS.put("continue", TT.CONTINUE); KEYWORDS.put("and", TT.AND); KEYWORDS.put("or", TT.OR);
        KEYWORDS.put("not", TT.NOT); KEYWORDS.put("true", TT.TRUE); KEYWORDS.put("false", TT.FALSE);
        KEYWORDS.put("nil", TT.NIL);
    }

    private static List<Token> tokenize(String s) {
        ArrayList<Token> out = new ArrayList<>();
        int i = 0, n = s.length(), line = 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r') { i++; continue; }
            if (c == '\n') { line++; i++; continue; }
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                i += 2;
                if (i + 1 < n && s.charAt(i) == '[' && s.charAt(i + 1) == '[') {
                    i += 2;
                    while (i + 1 < n && !(s.charAt(i) == ']' && s.charAt(i + 1) == ']')) {
                        if (s.charAt(i) == '\n') line++;
                        i++;
                    }
                    i = Math.min(n, i + 2);
                } else {
                    while (i < n && s.charAt(i) != '\n') i++;
                }
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                char quote = c; i++;
                StringBuilder sb = new StringBuilder();
                while (i < n && s.charAt(i) != quote) {
                    char ch = s.charAt(i++);
                    if (ch == '\\' && i < n) {
                        char esc = s.charAt(i++);
                        if (esc == 'n') sb.append('\n');
                        else if (esc == 't') sb.append('\t');
                        else if (esc == 'r') sb.append('\r');
                        else sb.append(esc);
                    } else {
                        if (ch == '\n') line++;
                        sb.append(ch);
                    }
                }
                if (i < n) i++;
                out.add(new Token(TT.STR, sb.toString(), line));
                continue;
            }
            if (c == '[' && i + 1 < n && s.charAt(i + 1) == '[') {
                i += 2;
                StringBuilder sb = new StringBuilder();
                while (i + 1 < n && !(s.charAt(i) == ']' && s.charAt(i + 1) == ']')) {
                    if (s.charAt(i) == '\n') line++;
                    sb.append(s.charAt(i++));
                }
                i = Math.min(n, i + 2);
                out.add(new Token(TT.STR, sb.toString(), line));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int start = i;
                if (c == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                    i += 2;
                    while (i < n && (Character.isDigit(s.charAt(i)) || "abcdefABCDEF_".indexOf(s.charAt(i)) >= 0)) i++;
                    long val = Long.parseLong(s.substring(start + 2, i).replace("_", ""), 16);
                    out.add(new Token(TT.NUM, String.valueOf(val), line));
                } else {
                    while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) i++;
                    if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                        i++;
                        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                        while (i < n && Character.isDigit(s.charAt(i))) i++;
                    }
                    out.add(new Token(TT.NUM, s.substring(start, i).replace("_", ""), line));
                }
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                String word = s.substring(start, i);
                TT kw = KEYWORDS.get(word);
                out.add(new Token(kw != null ? kw : TT.IDENT, word, line));
                continue;
            }
            char next = i + 1 < n ? s.charAt(i + 1) : 0;
            if (c == '.' && next == '.' && i + 2 < n && s.charAt(i + 2) == '=') { out.add(new Token(TT.CONCATEQ, "..=", line)); i += 3; continue; }
            if (c == '.' && next == '.') { out.add(new Token(TT.CONCAT, "..", line)); i += 2; continue; }
            if (c == '=' && next == '=') { out.add(new Token(TT.EQEQ, "==", line)); i += 2; continue; }
            if (c == '~' && next == '=') { out.add(new Token(TT.NEQ, "~=", line)); i += 2; continue; }
            if (c == '<' && next == '=') { out.add(new Token(TT.LTE, "<=", line)); i += 2; continue; }
            if (c == '>' && next == '=') { out.add(new Token(TT.GTE, ">=", line)); i += 2; continue; }
            if (c == '+' && next == '=') { out.add(new Token(TT.PLUSEQ, "+=", line)); i += 2; continue; }
            if (c == '-' && next == '=') { out.add(new Token(TT.MINUSEQ, "-=", line)); i += 2; continue; }
            if (c == '*' && next == '=') { out.add(new Token(TT.STAREQ, "*=", line)); i += 2; continue; }
            if (c == '/' && next == '=') { out.add(new Token(TT.SLASHEQ, "/=", line)); i += 2; continue; }
            if (c == '/' && next == '/') { out.add(new Token(TT.IDIV, "//", line)); i += 2; continue; }
            i++;
            switch (c) {
                case '(': out.add(new Token(TT.LPAREN, "(", line)); break;
                case ')': out.add(new Token(TT.RPAREN, ")", line)); break;
                case '{': out.add(new Token(TT.LBRACE, "{", line)); break;
                case '}': out.add(new Token(TT.RBRACE, "}", line)); break;
                case '[': out.add(new Token(TT.LBRACK, "[", line)); break;
                case ']': out.add(new Token(TT.RBRACK, "]", line)); break;
                case ',': out.add(new Token(TT.COMMA, ",", line)); break;
                case '.': out.add(new Token(TT.DOT, ".", line)); break;
                case ':': out.add(new Token(TT.COLON, ":", line)); break;
                case ';': out.add(new Token(TT.SEMI, ";", line)); break;
                case '+': out.add(new Token(TT.PLUS, "+", line)); break;
                case '-': out.add(new Token(TT.MINUS, "-", line)); break;
                case '*': out.add(new Token(TT.STAR, "*", line)); break;
                case '/': out.add(new Token(TT.SLASH, "/", line)); break;
                case '%': out.add(new Token(TT.PCT, "%", line)); break;
                case '^': out.add(new Token(TT.CARET, "^", line)); break;
                case '#': out.add(new Token(TT.HASH, "#", line)); break;
                case '=': out.add(new Token(TT.EQ, "=", line)); break;
                case '<': out.add(new Token(TT.LT, "<", line)); break;
                case '>': out.add(new Token(TT.GT, ">", line)); break;
                default: break;
            }
        }
        out.add(new Token(TT.EOF, "", line));
        return out;
    }

    private interface Stmt {}
    private interface Expr {}
    private static final class LocalStmt implements Stmt { final List<String> names; final List<Expr> values; LocalStmt(List<String> n, List<Expr> v) { names = n; values = v; } }
    private static final class AssignStmt implements Stmt { final List<Expr> targets, values; final String compoundOp; AssignStmt(List<Expr> t, List<Expr> v, String op) { targets = t; values = v; compoundOp = op; } }
    private static final class ExprStmt implements Stmt { final Expr expr; ExprStmt(Expr e) { expr = e; } }
    private static final class IfStmt implements Stmt { final List<Expr> conds; final List<List<Stmt>> bodies; final List<Stmt> elseBody; IfStmt(List<Expr> c, List<List<Stmt>> b, List<Stmt> e) { conds = c; bodies = b; elseBody = e; } }
    private static final class WhileStmt implements Stmt { final Expr cond; final List<Stmt> body; WhileStmt(Expr c, List<Stmt> b) { cond = c; body = b; } }
    private static final class RepeatStmt implements Stmt { final List<Stmt> body; final Expr cond; RepeatStmt(List<Stmt> b, Expr c) { body = b; cond = c; } }
    private static final class ForNumStmt implements Stmt { final String var; final Expr start, stop, step; final List<Stmt> body; ForNumStmt(String v, Expr s, Expr e, Expr st, List<Stmt> b) { var = v; start = s; stop = e; step = st; body = b; } }
    private static final class ForInStmt implements Stmt { final List<String> vars; final Expr iterExpr; final List<Stmt> body; ForInStmt(List<String> v, Expr i, List<Stmt> b) { vars = v; iterExpr = i; body = b; } }
    private static final class ReturnStmt implements Stmt { final List<Expr> values; ReturnStmt(List<Expr> v) { values = v; } }
    private static final class BreakStmt implements Stmt {}
    private static final class ContinueStmt implements Stmt {}

    private static final class LitExpr implements Expr { final Object value; LitExpr(Object v) { value = v; } }
    private static final class VarExpr implements Expr { final String name; VarExpr(String n) { name = n; } }
    private static final class UnaryExpr implements Expr { final String op; final Expr expr; UnaryExpr(String o, Expr e) { op = o; expr = e; } }
    private static final class BinaryExpr implements Expr { final String op; final Expr left, right; BinaryExpr(String o, Expr l, Expr r) { op = o; left = l; right = r; } }
    private static final class IndexExpr implements Expr { final Expr obj, key; IndexExpr(Expr o, Expr k) { obj = o; key = k; } }
    private static final class CallExpr implements Expr { final Expr callee; final String method; final List<Expr> args; CallExpr(Expr c, String m, List<Expr> a) { callee = c; method = m; args = a; } }
    private static final class FuncExpr implements Expr { final List<String> params; final List<Stmt> body; FuncExpr(List<String> p, List<Stmt> b) { params = p; body = b; } }
    private static final class TableExpr implements Expr { final List<Expr> keys, values; TableExpr(List<Expr> k, List<Expr> v) { keys = k; values = v; } }

    private static final class Parser {
        final List<Token> tokens;
        int pos;
        Parser(List<Token> tokens) { this.tokens = tokens; }
        Token peek() { return tokens.get(Math.min(pos, tokens.size() - 1)); }
        Token peek(int offset) { return tokens.get(Math.min(pos + offset, tokens.size() - 1)); }
        Token next() { Token t = peek(); if (pos < tokens.size() - 1) pos++; return t; }
        boolean match(TT t) { if (peek().type == t) { next(); return true; } return false; }
        void expect(TT t) { if (!match(t)) throw new LuaError("Line " + peek().line + ": expected " + t + " near '" + peek().text + "'"); }

        void skipOptionalTypeAnnotation() {
            if (peek().type == TT.COLON) {
                next();
                if (peek().type == TT.IDENT) next();
                if (peek().type == TT.DOT) { next(); if (peek().type == TT.IDENT) next(); }
            }
        }

        List<Stmt> parseBlock() {
            ArrayList<Stmt> list = new ArrayList<>();
            while (peek().type != TT.EOF && peek().type != TT.END && peek().type != TT.ELSE && peek().type != TT.ELSEIF && peek().type != TT.UNTIL) {
                if (match(TT.SEMI)) continue;
                list.add(parseStmt());
            }
            return list;
        }

        Stmt parseStmt() {
            TT t = peek().type;
            if (t == TT.LOCAL) {
                next();
                if (match(TT.FUNCTION)) {
                    String name = next().text;
                    FuncExpr fn = parseFuncBody(false);
                    return new LocalStmt(Collections.singletonList(name), Collections.singletonList(fn));
                }
                ArrayList<String> names = new ArrayList<>();
                names.add(next().text);
                skipOptionalTypeAnnotation();
                while (match(TT.COMMA)) {
                    names.add(next().text);
                    skipOptionalTypeAnnotation();
                }
                List<Expr> vals = match(TT.EQ) ? parseExprList() : Collections.emptyList();
                return new LocalStmt(names, vals);
            }
            if (t == TT.FUNCTION) {
                next();
                Expr target = new VarExpr(next().text);
                boolean hasSelf = false;
                while (match(TT.DOT)) target = new IndexExpr(target, new LitExpr(next().text));
                if (match(TT.COLON)) { target = new IndexExpr(target, new LitExpr(next().text)); hasSelf = true; }
                FuncExpr fn = parseFuncBody(hasSelf);
                return new AssignStmt(Collections.singletonList(target), Collections.singletonList(fn), null);
            }
            if (t == TT.IF) {
                next();
                ArrayList<Expr> conds = new ArrayList<>();
                ArrayList<List<Stmt>> bodies = new ArrayList<>();
                conds.add(parseExpr());
                expect(TT.THEN);
                bodies.add(parseBlock());
                while (match(TT.ELSEIF)) {
                    conds.add(parseExpr());
                    expect(TT.THEN);
                    bodies.add(parseBlock());
                }
                List<Stmt> elseBody = match(TT.ELSE) ? parseBlock() : null;
                expect(TT.END);
                return new IfStmt(conds, bodies, elseBody);
            }
            if (t == TT.WHILE) {
                next();
                Expr cond = parseExpr();
                expect(TT.DO);
                List<Stmt> body = parseBlock();
                expect(TT.END);
                return new WhileStmt(cond, body);
            }
            if (t == TT.REPEAT) {
                next();
                List<Stmt> body = parseBlock();
                expect(TT.UNTIL);
                return new RepeatStmt(body, parseExpr());
            }
            if (t == TT.FOR) {
                next();
                String first = next().text;
                if (match(TT.EQ)) {
                    Expr start = parseExpr();
                    expect(TT.COMMA);
                    Expr stop = parseExpr();
                    Expr step = match(TT.COMMA) ? parseExpr() : null;
                    expect(TT.DO);
                    List<Stmt> body = parseBlock();
                    expect(TT.END);
                    return new ForNumStmt(first, start, stop, step, body);
                }
                ArrayList<String> vars = new ArrayList<>();
                vars.add(first);
                while (match(TT.COMMA)) vars.add(next().text);
                expect(TT.IN);
                Expr iter = parseExpr();
                expect(TT.DO);
                List<Stmt> body = parseBlock();
                expect(TT.END);
                return new ForInStmt(vars, iter, body);
            }
            if (t == TT.RETURN) {
                next();
                TT nt = peek().type;
                if (nt == TT.END || nt == TT.ELSE || nt == TT.ELSEIF || nt == TT.UNTIL || nt == TT.EOF || nt == TT.SEMI) {
                    return new ReturnStmt(Collections.emptyList());
                }
                return new ReturnStmt(parseExprList());
            }
            if (t == TT.BREAK) { next(); return new BreakStmt(); }
            if (t == TT.CONTINUE) { next(); return new ContinueStmt(); }

            Expr first = parsePrefix();
            if (peek().type == TT.PLUSEQ || peek().type == TT.MINUSEQ || peek().type == TT.STAREQ || peek().type == TT.SLASHEQ || peek().type == TT.CONCATEQ) {
                String cop = next().text;
                String baseOp = cop.substring(0, cop.length() - 1);
                return new AssignStmt(Collections.singletonList(first), Collections.singletonList(parseExpr()), baseOp);
            }
            if (peek().type == TT.EQ || peek().type == TT.COMMA) {
                ArrayList<Expr> targets = new ArrayList<>();
                targets.add(first);
                while (match(TT.COMMA)) targets.add(parsePrefix());
                expect(TT.EQ);
                return new AssignStmt(targets, parseExprList(), null);
            }
            return new ExprStmt(first);
        }

        FuncExpr parseFuncBody(boolean hasSelf) {
            expect(TT.LPAREN);
            ArrayList<String> params = new ArrayList<>();
            if (hasSelf) params.add("self");
            if (peek().type != TT.RPAREN) {
                if (peek().type == TT.IDENT) {
                    params.add(next().text);
                    skipOptionalTypeAnnotation();
                }
                while (match(TT.COMMA)) {
                    if (peek().type == TT.IDENT) {
                        params.add(next().text);
                        skipOptionalTypeAnnotation();
                    }
                }
            }
            expect(TT.RPAREN);
            skipOptionalTypeAnnotation();
            List<Stmt> body = parseBlock();
            expect(TT.END);
            return new FuncExpr(params, body);
        }

        List<Expr> parseExprList() {
            ArrayList<Expr> list = new ArrayList<>();
            list.add(parseExpr());
            while (match(TT.COMMA)) list.add(parseExpr());
            return list;
        }

        Expr parseExpr() { return parseOr(); }
        Expr parseOr() {
            Expr e = parseAnd();
            while (match(TT.OR)) e = new BinaryExpr("or", e, parseAnd());
            return e;
        }
        Expr parseAnd() {
            Expr e = parseCmp();
            while (match(TT.AND)) e = new BinaryExpr("and", e, parseCmp());
            return e;
        }
        Expr parseCmp() {
            Expr e = parseConcat();
            while (peek().type == TT.EQEQ || peek().type == TT.NEQ || peek().type == TT.LT || peek().type == TT.LTE || peek().type == TT.GT || peek().type == TT.GTE) {
                e = new BinaryExpr(next().text, e, parseConcat());
            }
            return e;
        }
        Expr parseConcat() {
            Expr e = parseAdd();
            if (match(TT.CONCAT)) e = new BinaryExpr("..", e, parseConcat());
            return e;
        }
        Expr parseAdd() {
            Expr e = parseMul();
            while (peek().type == TT.PLUS || peek().type == TT.MINUS) e = new BinaryExpr(next().text, e, parseMul());
            return e;
        }
        Expr parseMul() {
            Expr e = parseUnary();
            while (peek().type == TT.STAR || peek().type == TT.SLASH || peek().type == TT.IDIV || peek().type == TT.PCT) {
                e = new BinaryExpr(next().text, e, parseUnary());
            }
            return e;
        }
        Expr parseUnary() {
            if (peek().type == TT.NOT || peek().type == TT.MINUS || peek().type == TT.HASH) {
                return new UnaryExpr(next().text, parseUnary());
            }
            return parsePow();
        }
        Expr parsePow() {
            Expr e = parsePrefix();
            if (match(TT.CARET)) e = new BinaryExpr("^", e, parseUnary());
            return e;
        }

        Expr parsePrefix() {
            Expr e = parseAtom();
            while (true) {
                if (match(TT.DOT)) {
                    e = new IndexExpr(e, new LitExpr(next().text));
                } else if (match(TT.LBRACK)) {
                    Expr k = parseExpr();
                    expect(TT.RBRACK);
                    e = new IndexExpr(e, k);
                } else if (match(TT.COLON)) {
                    String method = next().text;
                    List<Expr> args = parseCallArgs();
                    e = new CallExpr(e, method, args);
                } else if (peek().type == TT.LPAREN || peek().type == TT.STR || peek().type == TT.LBRACE) {
                    e = new CallExpr(e, null, parseCallArgs());
                } else {
                    break;
                }
            }
            return e;
        }

        List<Expr> parseCallArgs() {
            if (match(TT.LPAREN)) {
                if (match(TT.RPAREN)) return Collections.emptyList();
                List<Expr> list = parseExprList();
                expect(TT.RPAREN);
                return list;
            }
            if (peek().type == TT.STR) return Collections.singletonList(new LitExpr(next().text));
            if (peek().type == TT.LBRACE) return Collections.singletonList(parseTable());
            throw new LuaError("Line " + peek().line + ": expected call arguments");
        }

        Expr parseAtom() {
            Token t = peek();
            if (match(TT.NUM)) return new LitExpr(Double.parseDouble(t.text));
            if (match(TT.STR)) return new LitExpr(t.text);
            if (match(TT.TRUE)) return new LitExpr(Boolean.TRUE);
            if (match(TT.FALSE)) return new LitExpr(Boolean.FALSE);
            if (match(TT.NIL)) return new LitExpr(null);
            if (match(TT.IDENT)) return new VarExpr(t.text);
            if (match(TT.FUNCTION)) return parseFuncBody(false);
            if (t.type == TT.LBRACE) return parseTable();
            if (match(TT.LPAREN)) {
                Expr inner = parseExpr();
                expect(TT.RPAREN);
                return inner;
            }
            throw new LuaError("Line " + t.line + ": unexpected token '" + t.text + "'");
        }

        Expr parseTable() {
            expect(TT.LBRACE);
            ArrayList<Expr> keys = new ArrayList<>();
            ArrayList<Expr> vals = new ArrayList<>();
            while (peek().type != TT.RBRACE && peek().type != TT.EOF) {
                if (match(TT.LBRACK)) {
                    Expr k = parseExpr();
                    expect(TT.RBRACK);
                    expect(TT.EQ);
                    keys.add(k);
                    vals.add(parseExpr());
                } else if (peek().type == TT.IDENT && peek(1).type == TT.EQ) {
                    keys.add(new LitExpr(next().text));
                    next();
                    vals.add(parseExpr());
                } else {
                    keys.add(null);
                    vals.add(parseExpr());
                }
                if (!match(TT.COMMA) && !match(TT.SEMI)) break;
            }
            expect(TT.RBRACE);
            return new TableExpr(keys, vals);
        }
    }
}
