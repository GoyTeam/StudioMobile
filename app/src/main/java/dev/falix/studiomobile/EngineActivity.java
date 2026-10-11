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
    private LinearLayout explorerList, propertyList, toolStrip;
    private View explorerPanel, propertiesPanel, viewportBox, explorerSeparator, propertiesSeparator;
    private boolean narrowScreen;
    private int explorerWidth, propertiesWidth;
    private TextView title, subtitle, playLabel, explorerCount;
    private EditText explorerSearch;
    private FrameLayout viewportLayer;
    private TextView rendererError;
    private JoystickView joystick, liftPad;
    private View jumpButton;
    private View playControls, flyControls;
    private final HashSet<Instance> expanded = new HashSet<>();
    private final HashSet<Instance> collapsed = new HashSet<>();

    /** Explorer limits: huge places stay responsive and cannot exhaust memory while building rows. */
    private static final int MAX_TREE_ROWS = 400;
    private static final int MAX_TREE_DEPTH = 48;
    private static final int AUTO_EXPAND_LIMIT = 200;
    private int treeBudget = MAX_TREE_ROWS;

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
    private boolean snap;

    /** Opens a place in the 3D editor. Managed project places also update their manifest hash on save. */
    static void launch(android.app.Activity from, Uri placeUri, String placeName, Uri projectFolder, String placeId) {
        Intent intent = new Intent(from, EngineActivity.class);
        // A missing place file is reported inside the editor, never as a crash while launching it.
        if (placeUri != null) intent.putExtra("uri", placeUri.toString());
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
        enterFullscreen();
        try {
            setContentView(build());
            applyCutoutPadding();
            load();
        } catch (Throwable failure) {
            // Constructing the editor must never take the whole app down: show the cause instead.
            startupFailure(failure);
        }
    }

    /** Failure screen used when the editor cannot be built; shows the cause so it can be reported. */
    private void startupFailure(Throwable failure) {
        android.util.Log.e("StudioMobile", "Editor failed to start", failure instanceof Exception ? (Exception) failure : new RuntimeException(failure));
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        loading = false;
        loadError = "Editor could not start";
        LinearLayout root = col();
        root.setBackgroundColor(PANEL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(text("The 3D editor could not start", 15, true, TEXT));
        root.addView(text("This build caught the error below instead of crashing. Report it together with the trace.", 11, false, MUTED));
        TextView detail = text(trace.toString(), 10, false, MUTED);
        detail.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(detail, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, lp(-1, 0, 1));
        root.addView(button("Close", this::finish), margin(0, dp(8), -2, dp(36)));
        setContentView(root);
    }

    /**
     * The editor is a real full-screen workspace: the status and navigation bars are hidden and the
     * sticky-immersive flags bring them back only for an explicit swipe, exactly like Studio's
     * viewport taking the whole screen.
     */
    private void enterFullscreen() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        decor.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) decor.postDelayed(this::enterFullscreen, 1200);
        });
    }

    /** Keeps the UI clear of a display cutout, which the hidden status bar no longer insets for. */
    private void applyCutoutPadding() {
        if (Build.VERSION.SDK_INT < 28) return;
        View root = findViewById(android.R.id.content);
        if (root == null) return;
        try {
            WindowInsets insets = root.getRootWindowInsets();
            android.view.DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
            if (cutout == null) return;
            root.setPadding(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(), cutout.getSafeInsetRight(), cutout.getSafeInsetBottom());
        } catch (Throwable ignored) { }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { enterFullscreen(); applyCutoutPadding(); }
    }

    @Override protected void onResume() { super.onResume(); enterFullscreen(); if (viewport != null) viewport.onResume(); }
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
        // Phone-sized screens get the viewport first: the Explorer and Properties panels become
        // toggles instead of columns, so nothing is squeezed into a few remaining pixels.
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        float density = metrics.density <= 0 ? 1 : metrics.density;
        int widthDp = (int) (metrics.widthPixels / density);
        narrowScreen = widthDp < 700;
        explorerWidth = narrowScreen ? Math.min(dp(210), (int) (metrics.widthPixels * 0.36f)) : dp(226);
        propertiesWidth = narrowScreen ? Math.min(dp(230), (int) (metrics.widthPixels * 0.40f)) : dp(258);

        LinearLayout root = col();
        root.setBackgroundColor(PANEL);
        root.addView(buildTopBar(), lp(-1, dp(48)));
        LinearLayout body = row();
        root.addView(body, lp(-1, 0, 1));
        explorerPanel = buildExplorer();
        propertiesPanel = buildProperties();
        body.addView(explorerPanel, lp(explorerWidth, -1));
        explorerSeparator = line();
        body.addView(explorerSeparator, lp(dp(1), -1));
        viewportBox = buildViewport();
        body.addView(viewportBox, lp(0, -1, 1));
        propertiesSeparator = line();
        body.addView(propertiesSeparator, lp(dp(1), -1));
        body.addView(propertiesPanel, lp(propertiesWidth, -1));

        boolean showExplorer = !narrowScreen;
        explorerPanel.setVisibility(showExplorer ? View.VISIBLE : View.GONE);
        propertiesPanel.setVisibility(showExplorer ? View.VISIBLE : View.GONE);
        return root;
    }

    /** Explorer/Properties are toggles so the viewport can take the whole screen on a phone. */
    private void togglePanel(View panel, View separator) {
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (separator != null) separator.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private View buildTopBar() {
        LinearLayout bar = row();
        bar.setBackgroundColor(PANEL2);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(6), 0);
        int button = dp(42);
        bar.addView(iconButton("arrow", "Back", this::onBackPressed), lp(button, button));
        LinearLayout names = col();
        names.setPadding(dp(8), 0, dp(4), 0);
        title = text(placeName, 13, true, TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = text("Loading place…", 10, false, MUTED);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);
        names.addView(subtitle);
        bar.addView(names, lp(0, -2, 1));
        // Panels are toggles: on a phone the viewport gets the whole width until you ask for one.
        bar.addView(iconButton("layers", "Explorer panel", () -> togglePanel(explorerPanel, explorerSeparator)), lp(button, button));
        bar.addView(iconButton("list", "Properties panel", () -> togglePanel(propertiesPanel, propertiesSeparator)), lp(button, button));
        if (!narrowScreen) {
            bar.addView(iconButton("undo", "Undo", this::undo), lp(button, button));
            bar.addView(iconButton("redo", "Redo", this::redo), lp(button, button));
        }
        LinearLayout playButton = row();
        playButton.setGravity(Gravity.CENTER_VERTICAL);
        playButton.setPadding(dp(narrowScreen ? 8 : 12), 0, dp(narrowScreen ? 8 : 12), 0);
        playButton.setBackground(shape(SOFT, 8));
        playLabel = text("Play", 12, true, ACCENT);
        StudioIcon playIcon = new StudioIcon(this, "play", ACCENT);
        playButton.addView(playIcon, lp(dp(15), dp(15)));
        if (!narrowScreen) playButton.addView(playLabel, margin(8, 0, -2, -2));
        playButton.setOnClickListener(v -> { if (scene.playing) stopPlay(); else startPlay(); });
        bar.addView(playButton, lp(-2, dp(34)));
        bar.addView(iconButton("save", "Save place", () -> save(true)), lp(button, button));
        bar.addView(iconButton("more", "More actions", () -> moreMenu(bar)), lp(button, button));
        return bar;
    }

    private void undo() { scene.undo(); afterEdit(); }

    private void redo() { scene.redo(); afterEdit(); }

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
            public void onRendererError(String message) { rendererFailed(message); }
        });
        viewportLayer.addView(viewport, new FrameLayout.LayoutParams(-1, -1));

        toolStrip = row();
        toolStrip.setPadding(dp(4), dp(3), dp(4), dp(3));
        toolStrip.setBackground(shape(0xcc23272f, 8));
        int toolSize = dp(40);
        toolStrip.addView(toolButton("select", "arrow", "Select"), lp(toolSize, toolSize));
        toolStrip.addView(toolButton("move", "move", "Move tool"), lp(toolSize, toolSize));
        toolStrip.addView(toolButton("rotate", "rotate", "Rotate tool"), lp(toolSize, toolSize));
        toolStrip.addView(toolButton("scale", "scale", "Scale tool"), lp(toolSize, toolSize));
        toolStrip.addView(toolButton("grab", "hand", "Grab tool: drag parts to move them"), lp(toolSize, toolSize));
        toolStrip.addView(toolButton("snap", "grid", "Grid snapping"), lp(toolSize, toolSize));
        toolStrip.addView(line(), margin(dp(3), dp(6), dp(1), dp(26)));
        toolStrip.addView(toolButton("insert", "cube", "Insert object"), lp(toolSize, toolSize));
        toolStrip.addView(toolButton("duplicate", "copy", "Duplicate"), lp(toolSize, toolSize));
        toolStrip.addView(toolButton("delete", "trash", "Delete"), lp(toolSize, toolSize));
        toolStrip.addView(toolButton("focus", "search", "Focus selection"), lp(toolSize, toolSize));
        // The strip scrolls instead of shrinking, so no button is ever squeezed on a phone.
        HorizontalScrollView toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        toolScroll.setClipToPadding(false);
        toolScroll.addView(toolStrip, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams toolParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        toolParams.setMargins(dp(8), dp(8), dp(8), 0);
        viewportLayer.addView(toolScroll, toolParams);

        viewportLayer.addView(buildFlyControls(), new FrameLayout.LayoutParams(-1, -1));

        playControls = buildPlayControls();
        playControls.setVisibility(View.GONE);
        viewportLayer.addView(playControls, new FrameLayout.LayoutParams(-1, -1));

        rendererError = text("", 10.5f, false, 0xffffffff);
        rendererError.setBackground(shape(0xcc7a2118, 8));
        rendererError.setPadding(dp(10), dp(8), dp(10), dp(8));
        rendererError.setVisibility(View.GONE);
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        errorParams.setMargins(0, dp(8), dp(8), 0);
        viewportLayer.addView(rendererError, errorParams);
        return viewportLayer;
    }

    /** Shows why rendering stopped instead of letting a GL-thread failure kill the process. */
    private void rendererFailed(String message) {
        if (rendererError == null) return;
        rendererError.setText("Rendering stopped\n" + message + "\nEditing still works; reopen the place to retry.");
        rendererError.setVisibility(View.VISIBLE);
    }

