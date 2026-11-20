package dev.falix.studiomobile;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;

/**
 * Authentic Roblox Client Playtest CoreGui:
 *  - Top-left Roblox Experience Controls (Tilted Roblox Menu icon + Chat toggle)
 *  - Top-right Roblox Player / Health Bar + quick Stop pill
 *  - Translucent In-Game Chat panel firing Player.Chatted
 *  - Full Roblox Escape Settings Menu wired live to Lighting Technology, ClockTime,
 *    GlobalShadows, Camera Mode (3P/1P), Avatar Rig (R15/R6), Performance Stats, Reset, and Leave.
 */
final class RobloxPlayUi {
    interface Callbacks {
        void onStopPlay();
        void onToggleDiagnostics();
        boolean isDiagnosticsVisible();
        void onLightingChanged();
        void onRigChanged();
    }

    final FrameLayout root;
    private final Context ctx;
    private final EngineScene scene;
    private final Callbacks cb;
    private final float d;

    private View healthFill;
    private TextView playerBadgeText;
    private LinearLayout chatPanel, chatMessages;
    private FrameLayout escapeOverlay;
    private EditText chatInput;

    RobloxPlayUi(Context ctx, EngineScene scene, Callbacks cb) {
        this.ctx = ctx;
        this.scene = scene;
        this.cb = cb;
        this.d = ctx.getResources().getDisplayMetrics().density;
        this.root = new FrameLayout(ctx);
        build();
    }

    private int dp(float v) { return (int) (v * d + 0.5f); }

