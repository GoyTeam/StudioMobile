package dev.falix.studiomobile;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Studio Mobile engine: opens a real .rbxl/.rbxlx place, shows the Roblox data model as an
 * Explorer tree, edits properties, renders a GL viewport, and plays the character with a
 * joystick. Played locally on device; cloud Save/Publish stays in the project manager.
 */
public final class EngineActivity extends Activity {
    private int PANEL, PANEL2, TEXT, MUTED, BORDER, ACCENT, SOFT, CANVAS;
    private boolean dark;
    private float ui() { return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("compactUi", true) ? 0.92f : 1f; }

    private final EngineScene scene = new EngineScene();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EngineView viewport;
    private LinearLayout explorerList, propertyList, toolPad, toolStrip;
    private TextView title, subtitle, playLabel, explorerCount;
    private EditText explorerSearch;
    private FrameLayout viewportLayer;
    private JoystickView joystick;
    private View jumpButton;
    private View playControls;
    private final HashSet<Instance> expanded = new HashSet<>();
    private final HashSet<Instance> collapsed = new HashSet<>();

    private Uri placeUri;
    private String placeName = "Place";
    private Uri folderUri;
    private String placeId;
    private boolean autoPlay;
    private boolean loading = true;
    private String loadError;
    private int savedVersion;
    private String tool = "select";
    private double step = 0.5;

    /** Opens a place in the 3D editor. Managed project places also update their manifest hash on save. */
    static void launch(android.app.Activity from, Uri placeUri, String placeName, Uri projectFolder, String placeId) {
        Intent intent = new Intent(from, EngineActivity.class);
        intent.putExtra("uri", placeUri.toString());
        intent.putExtra("name", placeName == null ? "Place" : placeName);
        if (projectFolder != null) intent.putExtra("folder", projectFolder.toString());
        if (placeId != null) intent.putExtra("placeId", placeId);
        from.startActivity(intent);
    }