/**
     * Fly controls for the editor camera, Studio's WASD/QE replacement: the left stick flies along
     * the direction the camera looks (so looking up and pushing forward climbs) and the small pad
     * beside it climbs or descends without changing where you look. The same stick walks the
     * character in play mode.
     */
    private View buildFlyControls() {
        LinearLayout layer = row();
        joystick = new JoystickView(this);
        joystick.setContentDescription("Fly the editor camera");
        joystick.setListener((x, y) -> {
            if (scene.playing) { scene.moveX = x; scene.moveZ = y; }
            else { scene.flyX = x; scene.flyZ = y; }
        });
        layer.addView(joystick, lp(dp(128), dp(128)));
        liftPad = new JoystickView(this);
        liftPad.setContentDescription("Camera up and down");
        liftPad.setListener((x, y) -> { if (!scene.playing) scene.flyUp = y; });
        layer.addView(liftPad, margin(dp(10), 0, dp(58), dp(128)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        params.setMargins(dp(14), 0, 0, dp(14));
        layer.setLayoutParams(params);
        flyControls = layer;
        return layer;
    }

    private View buildPlayControls() {
        FrameLayout layer = new FrameLayout(this);
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
                    try {
                        scene.setRoot(result.root, placeName);
                        scene.warnings.addAll(result.warnings);
                        for (String warning : result.warnings) scene.unmodeledWarnings.add(warning);
                        loading = false;
                        int count = 0;
                        int parts = 0;
                        for (Instance i : scene.root.all(null)) { count++; if (i.isRenderable()) parts++; }
                        subtitle.setText(result.format + "  ·  " + count + " instances  ·  " + parts + " parts");
                        // Only auto-expand containers small enough to stay smooth in the tree.
                        for (Instance child : scene.root.children) if (child.children.size() <= AUTO_EXPAND_LIMIT) expanded.add(child);
                        if (scene.workspace != null && scene.workspace.children.size() <= AUTO_EXPAND_LIMIT) expanded.add(scene.workspace);
                        Instance first = scene.root.findFirst("SpawnLocation");
                        if (first != null) { scene.selection = first; scene.focus(first); }
                        refresh();
                        savedVersion = scene.version;
                        if (autoPlay) startPlay();
                    } catch (Throwable failure) {
                        fail(RobloxApi.safe(failure));
                    }
                });
            } catch (Throwable e) {
                final String message = RobloxApi.safe(e);
                runOnUiThread(() -> { try { fail(message); } catch (Throwable ignored) { } });
            } finally { if (tmp != null) tmp.delete(); }
        });
    }

    private void fail(String message) {
        loading = false;
        loadError = message;
        subtitle.setText("Could not open this place");
        propertyList.removeAllViews();
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
        treeBudget = MAX_TREE_ROWS;
        addNodes(explorerList, scene.root, 0, query);
        int total = 0;
        for (Instance ignored : scene.root.all(null)) total++;
        if (treeBudget <= 0) explorerList.addView(hintText("Showing the first " + MAX_TREE_ROWS + " of " + total + " instances. Filter or collapse branches to see the rest."));
        explorerCount.setText(String.valueOf(total));
    }

    private boolean matches(Instance node, String query) {
        if (query.isEmpty()) return true;
        return matches(node, query, 0);
    }

    private boolean matches(Instance node, String query, int depth) {
        if (depth >= Instance.MAX_DEPTH) return false;
        if (node.name.toLowerCase(Locale.US).contains(query) || node.className.toLowerCase(Locale.US).contains(query)) return true;
        for (Instance child : node.children) if (matches(child, query, depth + 1)) return true;
        return false;
    }

    private void addNodes(LinearLayout parent, Instance node, int depth, String query) {
        if (depth > MAX_TREE_DEPTH) return;
        for (Instance child : node.children) {
            if (treeBudget <= 0) return;
            if (!matches(child, query)) continue;
            parent.addView(treeRow(child, depth, query));
            treeBudget--;
            boolean open = expanded.contains(child) && !collapsed.contains(child);
            if (query.length() > 0) open = true;
            if (open && !child.children.isEmpty()) addNodes(parent, child, depth + 1, query);
        }
    }

    private View treeRow(Instance node, int depth, String query) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4 + depth * 14), 0, dp(6), 0);
        boolean selected = node == scene.selection;
        row.setBackground(selected ? shape(SOFT, 6) : null);
        if (!node.children.isEmpty()) {
            boolean open = expanded.contains(node) && !collapsed.contains(node);
            View chevron = iconButton(open ? "chevron" : "arrow", open ? "Collapse" : "Expand", () -> {
                if (open) { collapsed.add(node); expanded.remove(node); } else { expanded.add(node); collapsed.remove(node); }
                buildTree();
            });
            row.addView(chevron, lp(dp(24), dp(30)));
        } else {
            row.addView(new View(this), lp(dp(24), dp(1)));
        }
        row.addView(new StudioIcon(this, iconFor(node), selected ? ACCENT : MUTED), lp(dp(15), dp(15)));
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
        LinearLayout.LayoutParams params = lp(-1, dp(30));
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
        menu.getMenu().add("Group in Folder");
        menu.getMenu().add("Focus");
        if (!node.isService()) menu.getMenu().add("Delete");
        if (node.isRenderable()) { menu.getMenu().add("Anchor / Unanchor"); menu.getMenu().add("CanCollide on/off"); }
        menu.setOnMenuItemClickListener(item -> {
            String label = item.getTitle().toString();
            if (label.equals("Rename")) { renameDialog(node); return true; }
            if (label.equals("Duplicate")) { scene.selection = node; scene.duplicateSelection(); afterEdit(); return true; }
            if (label.equals("Group in Folder")) { groupInFolder(node); return true; }
            if (label.equals("Focus")) { scene.selection = node; scene.focus(node); refresh(); return true; }
            if (label.equals("Delete")) { scene.selection = node; scene.deleteSelection(); afterEdit(); return true; }
            if (label.equals("Anchor / Unanchor")) { scene.selection = node; scene.setProperty(node, "Anchored", !node.getBool("Anchored", false)); afterEdit(); return true; }
            if (label.equals("CanCollide on/off")) { scene.selection = node; scene.setProperty(node, "CanCollide", !node.getBool("CanCollide", true)); afterEdit(); return true; }
            return false;
        });
        menu.show();
    }

    /** Studio's "Group selection": one Folder holds the instance so the tree stays tidy. */
    private void groupInFolder(Instance node) {
        if (node == null || node.parent == null || node.isService()) return;
        scene.pushUndo();
        Instance parent = node.parent;
        String name = "Folder";
        int n = 1;
        while (parent.childNamed(name) != null) name = "Folder " + (++n);
        Instance folder = new Instance("Folder", name);
        parent.add(folder);
        folder.add(node);
        scene.selection = folder;
        expanded.add(folder);
        scene.markDirty();
        afterEdit();
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
        fillClassProperties(node);
        propertyList.addView(sectionLabel("Instance"));
        propertyList.addView(readonlyRow("Class", node.className));
        propertyList.addView(readonlyRow("Children", String.valueOf(node.children.size())));
        for (String warning : scene.unmodeledWarnings) propertyList.addView(hintText(warning));
    }

    /**
     * Per-class properties, mirroring Studio's panel: the Lighting service and everything it owns,
     * lights, the six BasePart surface types that pick the texture each face shows, streamed asset
     * slots and the SpawnLocation behaviour flags.
     */
    private void fillClassProperties(Instance node) {
        switch (node.className) {
            case "Lighting": lightingRows(node); break;
            case "Sky": skyRows(node); break;
            case "Atmosphere": atmosphereRows(node); break;
            case "Clouds": cloudsRows(node); break;
            case "PointLight": case "SpotLight": case "SurfaceLight": lightRows(node); break;
            case "Decal": case "Texture": textureRows(node); break;
            case "SurfaceAppearance": surfaceAppearanceRows(node); break;
            case "MeshPart": meshRows(node); break;
            default: break;
        }
        if (node.isRenderable()) basePartRows(node);
        if (node == scene.workspace) workspaceRows(node);
    }

    /** Enum.Material plus the per-face SurfaceType pickers that drive the viewport textures. */
    private void basePartRows(Instance node) {
        propertyList.addView(sectionLabel("Surfaces"));
        propertyList.addView(hintText("Each face shows its SurfaceType texture, or the Material texture when the surface is Smooth. Spawn pads keep their pad texture on top."));
        final String[] keys = {"RightSurface", "TopSurface", "BackSurface", "LeftSurface", "BottomSurface", "FrontSurface"};
        final String[] faces = {"Right (+X)", "Top (+Y)", "Back (+Z)", "Left (-X)", "Bottom (-Y)", "Front (-Z)"};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            final String faceName = faces[i];
            final int value = Math.max(0, Math.min(8, node.getInt(key, 0)));
            propertyList.addView(pickerRow(faceName, EngineTextures.SURFACE_TILES[value], () -> enumDialog("SurfaceType " + faceName, EngineTextures.SURFACE_TILES, value, chosen -> {
                scene.setProperty(node, key, chosen);
                afterEdit();
            })));
        }
        propertyList.addView(sectionLabel("Part"));
        propertyList.addView(pickerRow("Shape", PART_SHAPES[Math.max(0, Math.min(2, node.getInt("Shape", 1)))], () -> enumDialog("Part shape", PART_SHAPES, node.getInt("Shape", 1), chosen -> {
            scene.setProperty(node, "Shape", chosen);
            afterEdit();
        })));
        propertyList.addView(sliderRow("Reflectance", node.getNumber("Reflectance", 0), value -> { scene.setProperty(node, "Reflectance", value); afterEdit(); }));
        propertyList.addView(checkRow("CastShadow", node.getBool("CastShadow", true), checked -> { scene.setProperty(node, "CastShadow", checked); afterEdit(); }));
    }

    private static final String[] PART_SHAPES = {"Ball", "Block", "Cylinder"};

    private void workspaceRows(Instance node) {
        propertyList.addView(sectionLabel("Workspace"));
        propertyList.addView(numberRow("Gravity", node.getNumber("Gravity", 196.2), value -> { scene.setProperty(node, "Gravity", value); scene.grav(); afterEdit(); }));
        propertyList.addView(checkRow("StreamingEnabled", node.getBool("StreamingEnabled", false), checked -> { scene.setProperty(node, "StreamingEnabled", checked); afterEdit(); }));
        propertyList.addView(checkRow("FilteringEnabled", node.getBool("FilteringEnabled", true), checked -> { scene.setProperty(node, "FilteringEnabled", checked); afterEdit(); }));
    }

    private void lightingRows(Instance node) {
        propertyList.addView(sectionLabel("Lighting · time"));
        final double clock = node.getNumber("ClockTime", 14);
        LinearLayout clockBox = col();
        clockBox.setPadding(0, dp(3), 0, dp(3));
        LinearLayout clockTop = row();
        clockTop.addView(text("ClockTime", 10, false, MUTED), lp(0, -2, 1));
        final TextView clockRead = text(timeLabel(clock), 10, true, TEXT);
        clockTop.addView(clockRead);
        clockBox.addView(clockTop, lp(-1, -2));
        SeekBar bar = new SeekBar(this);
        bar.setMax(2400);
        bar.setProgress((int) Math.round(clock * 100));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { clockRead.setText(timeLabel(progress / 100.0)); }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { setClockTime(seekBar.getProgress() / 100.0); }
        });
        clockBox.addView(bar, lp(-1, dp(30)));
        propertyList.addView(clockBox);
        propertyList.addView(numberRow("GeographicLatitude", node.getNumber("GeographicLatitude", 41.7333), value -> { scene.setProperty(node, "GeographicLatitude", value); afterEdit(); }));
        propertyList.addView(numberRow("Brightness", node.getNumber("Brightness", 2), value -> { scene.setProperty(node, "Brightness", value); afterEdit(); }));
        propertyList.addView(numberRow("ExposureCompensation", node.getNumber("ExposureCompensation", 0), value -> { scene.setProperty(node, "ExposureCompensation", value); afterEdit(); }));

        propertyList.addView(sectionLabel("Lighting · colour"));
        propertyList.addView(colorRow("Ambient", colourOf(node, "Ambient", 0x000000), value -> { scene.setProperty(node, "Ambient", rgb(value)); afterEdit(); }));
        propertyList.addView(colorRow("OutdoorAmbient", colourOf(node, "OutdoorAmbient", 0x7F7F7F), value -> { scene.setProperty(node, "OutdoorAmbient", rgb(value)); afterEdit(); }));
        propertyList.addView(colorRow("ColorShift_Top", colourOf(node, "ColorShift_Top", 0x000000), value -> { scene.setProperty(node, "ColorShift_Top", rgb(value)); afterEdit(); }));
        propertyList.addView(colorRow("ColorShift_Bottom", colourOf(node, "ColorShift_Bottom", 0x000000), value -> { scene.setProperty(node, "ColorShift_Bottom", rgb(value)); afterEdit(); }));
        propertyList.addView(colorRow("FogColor", colourOf(node, "FogColor", 0xBFC0C3), value -> { scene.setProperty(node, "FogColor", rgb(value)); afterEdit(); }));

        propertyList.addView(sectionLabel("Lighting · environment"));
        propertyList.addView(sliderRow("EnvironmentDiffuseScale", node.getNumber("EnvironmentDiffuseScale", 0), value -> { scene.setProperty(node, "EnvironmentDiffuseScale", value); afterEdit(); }));
        propertyList.addView(sliderRow("EnvironmentSpecularScale", node.getNumber("EnvironmentSpecularScale", 1), value -> { scene.setProperty(node, "EnvironmentSpecularScale", value); afterEdit(); }));
        propertyList.addView(numberRow("FogStart", node.getNumber("FogStart", 0), value -> { scene.setProperty(node, "FogStart", value); afterEdit(); }));
        propertyList.addView(numberRow("FogEnd", node.getNumber("FogEnd", 100000), value -> { scene.setProperty(node, "FogEnd", value); afterEdit(); }));
        propertyList.addView(checkRow("GlobalShadows", node.getBool("GlobalShadows", true), checked -> { scene.setProperty(node, "GlobalShadows", checked); afterEdit(); }));
        propertyList.addView(sliderRow("ShadowSoftness", node.getNumber("ShadowSoftness", 0.2), value -> { scene.setProperty(node, "ShadowSoftness", value); afterEdit(); }));
        propertyList.addView(pickerRow("Technology", node.getString("Technology", "Voxel"), () -> enumDialog("Technology", TECHNOLOGIES, indexOf(TECHNOLOGIES, node.getString("Technology", "Voxel")), chosen -> {
            scene.setProperty(node, "Technology", TECHNOLOGIES[(int) chosen]);
            afterEdit();
        })));
        propertyList.addView(pickerRow("LightingStyle", node.getString("LightingStyle", "Soft"), () -> enumDialog("LightingStyle", LIGHTING_STYLES, indexOf(LIGHTING_STYLES, node.getString("LightingStyle", "Soft")), chosen -> {
            scene.setProperty(node, "LightingStyle", LIGHTING_STYLES[(int) chosen]);
            afterEdit();
        })));
    }

    private static final String[] TECHNOLOGIES = {"Voxel", "ShadowMap", "Future", "Compatibility", "Legacy"};
    private static final String[] LIGHTING_STYLES = {"Soft", "Realistic", "Colorful", "SoftShadows"};

    /** ClockTime and TimeOfDay are two views of one value in Roblox; keep them in step. */
    private void setClockTime(double hours) {
        Instance lighting = scene.root == null ? null : scene.root.findFirst("Lighting");
        if (lighting == null) return;
        double value = Math.max(0, Math.min(24, hours));
        scene.setProperty(lighting, "ClockTime", value);
        scene.setProperty(lighting, "TimeOfDay", timeLabel(value));
        afterEdit();
    }

    private static String timeLabel(double hours) {
        int total = (int) Math.round(Math.max(0, Math.min(24, hours)) * 60);
        return String.format(Locale.US, "%02d:%02d:00", (total / 60) % 24, total % 60);
    }

    private void skyRows(Instance node) {
        propertyList.addView(sectionLabel("Sky"));
        propertyList.addView(checkRow("CelestialBodiesShown", node.getBool("CelestialBodiesShown", true), checked -> { scene.setProperty(node, "CelestialBodiesShown", checked); afterEdit(); }));
        propertyList.addView(numberRow("SunAngularSize", node.getNumber("SunAngularSize", 21), value -> { scene.setProperty(node, "SunAngularSize", value); afterEdit(); }));
        propertyList.addView(numberRow("MoonAngularSize", node.getNumber("MoonAngularSize", 11), value -> { scene.setProperty(node, "MoonAngularSize", value); afterEdit(); }));
        propertyList.addView(numberRow("StarCount", node.getNumber("StarCount", 3000), value -> { scene.setProperty(node, "StarCount", value); afterEdit(); }));
        propertyList.addView(sectionLabel("Skybox textures"));
        propertyList.addView(hintText("rbxassetid:// ids are streamed and applied as a real cubemap once all six faces load. Leave them empty for the procedural Roblox-style sky."));
        String[] keys = {"SkyboxBk", "SkyboxDn", "SkyboxFt", "SkyboxLf", "SkyboxRt", "SkyboxUp"};
        for (final String key : keys) propertyList.addView(textRow(key, node.getString(key, ""), value -> { scene.setProperty(node, key, value.trim()); afterEdit(); }));
    }

    private void atmosphereRows(Instance node) {
        propertyList.addView(sectionLabel("Atmosphere"));
        propertyList.addView(sliderRow("Density", node.getNumber("Density", 0.3), value -> { scene.setProperty(node, "Density", value); afterEdit(); }));
        propertyList.addView(sliderRow("Offset", node.getNumber("Offset", 0), value -> { scene.setProperty(node, "Offset", value); afterEdit(); }));
        propertyList.addView(sliderRow("Decay", node.getNumber("Decay", 0.4), value -> { scene.setProperty(node, "Decay", value); afterEdit(); }));
        propertyList.addView(numberRow("Glare", node.getNumber("Glare", 0), value -> { scene.setProperty(node, "Glare", value); afterEdit(); }));
        propertyList.addView(numberRow("Haze", node.getNumber("Haze", 0), value -> { scene.setProperty(node, "Haze", value); afterEdit(); }));
        propertyList.addView(colorRow("Color", colourOf(node, "Color", 0xC7C7C7), value -> { scene.setProperty(node, "Color", rgb(value)); afterEdit(); }));
    }

    private void cloudsRows(Instance node) {
        propertyList.addView(sectionLabel("Clouds"));
        propertyList.addView(sliderRow("Cover", node.getNumber("Cover", 0.6), value -> { scene.setProperty(node, "Cover", value); afterEdit(); }));
        propertyList.addView(sliderRow("Density", node.getNumber("Density", 0.7), value -> { scene.setProperty(node, "Density", value); afterEdit(); }));
        propertyList.addView(colorRow("Color", colourOf(node, "Color", 0xFFFFFF), value -> { scene.setProperty(node, "Color", rgb(value)); afterEdit(); }));
    }

    private void lightRows(Instance node) {
        propertyList.addView(sectionLabel(node.className));
        propertyList.addView(numberRow("Brightness", node.getNumber("Brightness", 1), value -> { scene.setProperty(node, "Brightness", value); afterEdit(); }));
        propertyList.addView(numberRow("Range", node.getNumber("Range", 8), value -> { scene.setProperty(node, "Range", value); afterEdit(); }));
        if (node.className.equals("SpotLight")) propertyList.addView(numberRow("Angle", node.getNumber("Angle", 90), value -> { scene.setProperty(node, "Angle", value); afterEdit(); }));
        if (!node.className.equals("PointLight")) propertyList.addView(pickerRow("Face", NORMAL_IDS[Math.max(0, Math.min(5, node.getInt("Face", 5)))], () -> enumDialog("Face", NORMAL_IDS, node.getInt("Face", 5), chosen -> {
            scene.setProperty(node, "Face", chosen);
            afterEdit();
        })));
        propertyList.addView(colorRow("Color", colourOf(node, "Color", 0xFFFFFF), value -> { scene.setProperty(node, "Color", rgb(value)); afterEdit(); }));
        propertyList.addView(checkRow("Enabled", node.getBool("Enabled", true), checked -> { scene.setProperty(node, "Enabled", checked); afterEdit(); }));
    }

    private static final String[] NORMAL_IDS = {"Right", "Top", "Back", "Left", "Bottom", "Front"};

    private void textureRows(Instance node) {
        propertyList.addView(sectionLabel(node.className));
        propertyList.addView(textRow("Texture", node.getString(node.className.equals("Decal") ? "Texture" : "Texture", ""), value -> { scene.setProperty(node, "Texture", value.trim()); afterEdit(); }));
        propertyList.addView(pickerRow("Face", NORMAL_IDS[Math.max(0, Math.min(5, node.getInt("Face", 5)))], () -> enumDialog("Face", NORMAL_IDS, node.getInt("Face", 5), chosen -> {
            scene.setProperty(node, "Face", chosen);
            afterEdit();
        })));
        propertyList.addView(numberRow("StudsPerTileU", node.getNumber("StudsPerTileU", 1), value -> { scene.setProperty(node, "StudsPerTileU", value); afterEdit(); }));
        propertyList.addView(numberRow("StudsPerTileV", node.getNumber("StudsPerTileV", 1), value -> { scene.setProperty(node, "StudsPerTileV", value); afterEdit(); }));
        propertyList.addView(hintText("The image is streamed from Roblox with the class id in the path, then tiled across the face the way Roblox tiles it."));
    }

    private void surfaceAppearanceRows(Instance node) {
        propertyList.addView(sectionLabel("SurfaceAppearance"));
        String[] keys = {"ColorMap", "RoughnessMap", "MetalnessMap", "NormalMap", "EmissiveMask"};
        for (final String key : keys) propertyList.addView(textRow(key, node.getString(key, ""), value -> { scene.setProperty(node, key, value.trim()); afterEdit(); }));
        propertyList.addView(pickerRow("AlphaMode", ALPHA_MODES[Math.max(0, Math.min(3, node.getInt("AlphaMode", 0)))], () -> enumDialog("AlphaMode", ALPHA_MODES, node.getInt("AlphaMode", 0), chosen -> {
            scene.setProperty(node, "AlphaMode", chosen);
            afterEdit();
        })));
        propertyList.addView(hintText("ColorMap is streamed and layered over the material; the other maps are kept for round-trip editing."));
    }

    private static final String[] ALPHA_MODES = {"Overlay", "Transparency", "TintMask", "Opaque"};

    private void meshRows(Instance node) {
        propertyList.addView(sectionLabel("MeshPart"));
        propertyList.addView(readonlyRow("MeshId", node.getString("MeshId", node.getString("MeshID", ""))));
        propertyList.addView(textRow("TextureID", node.getString("TextureID", node.getString("TextureId", "")), value -> { scene.setProperty(node, "TextureID", value.trim()); afterEdit(); }));
        propertyList.addView(readonlyRow("DoubleSided", String.valueOf(node.getBool("DoubleSided", false))));
        propertyList.addView(hintText("Meshes render as their bounding box in this build; the texture id is streamed like a Texture object."));
    }

    /** Colour rows store Roblox Color3 values, so the INT3 tuple and the uint8 form both stay valid. */
    private int colourOf(Instance node, String key, int fallback) {
        Object value = node.props.get(key);
        if (value instanceof Instance.Vector3) {
            Instance.Vector3 v = (Instance.Vector3) value;
            return (clampByte(v.x) << 16) | (clampByte(v.y) << 8) | clampByte(v.z);
        }
        if (value instanceof Number) return ((Number) value).intValue() & 0xFFFFFF;
        return fallback;
    }

    private static int clampByte(double v) {
        int x = (int) Math.round(v * 255);
        return x < 0 ? 0 : Math.min(255, x);
    }

    private static Instance.Vector3 rgb(int colour) {
        return new Instance.Vector3(((colour >> 16) & 0xFF) / 255.0, ((colour >> 8) & 0xFF) / 255.0, (colour & 0xFF) / 255.0);
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(value)) return i;
        return 0;
    }

    /** Simple enum picker: the index of the chosen row is the Enum value Roblox stores. */
    private void enumDialog(String title, String[] names, int current, CommitNumber commit) {
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, Math.max(0, Math.min(names.length - 1, current)), (dialog, which) -> {
                    commit.set(which);
                    dialog.dismiss();
                }).show();
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

    private View colorRow(int color, CommitColor commit) { return colorRow("Color", color, commit); }

    private View colorRow(String label, int color, CommitColor commit) {
        LinearLayout box = row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(3), 0, dp(3));
        box.addView(text(label, 10, false, MUTED), lp(0, -2, 1));
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

    /** Every Enum.Material this build knows, with the real numeric ids so saves stay valid. */
    private void materialDialog(Instance node) {
        new AlertDialog.Builder(this).setTitle("Material")
                .setItems(EngineMaterial.pickerNames(), (d, which) -> { scene.setProperty(node, "Material", EngineMaterial.PICKER_IDS[which]); afterEdit(); })
                .show();
    }

    private String materialName(int value) { return EngineMaterial.nameOf(value); }

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
            case "snap": snapMenu(); return;
            default: tool = id; scene.gizmoTool = gizmoToolFor(id); refreshViewportTools(); return;
        }
    }

    /** Maps a tool button to the handle set the viewport draws and responds to. */
    private static int gizmoToolFor(String id) {
        switch (id) {
            case "move": return EngineGizmo.MOVE;
            case "rotate": return EngineGizmo.ROTATE;
            case "scale": return EngineGizmo.SCALE;
            case "grab": return EngineGizmo.GRAB;
            default: return EngineGizmo.MOVE;
        }
    }

    /** Grid snapping, in studs, applied by the transform handles while dragging. */
    private void snapMenu() {
        final double[] values = {0, 1, 0.5, 0.25};
        final String[] labels = {"Off", "1 stud", "0.5 studs", "0.25 studs"};
        new AlertDialog.Builder(this).setTitle("Grid snapping")
                .setItems(labels, (d, which) -> {
                    snap = values[which] > 0;
                    scene.gridSnap = values[which];
                    step = values[which] > 0 ? values[which] : 0.5;
                    refreshViewportTools();
                }).show();
    }

    private void refreshViewportTools() {
        if (toolStrip != null) {
            LinearLayout strip = toolStrip;
            String[] ids = {"select", "move", "rotate", "scale", "grab", "snap", null, "insert", "duplicate", "delete", "focus"};
            String[] icons = {"arrow", "move", "rotate", "scale", "hand", "grid", null, "cube", "copy", "trash", "search"};
            String[] descriptions = {"Select", "Move tool", "Rotate tool", "Scale tool", "Grab tool", "Grid snapping", null, "Insert object", "Duplicate", "Delete", "Focus selection"};
            for (int i = 0; i < strip.getChildCount() && i < ids.length; i++) {
                View child = strip.getChildAt(i);
                if (!(child instanceof FrameLayout) || ids[i] == null) continue;
                boolean active = ids[i].equals("snap") ? snap : tool.equals(ids[i]);
                child.setBackground(active ? shape(ACCENT, 6) : shape(0x00000000, 6));
                ((FrameLayout) child).removeAllViews();
                ((FrameLayout) child).addView(new StudioIcon(this, icons[i], active ? 0xffffffff : 0xffd0d6e0), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
                ((FrameLayout) child).setContentDescription(descriptions[i]);
                final String id = ids[i];
                child.setOnClickListener(v -> toolAction(id));
            }
        }
    }

    /**
     * Insert menu, grouped the way Studio's categories are so the list stays usable on a phone.
     * A container selection receives the new instance; otherwise it lands in Workspace next to the
     * current selection.
     */
    private void insertDialog() {
        final String[] categories = {"Parts", "Effects", "Lighting", "Scripts and UI", "Containers"};
        new AlertDialog.Builder(this).setTitle("Insert object")
                .setItems(categories, (d, which) -> {
                    final String[] items = INSERT_CATEGORIES[which];
                    new AlertDialog.Builder(this).setTitle("Insert " + categories[which])
                            .setItems(items, (d2, index) -> insertInstance(items[index])).show();
                }).show();
    }

    private static final String[][] INSERT_CATEGORIES = {
            {"Part", "Ball", "Cylinder", "Wedge", "CornerWedge", "Truss", "MeshPart", "SpawnLocation", "Seat", "VehicleSeat"},
            {"Decal", "Texture", "SurfaceAppearance", "ParticleEmitter", "Fire", "Smoke", "Sparkles", "Trail", "Beam", "Sound"},
            {"PointLight", "SpotLight", "SurfaceLight", "Sky", "Atmosphere", "Clouds", "BloomEffect", "BlurEffect", "ColorCorrectionEffect", "SunRaysEffect", "DepthOfFieldEffect", "ColorGradingEffect"},
            {"Script", "LocalScript", "ModuleScript", "ScreenGui", "Frame", "TextLabel", "TextButton", "BillboardGui", "SurfaceGui"},
            {"Folder", "Model", "Configuration", "Camera", "Terrain", "Attachment"}
    };

    /** Sensible Roblox defaults for the classes Studio inserts with properties set. */
    private void applyInsertDefaults(Instance instance) {
        switch (instance.className) {
            case "Decal": case "Texture":
                instance.props.put("Face", 5);
                instance.props.put("Texture", "");
                instance.props.put("StudsPerTileU", 1d);
                instance.props.put("StudsPerTileV", 1d);
                if (instance.className.equals("Texture")) instance.props.put("Transparency", 0d);
                break;
            case "PointLight": case "SpotLight": case "SurfaceLight":
                instance.props.put("Brightness", 1d);
                instance.props.put("Color", new Instance.Vector3(1, 1, 1));
                instance.props.put("Range", 8d);
                instance.props.put("Enabled", true);
                if (instance.className.equals("SurfaceLight")) instance.props.put("Face", 5);
                if (instance.className.equals("SpotLight")) { instance.props.put("Angle", 90d); instance.props.put("Face", 5); }
                break;
            case "Sky":
                instance.props.put("CelestialBodiesShown", true);
                instance.props.put("StarCount", 3000d);
                instance.props.put("SunAngularSize", 21d);
                instance.props.put("MoonAngularSize", 11d);
                break;
            case "Atmosphere":
                instance.props.put("Density", 0.3d);
                instance.props.put("Offset", 0d);
                instance.props.put("Decay", 0.4d);
                instance.props.put("Glare", 0d);
                instance.props.put("Haze", 0d);
                instance.props.put("Color", new Instance.Vector3(199 / 255.0, 199 / 255.0, 199 / 255.0));
                break;
            case "Clouds":
                instance.props.put("Cover", 0.6d);
                instance.props.put("Density", 0.7d);
                instance.props.put("Color", new Instance.Vector3(1, 1, 1));
                break;
            case "BloomEffect": instance.props.put("Enabled", true); instance.props.put("Intensity", 1d); instance.props.put("Size", 24d); instance.props.put("Threshold", 2d); break;
            case "BlurEffect": instance.props.put("Enabled", true); instance.props.put("Size", 24d); break;
            case "SunRaysEffect": instance.props.put("Enabled", true); instance.props.put("Intensity", 0.25d); instance.props.put("Spread", 1d); break;
            case "DepthOfFieldEffect": instance.props.put("Enabled", true); instance.props.put("FarIntensity", 0.75d); instance.props.put("FocusDistance", 0.1d); instance.props.put("InFocusRadius", 1d); break;
            case "ColorCorrectionEffect": instance.props.put("Enabled", true); instance.props.put("Brightness", 0d); instance.props.put("Contrast", 0d); instance.props.put("Saturation", 0d); break;
            case "ColorGradingEffect": instance.props.put("Enabled", true); break;
            case "SurfaceAppearance": instance.props.put("AlphaMode", 0); break;
            case "Folder": case "Model": case "Configuration": case "Terrain": case "Attachment": break;
            case "ScreenGui": instance.props.put("Enabled", true); break;
            default: break;
        }
    }

    private void insertInstance(String label) {
        String cls = label;
        Instance created;
        if (label.equals("Ball")) { created = scene.insert("Part"); created.props.put("Shape", 0); }
        else if (label.equals("Cylinder")) { created = scene.insert("Part"); created.props.put("Shape", 2); }
        else if (label.equals("Wedge")) created = scene.insert("WedgePart");
        else if (label.equals("CornerWedge")) created = scene.insert("CornerWedgePart");
        else if (label.equals("Truss")) created = scene.insert("TrussPart");
        else created = scene.insert(cls);
        if (created != null && created.props.isEmpty()) applyInsertDefaults(created);
        if (created == null) return;
        expanded.add(created.parent == null ? scene.workspace : created.parent);
        if (!created.children.isEmpty()) expanded.add(created);
        afterEdit();
    }

    // ------------------------------------------------------------------ play
    private void startPlay() {
        scene.startPlay();
        playControls.setVisibility(View.VISIBLE);
        if (liftPad != null) liftPad.setVisibility(View.GONE);
        flyControls.setVisibility(View.VISIBLE);
        playLabel.setText("Stop");
        subtitle.setText("Playing locally — drag to look, joystick to move");
        scene.refreshCache();
    }

    private void stopPlay() {
        scene.stopPlay();
        playControls.setVisibility(View.GONE);
        if (liftPad != null) liftPad.setVisibility(View.VISIBLE);
        flyControls.setVisibility(View.VISIBLE);
        playLabel.setText("Play");
        subtitle.setText("Edit mode — drag to look, joystick to fly, handles to transform");
        scene.refreshCache();
    }

    // ------------------------------------------------------------------ persistence
    private void moreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Focus selection");
        menu.getMenu().add("Select spawn point");
        menu.getMenu().add("Reset camera");
        menu.getMenu().add("Undo");
        menu.getMenu().add("Redo");
        menu.getMenu().add(explorerPanel != null && explorerPanel.getVisibility() == View.VISIBLE ? "Hide Explorer" : "Show Explorer");
        menu.getMenu().add(propertiesPanel != null && propertiesPanel.getVisibility() == View.VISIBLE ? "Hide Properties" : "Show Properties");
        menu.getMenu().add(snap ? "Grid snapping: on" : "Grid snapping: off");
        menu.getMenu().add("Engine limits…");
        menu.setOnMenuItemClickListener(item -> {
            String label = item.getTitle().toString();
            if (label.startsWith("Focus") && scene.selection != null) { scene.focus(scene.selection); refresh(); }
            else if (label.startsWith("Select spawn")) { Instance spawn = scene.root.findFirst("SpawnLocation"); if (spawn != null) { scene.selection = spawn; scene.focus(spawn); refresh(); } }
            else if (label.startsWith("Reset camera")) { scene.camTarget[0] = 0; scene.camTarget[1] = 4; scene.camTarget[2] = 0; scene.camDist = 34; scene.camYaw = Math.toRadians(35); scene.camPitch = Math.toRadians(-30); }
            else if (label.equals("Undo")) undo();
            else if (label.equals("Redo")) redo();
            else if (label.endsWith("Explorer")) togglePanel(explorerPanel, explorerSeparator);
            else if (label.endsWith("Properties")) togglePanel(propertiesPanel, propertiesSeparator);
            else if (label.startsWith("Grid snapping")) snapMenu();
            else if (label.startsWith("Engine limits")) limitsDialog();
            return true;
        });
        menu.show();
    }

    private void limitsDialog() {
        String textureNote = viewport == null ? "" : viewport.textureNote();
        new AlertDialog.Builder(this).setTitle("Roblox engine in this build")
                .setMessage("Solid parts (Part, SpawnLocation, Wedge, Truss, MeshPart, seats) render as boxes with their real size, CFrame, colour and per-face surface type.\n\n" +
                        "Textures: every face samples its SurfaceType texture (Studs, Inlet, Universal, Hinge, Motor, SteppingMotor, Weld, Glue) or its Enum.Material texture, world aligned and tiled like Roblox tiles them. " +
                        "Texture, Decal, SurfaceAppearance.ColorMap, MeshPart.TextureID and Sky.Skybox* assets are streamed, cached and applied.\n\n" +
                        "Lighting is read from the place: ClockTime/TimeOfDay and GeographicLatitude drive the sun, and Ambient, OutdoorAmbient, " +
                        "ColorShift_Top/Bottom, Brightness, FogStart/FogEnd/FogColor, EnvironmentDiffuseScale and " +
                        "EnvironmentSpecularScale all shade the scene. PointLight, SpotLight and SurfaceLight objects light parts " +
                        "near them (4 at a time), and Material drives metalness/roughness. Sky, Atmosphere and Clouds shade the sky and add its haze and cloud layer. " +
                        "ColorCorrection, Bloom, Blur, SunRays and ExposureCompensation are applied as a post pass.\n\n" +
                        "Editing: Move/Rotate/Scale handles work by grabbing them, the grab tool slides parts, and the joystick flies the camera. " +
                        "Grid snapping is in the tool strip.\n\n" +
                        "Not rendered yet: shadow maps (GlobalShadows/ShadowSoftness are read but no shadow map is produced), DepthOfField, terrain, " +
                        "particles, animations, and SurfaceAppearance maps other than ColorMap.\n\n" +
                        "Scripts do not run. Unions/Meshes use bounding boxes, and rotated parts collide as axis-aligned boxes.\n\n" +
                        "Saving writes the Roblox XML data model back to this place file; publish from the project page to sync with Roblox."
                        + (textureNote.isEmpty() ? "" : "\n\n" + textureNote)
                        + (viewport == null || viewport.postNote().isEmpty() ? "" : "\n\n" + viewport.postNote()))
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