    private GradientDrawable pill(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * d);
        if (strokeColor != 0) g.setStroke(Math.max(1, dp(1)), strokeColor);
        return g;
    }

    private void build() {
        // --- Top-left Roblox CoreGui Unibar (Roblox Tilt-Square Menu + Chat + Camera) ---
        LinearLayout topLeft = new LinearLayout(ctx);
        topLeft.setOrientation(LinearLayout.HORIZONTAL);
        topLeft.setGravity(Gravity.CENTER_VERTICAL);

        // Authentic Roblox Menu Icon Button
        FrameLayout menuBtn = new FrameLayout(ctx);
        menuBtn.setBackground(pill(0xCC12151C, 22, 0x33FFFFFF));
        FrameLayout sq = new FrameLayout(ctx);
        sq.setBackground(pill(0x00000000, 2, 0xFFFFFFFF));
        sq.setRotation(15f);
        View inner = new View(ctx);
        inner.setBackgroundColor(0xFFFFFFFF);
        sq.addView(inner, new FrameLayout.LayoutParams(dp(5), dp(5), Gravity.CENTER));
        menuBtn.addView(sq, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        menuBtn.setOnClickListener(v -> showRobloxSettingsMenu());
        topLeft.addView(menuBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // Chat Toggle Button
        TextView chatBtn = new TextView(ctx);
        chatBtn.setText("💬");
        chatBtn.setTextSize(15f);
        chatBtn.setGravity(Gravity.CENTER);
        chatBtn.setBackground(pill(0xCC12151C, 22, 0x33FFFFFF));
        chatBtn.setOnClickListener(v -> {
            if (chatPanel != null) {
                chatPanel.setVisibility(chatPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        LinearLayout.LayoutParams chatLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        chatLp.leftMargin = dp(8);
        topLeft.addView(chatBtn, chatLp);

        FrameLayout.LayoutParams tlLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        tlLp.setMargins(dp(12), dp(10), 0, 0);
        root.addView(topLeft, tlLp);

        // --- Top-right Roblox Player Health Bar + Compact Stop Playtest Pill ---
        LinearLayout topRight = new LinearLayout(ctx);
        topRight.setOrientation(LinearLayout.HORIZONTAL);
        topRight.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout hpCard = new LinearLayout(ctx);
        hpCard.setOrientation(LinearLayout.VERTICAL);
        hpCard.setBackground(pill(0xCC12151C, 10, 0x28FFFFFF));
        hpCard.setPadding(dp(10), dp(5), dp(10), dp(6));

        playerBadgeText = new TextView(ctx);
        playerBadgeText.setText(scene.avatar != null ? scene.avatar.username : "Player");
        playerBadgeText.setTextColor(0xFFF0F4FA);
        playerBadgeText.setTextSize(10.5f);
        playerBadgeText.setTypeface(Typeface.DEFAULT_BOLD);
        playerBadgeText.setSingleLine(true);
        playerBadgeText.setEllipsize(TextUtils.TruncateAt.END);
        hpCard.addView(playerBadgeText);

        FrameLayout barTrack = new FrameLayout(ctx);
        barTrack.setBackground(pill(0xFF2B313E, 3, 0));
        healthFill = new View(ctx);
        healthFill.setBackground(pill(0xFF2ED573, 3, 0));
        barTrack.addView(healthFill, new FrameLayout.LayoutParams(dp(108), dp(6)));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(108), dp(6));
        trackLp.topMargin = dp(3);
        hpCard.addView(barTrack, trackLp);
        topRight.addView(hpCard);

        TextView stopPill = new TextView(ctx);
        stopPill.setText("■ Stop");
        stopPill.setTextColor(0xFFFFFFFF);
        stopPill.setTextSize(11f);
        stopPill.setTypeface(Typeface.DEFAULT_BOLD);
        stopPill.setGravity(Gravity.CENTER);
        stopPill.setPadding(dp(12), dp(8), dp(12), dp(8));
        stopPill.setBackground(pill(0xE6D93636, 18, 0));
        stopPill.setOnClickListener(v -> { if (cb != null) cb.onStopPlay(); });
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-2, dp(36));
        stLp.leftMargin = dp(8);
        topRight.addView(stopPill, stLp);

        FrameLayout.LayoutParams trLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        trLp.setMargins(0, dp(10), dp(12), 0);
        root.addView(topRight, trLp);

        // --- Translucent Roblox In-Game Chat Panel ---
        chatPanel = new LinearLayout(ctx);
        chatPanel.setOrientation(LinearLayout.VERTICAL);
        chatPanel.setBackground(pill(0xAA10131A, 10, 0x2AFFFFFF));
        chatPanel.setPadding(dp(8), dp(6), dp(8), dp(6));
        chatPanel.setVisibility(View.GONE);

        ScrollView chatScroll = new ScrollView(ctx);
        chatMessages = new LinearLayout(ctx);
        chatMessages.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(chatMessages);
        chatPanel.addView(chatScroll, new LinearLayout.LayoutParams(dp(230), dp(90)));

        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        chatInput = new EditText(ctx);
        chatInput.setHint("Tap here to chat");
        chatInput.setHintTextColor(0xFF9AA4B5);
        chatInput.setTextColor(0xFFFFFFFF);
        chatInput.setTextSize(11f);
        chatInput.setSingleLine(true);
        chatInput.setBackground(pill(0xCC1E2430, 6, 0));
        chatInput.setPadding(dp(8), dp(4), dp(8), dp(4));
        inputRow.addView(chatInput, new LinearLayout.LayoutParams(0, dp(30), 1f));

        TextView sendBtn = new TextView(ctx);
        sendBtn.setText("Send");
        sendBtn.setTextColor(0xFFFFFFFF);
        sendBtn.setTextSize(10.5f);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sendBtn.setPadding(dp(8), dp(5), dp(8), dp(5));
        sendBtn.setBackground(pill(0xFF00A2FF, 6, 0));
        sendBtn.setOnClickListener(v -> sendChat());
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, -2);
        sLp.leftMargin = dp(6);
        inputRow.addView(sendBtn, sLp);

        LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(dp(230), -2);
        irLp.topMargin = dp(4);
        chatPanel.addView(inputRow, irLp);

        FrameLayout.LayoutParams cpLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        cpLp.setMargins(dp(12), dp(58), 0, 0);
        root.addView(chatPanel, cpLp);
    }

    private void sendChat() {
        String msg = chatInput.getText().toString().trim();
        if (msg.isEmpty()) return;
        chatInput.setText("");
        String user = scene.avatar != null ? scene.avatar.username : "Player";
        TextView line = new TextView(ctx);
        line.setText("[" + user + "]: " + msg);
        line.setTextColor(0xFFF2F6FC);
        line.setTextSize(11f);
        chatMessages.addView(line);
        scene.logOutput("info", "[Chat] " + user + ": " + msg);
        scene.lua().firePlayerChatted(msg);
    }

    void refreshHud() {
        if (playerBadgeText != null && scene.avatar != null) {
            playerBadgeText.setText(scene.avatar.username + " · " + (scene.avatar.isR15 ? "R15" : "R6"));
        }
        if (healthFill != null) {
            float frac = (float) Math.max(0.0, Math.min(1.0, scene.charHealth / Math.max(1.0, scene.charMaxHealth)));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) healthFill.getLayoutParams();
            lp.width = Math.max(dp(2), Math.round(dp(108) * frac));
            healthFill.setLayoutParams(lp);
            int col = frac > 0.5f ? 0xFF2ED573 : (frac > 0.25f ? 0xFFFFBE3B : 0xFFFF4757);
            healthFill.setBackground(pill(col, 3, 0));
        }
    }

    private void showRobloxSettingsMenu() {
        ScrollView sv = new ScrollView(ctx);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(0xFF1F2229);
        body.setPadding(dp(18), dp(14), dp(18), dp(14));

        TextView head = new TextView(ctx);
        head.setText("Roblox Experience Menu");
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(16f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(head);

        String user = scene.avatar != null ? "@" + scene.avatar.username : "@Player";
        TextView sub = new TextView(ctx);
        sub.setText("Player: " + user + "   |   State: " + scene.humanoidState);
        sub.setTextColor(0xFF9EA8B8);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(2), 0, dp(12));
        body.addView(sub);

        AlertDialog[] holder = new AlertDialog[1];

        // 1. Avatar Rig Toggle (R15 / R6)
        boolean isR15 = scene.avatar == null || scene.avatar.isR15;
        addSwitchRow(body, "Avatar Rig: R15 (Off = R6)", isR15, checked -> {
            if (scene.avatar == null) scene.avatar = AvatarProfile.defaultAvatar();
            scene.avatar.isR15 = checked;
            refreshHud();
            if (cb != null) cb.onRigChanged();
        });

        // 2. Camera Mode Toggle (1P / 3P)
        addSwitchRow(body, "First Person Camera (1P)", scene.firstPerson, checked -> {
            scene.firstPerson = checked;
            scene.camDist = checked ? 1.0 : 16.0;
        });

        // 3. Global Shadows Toggle
        Instance lighting = scene.root == null ? null : scene.root.findFirst("Lighting");
        boolean shadowsOn = lighting == null || lighting.getBool("GlobalShadows", true);
        addSwitchRow(body, "Global Shadows", shadowsOn, checked -> {
            if (lighting != null) {
                lighting.props.put("GlobalShadows", checked);
                scene.markDirty();
                scene.refreshCache();
                if (cb != null) cb.onLightingChanged();
            }
        });

        // 4. Performance Stats Toggle
        addSwitchRow(body, "Performance Stats (FPS / 240Hz)", cb != null && cb.isDiagnosticsVisible(), checked -> {
            if (cb != null) cb.onToggleDiagnostics();
        });

        // 5. Lighting Technology Selector (Future / ShadowMap / Voxel)
        TextView techLbl = new TextView(ctx);
        techLbl.setText("Graphics / Lighting Technology");
        techLbl.setTextColor(0xFFD8E0EC);
        techLbl.setTextSize(12f);
        techLbl.setPadding(0, dp(10), 0, dp(4));
        body.addView(techLbl);

        LinearLayout techRow = new LinearLayout(ctx);
        techRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] techs = {"Future", "ShadowMap", "Voxel"};
        int[] techIds = {3, 2, 1};
        for (int i = 0; i < techs.length; i++) {
            final int tid = techIds[i];
            final String tname = techs[i];
            TextView tb = new TextView(ctx);
            tb.setText(tname);
            tb.setTextColor(0xFFFFFFFF);
            tb.setTextSize(11.5f);
            tb.setGravity(Gravity.CENTER);
            tb.setPadding(dp(10), dp(8), dp(10), dp(8));
            boolean cur = scene.lighting != null && tname.equalsIgnoreCase(scene.lighting.technology);
            tb.setBackground(pill(cur ? 0xFF00A2FF : 0xFF323846, 8, 0));
            tb.setOnClickListener(v -> {
                if (lighting != null) {
                    lighting.props.put("Technology", tid);
                    scene.markDirty();
                    scene.refreshCache();
                    if (cb != null) cb.onLightingChanged();
                }
                closeEscapeOverlay();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            techRow.addView(tb, lp);
        }
        body.addView(techRow);

        // 6. Time of Day (ClockTime) Slider
        double curClock = lighting != null ? lighting.getNumber("ClockTime", 14.0) : 14.0;
        TextView clockLbl = new TextView(ctx);
        clockLbl.setText(String.format(Locale.US, "ClockTime: %.1f:00", curClock));
        clockLbl.setTextColor(0xFFD8E0EC);
        clockLbl.setTextSize(12f);
        clockLbl.setPadding(0, dp(12), 0, dp(2));
        body.addView(clockLbl);

        SeekBar clockBar = new SeekBar(ctx);
        clockBar.setMax(240);
        clockBar.setProgress((int) Math.round(curClock * 10.0));
        clockBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                double ct = prog / 10.0;
                clockLbl.setText(String.format(Locale.US, "ClockTime: %.1f:00", ct));
                if (fromUser && lighting != null) {
                    lighting.props.put("ClockTime", ct);
                    scene.markDirty();
                    scene.refreshCache();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        body.addView(clockBar);

        // 7. Action Buttons (Reset Character / Resume / Leave Experience)
        LinearLayout actRow = new LinearLayout(ctx);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(-1, -2);
        arLp.topMargin = dp(14);
        actRow.addView(actionBtn("Reset", 0xFF394152, () -> {
            scene.respawnCharacter();
            closeEscapeOverlay();
        }), new LinearLayout.LayoutParams(0, dp(38), 1f));

        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        midLp.leftMargin = dp(8);
        actRow.addView(actionBtn("Resume", 0xFF00A2FF, () -> {
            closeEscapeOverlay();
        }), midLp);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        rightLp.leftMargin = dp(8);
        actRow.addView(actionBtn("Leave", 0xFFD93636, () -> {
            closeEscapeOverlay();
            if (cb != null) cb.onStopPlay();
        }), rightLp);
        body.addView(actRow, arLp);

        sv.addView(body);
        // Full-screen translucent Roblox Escape Menu overlay (replaces AlertDialog)
        if (escapeOverlay != null) root.removeView(escapeOverlay);
        escapeOverlay = new FrameLayout(ctx);
        escapeOverlay.setBackgroundColor(0xD612141B);
        escapeOverlay.setClickable(true);
        escapeOverlay.setFocusable(true);
        FrameLayout card = new FrameLayout(ctx);
        card.setBackground(pill(0xF21B1E26, 14, 0x33FFFFFF));
        card.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        int cardW = Math.min(dp(440), ctx.getResources().getDisplayMetrics().widthPixels - dp(36));
        int cardH = Math.min(dp(340), ctx.getResources().getDisplayMetrics().heightPixels - dp(36));
        escapeOverlay.addView(card, new FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER));
        escapeOverlay.setOnClickListener(v -> closeEscapeOverlay());
        root.addView(escapeOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeEscapeOverlay() {
        if (escapeOverlay != null) {
            root.removeView(escapeOverlay);
            escapeOverlay = null;
        }
    }

    private interface BoolListener { void onChange(boolean v); }

    private void addSwitchRow(LinearLayout parent, String label, boolean initial, BoolListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFE8EEF8);
        tv.setTextSize(12.5f);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(ctx);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((b, c) -> listener.onChange(c));
        row.addView(sw);
        parent.addView(row);
    }

    private TextView actionBtn(String label, int color, Runnable action) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(pill(color, 8, 0));
        tv.setOnClickListener(v -> action.run());
        return tv;
    }
}