    @Override public void onCreate(Bundle state) {
        String appearance = getSharedPreferences("settings", MODE_PRIVATE).getString("appearance", "system");
        dark = "dark".equals(appearance) || "system".equals(appearance) && (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        applyPalette();
        Intent intent = getIntent();
        if (intent.getStringExtra("name") != null) placeName = intent.getStringExtra("name");
        String uri = intent.getStringExtra("uri");
        if (uri != null) placeUri = Uri.parse(uri);
        String folder = intent.getStringExtra("folder");
        if (folder != null) folderUri = Uri.parse(folder);
        placeId = intent.getStringExtra("placeId");
        autoPlay = intent.getBooleanExtra("play", false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        getWindow().setStatusBarColor(dark ? 0xff15181e : 0xfff1f3f7);
        getWindow().setNavigationBarColor(dark ? 0xff15181e : 0xfff1f3f7);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(build());
        load();
    }

    @Override protected void onResume() { super.onResume(); if (viewport != null) viewport.onResume(); }
    @Override protected void onPause() { if (viewport != null) viewport.onPause(); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    @Override public void onBackPressed() {
        if (scene.playing) { stopPlay(); return; }
        if (!loading && loadError == null && scene.version != savedVersion) {
            new AlertDialog.Builder(this).setTitle("Save changes?").setMessage("This place has unsaved editor changes.")
                    .setPositiveButton("Save", (d, w) -> { save(false); })
                    .setNegativeButton("Discard", (d, w) -> finish())
                    .setNeutralButton("Keep editing", null).show();
            return;
        }
        super.onBackPressed();
    }

    private void applyPalette() {
        if (dark) {
            PANEL = 0xff1c1f26; PANEL2 = 0xff23272f; TEXT = 0xffe8ecf4; MUTED = 0xff96a0b0;
            BORDER = 0xff343a45; ACCENT = 0xff6f9bff; SOFT = 0xff2a3350; CANVAS = 0xff15181e;
        } else {
            PANEL = 0xfff1f3f7; PANEL2 = 0xffffffff; TEXT = 0xff1e2430; MUTED = 0xff5c6775;
            BORDER = 0xffdde2ea; ACCENT = 0xff2d56e8; SOFT = 0xffe7edff; CANVAS = 0xffe9ecf2;
        }
    }

    // ------------------------------------------------------------------ layout
    private View build() {
        LinearLayout root = col();
        root.setBackgroundColor(PANEL);
        root.addView(buildTopBar(), lp(-1, 44));
        LinearLayout body = row();
        root.addView(body, lp(-1, 0, 1));
        body.addView(buildExplorer(), lp(dp(196), -1));
        body.addView(line(), lp(dp(1), -1));
        body.addView(buildViewport(), lp(0, -1, 1));
        body.addView(line(), lp(dp(1), -1));
        body.addView(buildProperties(), lp(dp(228), -1));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = row();
        bar.setBackgroundColor(PANEL2);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(iconButton("arrow", "Back", this::onBackPressed), lp(dp(36), dp(36)));
        LinearLayout names = col();
        names.setPadding(dp(8), 0, 0, 0);
        title = text(placeName, 13, true, TEXT);
        subtitle = text("Loading place…", 10, false, MUTED);
        names.addView(title);
        names.addView(subtitle);
        bar.addView(names, lp(0, -2, 1));
        bar.addView(iconButton("search", "Search instances", () -> { if (explorerSearch != null) explorerSearch.requestFocus(); }), lp(dp(36), dp(36)));
        bar.addView(iconButton("undo", "Undo", () -> { scene.undo(); afterEdit(); }), lp(dp(36), dp(36)));
        bar.addView(iconButton("redo", "Redo", () -> { scene.redo(); afterEdit(); }), lp(dp(36), dp(36)));
        bar.addView(line(), lp(dp(1), dp(20)));
        LinearLayout playButton = row();
        playButton.setGravity(Gravity.CENTER_VERTICAL);
        playButton.setPadding(dp(10), 0, dp(10), 0);
        playButton.setBackground(shape(SOFT, 8));
        playLabel = text("Play", 12, true, ACCENT);
        StudioIcon playIcon = new StudioIcon(this, "play", ACCENT);
        playButton.addView(playIcon, lp(dp(14), dp(14)));
        playButton.addView(playLabel, margin(8, 0, -2, -2));
        playButton.setOnClickListener(v -> { if (scene.playing) stopPlay(); else startPlay(); });
        bar.addView(playButton, lp(-2, dp(30)));
        bar.addView(iconButton("save", "Save place", () -> save(true)), lp(dp(36), dp(36)));
        bar.addView(iconButton("more", "More actions", () -> moreMenu(bar)), lp(dp(36), dp(36)));
        return bar;
    }

    private View buildExplorer() {
        LinearLayout panel = col();
        panel.setBackgroundColor(PANEL);
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), 0, dp(8), 0);
        head.addView(new StudioIcon(this, "layers", MUTED), lp(dp(14), dp(14)));
        TextView label = text("EXPLORER", 10, true, MUTED);
        head.addView(label, margin(6, 0, -2, -2));
        head.addView(new View(this), lp(0, 1, 1));
        explorerCount = text("–", 10, false, MUTED);
        head.addView(explorerCount);
        panel.addView(head, lp(-1, dp(26)));
        panel.addView(line(), lp(-1, dp(1)));
        explorerSearch = new EditText(this);
        explorerSearch.setSingleLine(true);
        explorerSearch.setTextSize(11 * ui());
        explorerSearch.setTextColor(TEXT);
        explorerSearch.setHintTextColor(MUTED);
        explorerSearch.setHint("Filter instances");
        explorerSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        explorerSearch.setPadding(dp(8), 0, dp(8), 0);
        explorerSearch.setBackground(shape(dark ? CANVAS : 0xffe8ebf1, 6));
        explorerSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { buildTree(); }
            public void afterTextChanged(android.text.Editable e) { }
        });
        LinearLayout searchWrap = col();
        searchWrap.setPadding(dp(8), dp(6), dp(8), dp(6));
        searchWrap.addView(explorerSearch, lp(-1, dp(28)));
        panel.addView(searchWrap);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        explorerList = col();
        explorerList.setPadding(0, dp(2), 0, dp(8));
        scroll.addView(explorerList, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, lp(-1, 0, 1));
        return panel;
    }

    private View buildViewport() {
        viewportLayer = new FrameLayout(this);
        viewportLayer.setBackgroundColor(CANVAS);
        viewport = new EngineView(this, scene);
        viewport.setListener(new EngineView.Listener() {
            public void onPick(Instance hit) { scene.selection = hit; if (hit != null) { scene.focus(hit); expanded.add(hit); for (Instance p = hit.parent; p != null; p = p.parent) expanded.add(p); } refresh(); }
            public void onCameraMoved() { }
        });
        viewportLayer.addView(viewport, new FrameLayout.LayoutParams(-1, -1));

        toolStrip = row();
        toolStrip.setPadding(dp(4), dp(2), dp(4), dp(2));
        toolStrip.setBackground(shape(0xcc23272f, 8));
        toolStrip.addView(toolButton("select", "arrow", "Select"), lp(dp(30), dp(30)));
        toolStrip.addView(toolButton("move", "move", "Move"), lp(dp(30), dp(30)));
        toolStrip.addView(toolButton("rotate", "rotate", "Rotate"), lp(dp(30), dp(30)));
        toolStrip.addView(toolButton("scale", "scale", "Scale"), lp(dp(30), dp(30)));
        toolStrip.addView(toolButton("insert", "cube", "Insert"), lp(dp(30), dp(30)));
        toolStrip.addView(toolButton("duplicate", "copy", "Duplicate"), lp(dp(30), dp(30)));
        toolStrip.addView(toolButton("delete", "trash", "Delete"), lp(dp(30), dp(30)));
        toolStrip.addView(toolButton("focus", "search", "Focus selection"), lp(dp(30), dp(30)));
        FrameLayout.LayoutParams toolParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        toolParams.setMargins(dp(8), dp(8), 0, 0);
        viewportLayer.addView(toolStrip, toolParams);

        toolPad = col();
        toolPad.setPadding(dp(6), dp(6), dp(6), dp(6));
        toolPad.setBackground(shape(0xcc23272f, 10));
        toolPad.setVisibility(View.GONE);
        FrameLayout.LayoutParams padParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        padParams.setMargins(dp(8), 0, 0, dp(8));
        viewportLayer.addView(toolPad, padParams);

        playControls = buildPlayControls();
        playControls.setVisibility(View.GONE);
        viewportLayer.addView(playControls, new FrameLayout.LayoutParams(-1, -1));
        return viewportLayer;
    }

    private View buildPlayControls() {
        FrameLayout layer = new FrameLayout(this);
        joystick = new JoystickView(this);
        joystick.setListener((x, y) -> { scene.moveX = x; scene.moveZ = y; });
        FrameLayout.LayoutParams joy = new FrameLayout.LayoutParams(dp(128), dp(128), Gravity.BOTTOM | Gravity.LEFT);
        joy.setMargins(dp(14), 0, 0, dp(14));
        layer.addView(joystick, joy);
        jumpButton = roundButton("JUMP", () -> { scene.jumpQueued = true; viewport.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); });
        FrameLayout.LayoutParams jump = new FrameLayout.LayoutParams(dp(74), dp(74), Gravity.BOTTOM | Gravity.RIGHT);
        jump.setMargins(0, 0, dp(20), dp(20));
        layer.addView(jumpButton, jump);
        TextView hint = text("Drag to look around", 11, false, 0xffffffff);
        hint.setShadowLayer(4, 0, 1, 0x99000000);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintParams.setMargins(0, 0, 0, dp(12));
        layer.addView(hint, hintParams);
        return layer;
    }

    private View buildProperties() {
        LinearLayout panel = col();
        panel.setBackgroundColor(PANEL);
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), 0, dp(8), 0);
        head.addView(new StudioIcon(this, "list", MUTED), lp(dp(14), dp(14)));
        head.addView(text("PROPERTIES", 10, true, MUTED), margin(6, 0, -2, -2));
        panel.addView(head, lp(-1, dp(26)));
        panel.addView(line(), lp(-1, dp(1)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        propertyList = col();
        propertyList.setPadding(dp(10), dp(8), dp(10), dp(12));
        scroll.addView(propertyList, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, lp(-1, 0, 1));
        return panel;
    }

    // ------------------------------------------------------------------ loading
    private void load() {
        if (placeUri == null) { fail("No place file was supplied."); return; }
        subtitle.setText("Decoding place…");
        worker.execute(() -> {
            File tmp = null;
            try {
                tmp = File.createTempFile("engine-", ".place", getCacheDir());
                try (InputStream in = open(placeUri); OutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new IOException("The place file could not be opened.");
                    byte[] buffer = new byte[32768];
                    int n; long total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > PlaceDecoder.MAX_BYTES) throw new IOException("Place exceeds the 100 MiB editing limit.");
                        out.write(buffer, 0, n);
                    }
                }
                PlaceDecoder.Result result = PlaceDecoder.decode(tmp);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    scene.setRoot(result.root, placeName);
                    scene.warnings.addAll(result.warnings);
                    for (String warning : result.warnings) scene.unmodeledWarnings.add(warning);
                    loading = false;
                    int count = 0;
                    int parts = 0;
                    for (Instance i : scene.root.all(null)) { count++; if (i.isRenderable()) parts++; }
                    subtitle.setText(result.format + "  ·  " + count + " instances  ·  " + parts + " parts");
                    expanded.addAll(scene.root.children);
                    if (scene.workspace != null) expanded.add(scene.workspace);
                    Instance first = scene.root.findFirst("SpawnLocation");
                    if (first != null) { scene.selection = first; scene.focus(first); }
                    refresh();
                    savedVersion = scene.version;
                    if (autoPlay) startPlay();
                });
            } catch (Exception e) {
                final String message = RobloxApi.safe(e);
                runOnUiThread(() -> fail(message));
            } finally { if (tmp != null) tmp.delete(); }
        });
    }

    private void fail(String message) {
        loading = false;
        loadError = message;
        subtitle.setText("Could not open this place");
        propertyList.removeAllViews();
        toolPad.setVisibility(View.GONE);
        LinearLayout cardView = col();
        cardView.setPadding(dp(14), dp(14), dp(14), dp(14));
        cardView.setBackground(shape(CANVAS, 10));
        cardView.addView(text("Place could not be decoded", 14, true, TEXT), lp(-1, -2));
        TextView detail = text(message, 11, false, MUTED);
        detail.setPadding(0, dp(6), 0, 0);
        cardView.addView(detail, lp(-1, -2));
        propertyList.addView(cardView, lp(-1, -2));
    }

    // ------------------------------------------------------------------ explorer
    private void buildTree() {
        if (explorerList == null) return;
        explorerList.removeAllViews();
        if (loading || loadError != null) { explorerList.addView(hintText(loadError != null ? "No tree available." : "Reading instances…")); return; }
        String query = explorerSearch == null ? "" : explorerSearch.getText().toString().trim().toLowerCase(Locale.US);
        addNodes(explorerList, scene.root, 0, query);
        int total = 0;
        for (Instance ignored : scene.root.all(null)) total++;
        explorerCount.setText(String.valueOf(total));
    }

    private boolean matches(Instance node, String query) {
        if (query.isEmpty()) return true;
        if (node.name.toLowerCase(Locale.US).contains(query) || node.className.toLowerCase(Locale.US).contains(query)) return true;
        for (Instance child : node.children) if (matches(child, query)) return true;
        return false;
    }

    private void addNodes(LinearLayout parent, Instance node, int depth, String query) {
        for (Instance child : node.children) {
            if (!matches(child, query)) continue;
            parent.addView(treeRow(child, depth, query));
            boolean open = expanded.contains(child) && !collapsed.contains(child);
            if (query.length() > 0) open = true;
            if (open && !child.children.isEmpty()) addNodes(parent, child, depth + 1, query);
        }
    }

    private View treeRow(Instance node, int depth, String query) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4 + depth * 12), 0, dp(6), 0);
        boolean selected = node == scene.selection;
        row.setBackground(selected ? shape(SOFT, 6) : null);
        if (!node.children.isEmpty()) {
            boolean open = expanded.contains(node) && !collapsed.contains(node);
            View chevron = iconButton(open ? "chevron" : "arrow", open ? "Collapse" : "Expand", () -> {
                if (open) { collapsed.add(node); expanded.remove(node); } else { expanded.add(node); collapsed.remove(node); }
                buildTree();
            });
            row.addView(chevron, lp(dp(18), dp(18)));
        } else {
            row.addView(new View(this), lp(dp(18), dp(1)));
        }
        row.addView(new StudioIcon(this, iconFor(node), selected ? ACCENT : MUTED), lp(dp(14), dp(14)));
        LinearLayout words = row();
        words.setGravity(Gravity.CENTER_VERTICAL);
        TextView namePart = text(node.name, 11.5f, selected, selected ? ACCENT : TEXT);
        namePart.setSingleLine(true);
        namePart.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(namePart, lp(0, -2, 1));
        TextView cls = text(node.className, 9.5f, false, MUTED);
        cls.setSingleLine(true);
        cls.setPadding(dp(6), 0, 0, 0);
        cls.setMaxWidth(dp(74));
        cls.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(cls);
        row.addView(words, margin(dp(7), 0, 0, -2, 1));
        row.setOnClickListener(v -> { scene.selection = node; refresh(); });
        row.setOnLongClickListener(v -> { nodeMenu(v, node); return true; });
        LinearLayout.LayoutParams params = lp(-1, dp(26));
        row.setLayoutParams(params);
        return row;
    }

    private String iconFor(Instance node) {
        switch (node.className) {
            case "Part": case "WedgePart": case "TrussPart": case "MeshPart": case "UnionOperation": case "Seat": case "VehicleSeat": return "cube";
            case "SpawnLocation": return "spawn";
            case "Model": case "Humanoid": case "StarterPlayerScripts": return "model";
            case "Folder": return "folder";
            case "Script": case "LocalScript": case "ModuleScript": return "script";
            case "PointLight": case "SpotLight": case "SurfaceLight": return "light";
            case "Camera": return "camera";
            case "Terrain": return "terrain";
            case "Sound": return "sound";
            case "ScreenGui": case "Frame": case "TextLabel": case "TextButton": case "BillboardGui": case "SurfaceGui": return "gui";
            default: return node.isService() ? "service" : "cube";
        }
    }

    private void nodeMenu(View anchor, Instance node) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Rename");
        menu.getMenu().add("Duplicate");
        menu.getMenu().add("Focus");
        if (!node.isService()) menu.getMenu().add("Delete");
        if (node.isRenderable()) { menu.getMenu().add("Anchor / Unanchor"); menu.getMenu().add("CanCollide on/off"); }
        menu.setOnMenuItemClickListener(item -> {
            String label = item.getTitle().toString();
            if (label.equals("Rename")) { renameDialog(node); return true; }
            if (label.equals("Duplicate")) { scene.selection = node; scene.duplicateSelection(); afterEdit(); return true; }
            if (label.equals("Focus")) { scene.selection = node; scene.focus(node); refresh(); return true; }
            if (label.equals("Delete")) { scene.selection = node; scene.deleteSelection(); afterEdit(); return true; }
            if (label.equals("Anchor / Unanchor")) { scene.selection = node; scene.setProperty(node, "Anchored", !node.getBool("Anchored", false)); afterEdit(); return true; }
            if (label.equals("CanCollide on/off")) { scene.selection = node; scene.setProperty(node, "CanCollide", !node.getBool("CanCollide", true)); afterEdit(); return true; }
            return false;
        });
        menu.show();
    }

    private void renameDialog(Instance node) {
        EditText field = input(node.name);
        new AlertDialog.Builder(this)
                .setTitle("Rename instance")
                .setView(field)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", (d, w) -> { scene.setProperty(node, "Name", field.getText().toString()); afterEdit(); })
                .show();
    }

    // ------------------------------------------------------------------ properties
    private void fillProperties() {
        if (propertyList == null) return;
        propertyList.removeAllViews();
        if (loading || loadError != null) return;
        Instance node = scene.selection;
        if (node == null) {
            propertyList.addView(hintText("Tap a part in the viewport or pick an instance in Explorer to edit its properties."));
            if (scene.workspace != null) {
                propertyList.addView(sectionLabel("Workspace"));
                propertyList.addView(checkRow("StreamingEnabled", scene.workspace.getBool("StreamingEnabled", false), checked -> { scene.setProperty(scene.workspace, "StreamingEnabled", checked); afterEdit(); }));
                propertyList.addView(numberRow("Gravity", scene.workspace.getNumber("Gravity", 196.2), value -> { scene.setProperty(scene.workspace, "Gravity", value); scene.grav(); afterEdit(); }));
            }
            for (String warning : scene.unmodeledWarnings) propertyList.addView(hintText(warning));
            return;
        }
        LinearLayout head = col();
        head.setPadding(0, 0, 0, dp(6));
        head.addView(badge(node.className), lp(-2, -2));
        propertyList.addView(head, lp(-1, -2));
        propertyList.addView(textRow("Name", node.name, value -> { scene.setProperty(node, "Name", value); afterEdit(); }));
        if (node == scene.workspace) {
            propertyList.addView(checkRow("StreamingEnabled", node.getBool("StreamingEnabled", false), checked -> { scene.setProperty(node, "StreamingEnabled", checked); afterEdit(); }));
        }
        if (node.isRenderable()) {
            double[] c = node.cframe();
            propertyList.addView(sectionLabel("Transform"));
            propertyList.addView(vectorRow("Position", new double[]{c[0], c[1], c[2]}, v -> {
                double[] next = node.cframe();
                next[0] = v[0]; next[1] = v[1]; next[2] = v[2];
                scene.setProperty(node, "CFrame", next);
                afterEdit();
            }));
            double[] s = node.size();
            propertyList.addView(vectorRow("Size", s, v -> {
                scene.setProperty(node, "size", new Instance.Vector3(Math.max(0.05, v[0]), Math.max(0.05, v[1]), Math.max(0.05, v[2])));
                afterEdit();
            }));
            double yaw = Math.toDegrees(Math.atan2(c[5], c[11]));
            propertyList.addView(rotationRow(yaw, delta -> {
                scene.rotateSelection(delta);
                afterEdit();
            }));
            propertyList.addView(sectionLabel("Appearance"));
            propertyList.addView(colorRow(node.getColor(), color -> { scene.setProperty(node, "Color3uint8", color); afterEdit(); }));
            propertyList.addView(sliderRow("Transparency", node.getNumber("Transparency", 0), value -> { scene.setProperty(node, "Transparency", value); afterEdit(); }));
            propertyList.addView(pickerRow("Material", materialName(node.getInt("Material", 256)), () -> materialDialog(node)));
        }
        if (node.isRenderable() || node.className.equals("SpawnLocation")) {
            propertyList.addView(sectionLabel("Behavior"));
            propertyList.addView(checkRow("Anchored", node.getBool("Anchored", false), checked -> { scene.setProperty(node, "Anchored", checked); afterEdit(); }));
            propertyList.addView(checkRow("CanCollide", node.getBool("CanCollide", true), checked -> { scene.setProperty(node, "CanCollide", checked); afterEdit(); }));
            if (node.className.equals("SpawnLocation")) propertyList.addView(checkRow("Enabled", node.getBool("Enabled", true), checked -> { scene.setProperty(node, "Enabled", checked); afterEdit(); }));
        }
        propertyList.addView(sectionLabel("Instance"));
        propertyList.addView(readonlyRow("Class", node.className));
        propertyList.addView(readonlyRow("Children", String.valueOf(node.children.size())));
        for (String warning : scene.unmodeledWarnings) propertyList.addView(hintText(warning));
    }

    private interface CommitText { void set(String value); }
    private interface CommitNumber { void set(double value); }
    private interface CommitVector { void set(double[] value); }
    private interface CommitBoolean { void set(boolean value); }
    private interface CommitColor { void set(int color); }
    private interface CommitRotation { void set(int quarterTurns); }

    private View textRow(String label, String value, CommitText commit) {
        EditText field = input(value);
        field.setOnEditorActionListener((v, action, event) -> { commit.set(field.getText().toString()); return true; });
        field.setOnFocusChangeListener((v, has) -> { if (!has) commit.set(field.getText().toString()); });
        return labeled(label, field);
    }

    private View readonlyRow(String label, String value) {
        TextView view = text(value, 11, false, MUTED);
        return labeled(label, view);
    }

    private View numberRow(String label, double value, CommitNumber commit) {
        EditText field = numberField(value);
        field.setOnEditorActionListener((v, action, event) -> { commit.set(parse(field, value)); return true; });
        field.setOnFocusChangeListener((v, has) -> { if (!has) commit.set(parse(field, value)); });
        return labeled(label, field);
    }

    private View vectorRow(String label, double[] values, CommitVector commit) {
        LinearLayout box = col();
        box.setPadding(0, dp(3), 0, dp(3));
        box.addView(text(label, 10, false, MUTED), lp(-1, -2));
        LinearLayout fields = row();
        String[] axis = {"X", "Y", "Z"};
        LinearLayout[] holders = new LinearLayout[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout holder = row();
            holder.setGravity(Gravity.CENTER_VERTICAL);
            holder.setPadding(dp(4), 0, dp(4), 0);
            holder.setBackground(shape(CANVAS, 6));
            holder.addView(text(axis[i], 9, true, MUTED), margin(0, 0, -2, -2));
            EditText field = numberField(values[i]);
            field.setBackground(null);
            field.setOnEditorActionListener((v, action, event) -> { commit.set(readVector(holders, values)); return true; });
            field.setOnFocusChangeListener((v, has) -> { if (!has) commit.set(readVector(holders, values)); });
            holder.addView(field, lp(0, dp(26), 1));
            holders[i] = holder;
            fields.addView(holder, i < 2 ? margin(i == 0 ? 0 : dp(4), 0, 0, dp(26), 1) : margin(dp(4), 0, 0, dp(26), 1));
        }
        box.addView(fields, lp(-1, dp(26)));
        return box;
    }

    private double[] readVector(LinearLayout[] holders, double[] fallback) {
        double[] out = fallback.clone();
        for (int i = 0; i < 3; i++) {
            EditText field = (EditText) holders[i].getChildAt(1);
            try { out[i] = Double.parseDouble(field.getText().toString().trim()); } catch (Exception ignored) { }
        }
        return out;
    }

    private View rotationRow(double yaw, CommitRotation commit) {
        LinearLayout box = row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(3), 0, dp(3));
        box.addView(text("Rotation Y", 10, false, MUTED), lp(0, -2, 1));
        TextView readout = text(((int) Math.round(yaw)) + "°", 11, true, TEXT);
        readout.setPadding(dp(6), 0, dp(6), 0);
        box.addView(readout);
        box.addView(smallButton("-90°", () -> commit.set(-1)), lp(-2, dp(26)));
        box.addView(smallButton("+90°", () -> commit.set(1)), margin(dp(4), 0, -2, dp(26)));
        return box;
    }

    private View colorRow(int color, CommitColor commit) {
        LinearLayout box = row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(3), 0, dp(3));
        box.addView(text("Color", 10, false, MUTED), lp(0, -2, 1));
        View swatch = new View(this);
        swatch.setBackground(shape(0xff000000 | color, 5));
        swatch.setContentDescription("Pick color");
        swatch.setOnClickListener(v -> colorDialog(color, chosen -> { commit.set(chosen); }));
        box.addView(swatch, lp(dp(30), dp(24)));
        return box;
    }

    private View sliderRow(String label, double value, CommitNumber commit) {
        LinearLayout box = col();
        box.setPadding(0, dp(3), 0, dp(3));
        LinearLayout top = row();
        top.addView(text(label, 10, false, MUTED), lp(0, -2, 1));
        final TextView readout = text(Math.round(value * 100) + "%", 10, true, TEXT);
        top.addView(readout);
        box.addView(top, lp(-1, -2));
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress((int) Math.round(value * 100));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { readout.setText(progress + "%"); }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { commit.set(seekBar.getProgress() / 100.0); }
        });
        box.addView(bar, lp(-1, dp(30)));
        return box;
    }

    private View pickerRow(String label, String value, Runnable open) {
        LinearLayout box = row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(3), 0, dp(3));
        box.addView(text(label, 10, false, MUTED), lp(0, -2, 1));
        TextView buttonView = text(value, 11, false, ACCENT);
        buttonView.setPadding(dp(8), dp(4), dp(8), dp(4));
        buttonView.setBackground(shape(SOFT, 6));
        buttonView.setOnClickListener(v -> open.run());
        box.addView(buttonView);
        return box;
    }

    private View checkRow(String label, boolean checked, CommitBoolean commit) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(11 * ui());
        box.setTextColor(TEXT);
        box.setChecked(checked);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        box.setOnCheckedChangeListener((v, value) -> commit.set(value));
        return box;
    }

    private View labeled(String label, View field) {
        LinearLayout box = col();
        box.setPadding(0, dp(3), 0, dp(3));
        box.addView(text(label, 10, false, MUTED), lp(-1, -2));
        box.addView(field, lp(-1, -2));
        return box;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 10, true, MUTED);
        view.setPadding(0, dp(10), 0, dp(2));
        return view;
    }

    private TextView hintText(String value) {
        TextView view = text(value, 10.5f, false, MUTED);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private void materialDialog(Instance node) {
        final String[] names = {"Plastic", "SmoothPlastic", "Neon", "Wood", "WoodPlanks", "Metal", "DiamondPlate", "Concrete", "Brick", "Glass", "Fabric", "Ice", "Marble", "Granite", "Grass", "Sand", "Slate", "Cobblestone"};
        final int[] values = {256, 288, 272, 784, 800, 1088, 1072, 816, 848, 864, 1312, 1360, 832, 880, 1280, 1296, 800, 880};
        new AlertDialog.Builder(this).setTitle("Material")
                .setItems(names, (d, which) -> { scene.setProperty(node, "Material", values[which]); afterEdit(); })
                .show();
    }

    private String materialName(int value) {
        switch (value) {
            case 288: return "SmoothPlastic";
            case 272: return "Neon";
            case 784: return "Wood";
            case 800: return "WoodPlanks";
            case 1088: return "Metal";
            case 816: return "Concrete";
            case 848: return "Brick";
            case 864: return "Glass";
            case 1312: return "Fabric";
            case 1280: return "Grass";
            default: return "Plastic";
        }
    }

    private void colorDialog(int current, CommitColor commit) {
        final int[] palette = {
                0xF2F3F3, 0xA3A2A5, 0x635F62, 0x1B2A34, 0xFFFFFF, 0xF5CD30, 0xE8AB2E, 0xCF8B17,
                0xDA8670, 0xA03B2B, 0xC4281C, 0xFF0000, 0xFF7B7B, 0xFF98DC, 0xE1007B, 0x9F4BFF,
                0x6225C8, 0x0D69D1, 0x1B2A34, 0x00A2FF, 0x4B9CD3, 0x008F9C, 0x2BBF4B, 0x00FF00,
                0xA1C13B, 0xCDE000, 0xF8F184, 0xE2D2A1, 0xB08C4C, 0x6E4B2E, 0x2E2C2B, 0x000000
        };
        LinearLayout grid = col();
        grid.setPadding(dp(12), dp(12), dp(12), dp(4));
        LinearLayout line = null;
        for (int i = 0; i < palette.length; i++) {
            if (i % 8 == 0) { line = row(); grid.addView(line, lp(-1, dp(40))); }
            View swatch = new View(this);
            swatch.setBackground(shape(0xff000000 | palette[i], 6));
            final int chosen = palette[i];
            swatch.setOnClickListener(v -> { commit.set(chosen); });
            line.addView(swatch, i % 8 == 0 ? margin(0, 0, 0, dp(34), 1) : margin(dp(6), 0, 0, dp(34), 1));
        }
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(grid);
        LinearLayout hexBox = row();
        hexBox.setPadding(dp(12), 0, dp(12), dp(12));
        EditText hex = input(String.format(Locale.US, "%06X", current));
        hex.setHint("RRGGBB");
        hexBox.addView(hex, lp(0, dp(32), 1));
        TextView apply = smallButton("Apply", () -> {
            try {
                int value = (int) Long.parseLong(hex.getText().toString().trim(), 16);
                commit.set(value & 0xFFFFFF);
            } catch (Exception ignored) { }
            dialog.dismiss();
        });
        hexBox.addView(apply, margin(dp(6), 0, -2, dp(32)));
        grid.addView(hexBox, lp(-1, dp(32)));
        dialog.show();
        if (dialog.getWindow() != null) { dialog.getWindow().setBackgroundDrawable(shape(PANEL2, 12)); dialog.getWindow().setLayout(Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(40)), -2); }
    }

    // ------------------------------------------------------------------ tools
    private View toolButton(String id, String icon, String description) {
        FrameLayout button = new FrameLayout(this);
        boolean active = tool.equals(id);
        button.setBackground(active ? shape(ACCENT, 6) : shape(0x00000000, 6));
        button.setContentDescription(description);
        button.addView(new StudioIcon(this, icon, active ? 0xffffffff : 0xffd0d6e0), new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
        button.setOnClickListener(v -> toolAction(id));
        return button;
    }

    private void toolAction(String id) {
        switch (id) {
            case "insert": insertDialog(); return;
            case "duplicate": scene.duplicateSelection(); afterEdit(); return;
            case "delete": scene.deleteSelection(); afterEdit(); return;
            case "focus": if (scene.selection != null) { scene.focus(scene.selection); refresh(); } return;
            default: tool = id; buildToolPad(); refreshViewportTools(); return;
        }
    }

    private void refreshViewportTools() {
        if (toolStrip != null) {
            LinearLayout strip = toolStrip;
            String[] ids = {"select", "move", "rotate", "scale", "insert", "duplicate", "delete", "focus"};
            String[] icons = {"arrow", "move", "rotate", "scale", "cube", "copy", "trash", "search"};
            String[] descriptions = {"Select", "Move", "Rotate", "Scale", "Insert", "Duplicate", "Delete", "Focus selection"};
            for (int i = 0; i < strip.getChildCount() && i < ids.length; i++) {
                View child = strip.getChildAt(i);
                if (!(child instanceof FrameLayout)) continue;
                boolean active = tool.equals(ids[i]);
                child.setBackground(active ? shape(ACCENT, 6) : shape(0x00000000, 6));
                ((FrameLayout) child).removeAllViews();
                ((FrameLayout) child).addView(new StudioIcon(this, icons[i], active ? 0xffffffff : 0xffd0d6e0), new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
                final String id = ids[i];
                child.setOnClickListener(v -> toolAction(id));
            }
        }
    }

    private void buildToolPad() {
        toolPad.removeAllViews();
        if (tool.equals("select")) { toolPad.setVisibility(View.GONE); return; }
        toolPad.setVisibility(View.VISIBLE);
        if (tool.equals("move") || tool.equals("scale")) {
            boolean scale = tool.equals("scale");
            TextView label = text(scale ? "Scale 10%" : "Move " + step + " studs", 10, true, 0xffffffff);
            label.setPadding(dp(4), 0, dp(4), dp(4));
            toolPad.addView(label);
            LinearLayout line1 = row();
            line1.addView(padButton("Y+", () -> padAction(1, 1, scale)), lp(dp(40), dp(28)));
            line1.addView(padButton("Y-", () -> padAction(1, -1, scale)), margin(dp(4), 0, dp(40), dp(28)));
            toolPad.addView(line1, lp(-2, dp(28)));
            LinearLayout line2 = row();
            line2.addView(padButton("X-", () -> padAction(0, -1, scale)), lp(dp(40), dp(28)));
            line2.addView(padButton("X+", () -> padAction(0, 1, scale)), margin(dp(4), 0, dp(40), dp(28)));
            line2.addView(padButton("Z-", () -> padAction(2, -1, scale)), margin(dp(4), 0, dp(40), dp(28)));
            line2.addView(padButton("Z+", () -> padAction(2, 1, scale)), margin(dp(4), 0, dp(40), dp(28)));
            toolPad.addView(line2, lp(-2, dp(28)));
            if (!scale) {
                LinearLayout line3 = row();
                line3.addView(padButton("0.5", () -> { step = 0.5; buildToolPad(); }), lp(dp(40), dp(24)));
                line3.addView(padButton("1", () -> { step = 1; buildToolPad(); }), margin(dp(4), 0, dp(40), dp(24)));
                line3.addView(padButton("4", () -> { step = 4; buildToolPad(); }), margin(dp(4), 0, dp(40), dp(24)));
                toolPad.addView(line3, lp(-2, dp(24)));
            }
        } else if (tool.equals("rotate")) {
            LinearLayout line1 = row();
            line1.addView(padButton("-90° Y", () -> { scene.rotateSelection(-1); afterEdit(); }), lp(dp(60), dp(28)));
            line1.addView(padButton("+90° Y", () -> { scene.rotateSelection(1); afterEdit(); }), margin(dp(4), 0, dp(60), dp(28)));
            toolPad.addView(line1, lp(-2, dp(28)));
        }
    }

    private void padAction(int axis, int direction, boolean scale) {
        if (scale) scene.scaleSelection(axis, direction > 0 ? 1.1 : 1 / 1.1);
        else scene.nudge(axis, direction * step);
        afterEdit();
    }

    private TextView padButton(String label, Runnable action) {
        TextView button = text(label, 11, true, 0xffffffff);
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(0x33ffffff, 6));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void insertDialog() {
        new AlertDialog.Builder(this).setTitle("Insert object")
                .setItems(Instance.INSERTABLE, (d, which) -> {
                    Instance created = scene.insert(Instance.INSERTABLE[which]);
                    expanded.add(created.parent == null ? scene.workspace : created.parent);
                    afterEdit();
                }).show();
    }

    // ------------------------------------------------------------------ play
    private void startPlay() {
        scene.startPlay();
        playControls.setVisibility(View.VISIBLE);
        toolPad.setVisibility(View.GONE);
        playLabel.setText("Stop");
        subtitle.setText("Playing locally — drag to look, joystick to move");
        scene.refreshCache();
    }

    private void stopPlay() {
        scene.stopPlay();
        playControls.setVisibility(View.GONE);
        buildToolPad();
        playLabel.setText("Play");
        subtitle.setText("Edit mode — changes are local until you save");
        scene.refreshCache();
    }

    // ------------------------------------------------------------------ persistence
    private void moreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Focus selection");
        menu.getMenu().add("Select spawn point");
        menu.getMenu().add("Reset camera");
        menu.getMenu().add("Engine limits…");
        menu.setOnMenuItemClickListener(item -> {
            String label = item.getTitle().toString();
            if (label.startsWith("Focus") && scene.selection != null) { scene.focus(scene.selection); refresh(); }
            else if (label.startsWith("Select spawn")) { Instance spawn = scene.root.findFirst("SpawnLocation"); if (spawn != null) { scene.selection = spawn; scene.focus(spawn); refresh(); } }
            else if (label.startsWith("Reset camera")) { scene.camTarget[0] = 0; scene.camTarget[1] = 4; scene.camTarget[2] = 0; scene.camDist = 34; scene.camYaw = Math.toRadians(35); scene.camPitch = Math.toRadians(-30); }
            else if (label.startsWith("Engine limits")) limitsDialog();
            return true;
        });
        menu.show();
    }

    private void limitsDialog() {
        new AlertDialog.Builder(this).setTitle("Engine limits in this build")
                .setMessage("Solid parts (Part, SpawnLocation, Wedge, Truss, MeshPart) render as boxes with their real size, CFrame and color.\n\n" +
                        "Scripts do not run yet. Unions/Meshes use bounding boxes. Rotated parts are collided against with axis-aligned boxes. " +
                        "Terrain, textures, particles and animations are not rendered in this beta.\n\n" +
                        "Saving writes the Roblox XML data model back to this place file; publish from the project page to sync with Roblox.")
                .setPositiveButton("Got it", null).show();
    }

    private void save(boolean notify) {
        if (loading || loadError != null || placeUri == null) return;
        subtitle.setText("Saving place…");
        final String xml;
        try {
            xml = PlaceWriter.generate(scene.root);
        } catch (Exception e) {
            subtitle.setText("Save failed: " + RobloxApi.safe(e));
            return;
        }
        final int versionAtSave = scene.version;
        worker.execute(() -> {
            try {
                byte[] bytes = xml.getBytes("UTF-8");
                write(bytes);
                updateManifest(bytes);
                runOnUiThread(() -> {
                    savedVersion = versionAtSave;
                    subtitle.setText("Saved  ·  " + (bytes.length / 1024) + " KiB  ·  " + scene.root.all(null).size() + " instances");
                    if (notify) Toast.makeText(this, "Place saved on device", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> subtitle.setText("Save failed: " + RobloxApi.safe(e)));
            }
        });
    }

    private void write(byte[] bytes) throws Exception {
        if ("file".equals(placeUri.getScheme())) {
            File destination = new File(placeUri.getPath());
            File tmp = new File(destination.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
            if (!tmp.renameTo(destination)) { tmp.delete(); throw new IOException("Could not commit the saved place."); }
        } else {
            try (OutputStream out = getContentResolver().openOutputStream(placeUri, "wt")) {
                if (out == null) throw new IOException("The place file is not writable through this provider.");
                out.write(bytes);
            }
        }
    }

    private void updateManifest(byte[] bytes) {
        if (folderUri == null || placeId == null) return;
        try {
            ManagedProjects repo = new ManagedProjects(this, new ProjectStore(this));
            ManagedProjects.Record record = repo.load(folderUri);
            for (int i = 0; i < record.places().length(); i++) {
                JSONObject place = record.places().getJSONObject(i);
                if (placeId.equals(place.optString("id"))) {
                    place.put("sha256", ManagedProjects.hash(bytes));
                    repo.save(record);
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    private InputStream open(Uri uri) throws Exception {
        if ("file".equals(uri.getScheme())) return new FileInputStream(uri.getPath());
        return getContentResolver().openInputStream(uri);
    }

    // ------------------------------------------------------------------ refresh
    private void afterEdit() {
        scene.refreshCache();
        refresh();
    }

    private void refresh() {
        buildTree();
        fillProperties();
        scene.refreshCache();
    }

    // ------------------------------------------------------------------ widgets
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private View line() { View v = new View(this); v.setBackgroundColor(BORDER); return v; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }
    private LinearLayout.LayoutParams margin(int left, int top, int w, int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.leftMargin = left; p.topMargin = top; return p; }
    private LinearLayout.LayoutParams margin(int left, int top, int w, int h, float weight) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight); p.leftMargin = left; p.topMargin = top; return p; }

    private TextView text(String value, float size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size * ui());
        t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.create("sans-serif" + (bold ? "-medium" : ""), 0));
        return t;
    }

    private TextView button(String value, Runnable action) {
        TextView t = text(value, 12, true, 0xffffffff);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(shape(ACCENT, 8));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private TextView smallButton(String value, Runnable action) {
        TextView t = text(value, 11, true, ACCENT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        t.setBackground(shape(SOFT, 6));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private View iconButton(String icon, String description, Runnable action) {
        FrameLayout button = new FrameLayout(this);
        button.setContentDescription(description);
        button.addView(new StudioIcon(this, icon, dark ? 0xffd0d6e0 : 0xff4a5364), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private View roundButton(String label, Runnable action) {
        TextView t = text(label, 12, true, 0xffffffff);
        t.setGravity(Gravity.CENTER);
        t.setBackground(shape(0xcc2d56e8, 40));
        t.setContentDescription(label);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private EditText input(String value) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setTextSize(11.5f * ui());
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setSingleLine(true);
        field.setPadding(dp(6), dp(4), dp(6), dp(4));
        field.setBackground(shape(CANVAS, 6));
        return field;
    }

    private EditText numberField(double value) {
        EditText field = input(PlaceWriter.num(value));
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return field;
    }

    private static double parse(EditText field, double fallback) {
        try { return Double.parseDouble(field.getText().toString().trim()); } catch (Exception ignored) { return fallback; }
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView badge(String value) {
        TextView t = text(value, 10, true, ACCENT);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        t.setBackground(shape(SOFT, 5));
        return t;
    }
}
