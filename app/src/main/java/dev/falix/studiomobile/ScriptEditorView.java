package dev.falix.studiomobile;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import java.util.regex.*;

final class ScriptEditorView extends LinearLayout {
    interface Listener {
        void onSaveScript(Instance script, String source);
        void onRunScript(Instance script, String source);
        void onCloseEditor();
    }

    private static final Pattern LUAU_TOKENS = Pattern.compile(
            "(--\\[\\[[\\s\\S]*?\\]\\]|--[^\\n]*)"
            + "|(\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|`([^`\\\\]|\\\\.)*`)"
            + "|\\b(local|function|end|if|then|elseif|else|for|in|do|while|repeat|until|return|break|continue|and|or|not|true|false|nil)\\b"
            + "|\\b(game|workspace|script|Instance|Vector3|CFrame|Color3|BrickColor|UDim2|Enum|TweenInfo|task|math|string|table|os|print|warn|error|pcall|require|pairs|ipairs|tostring|tonumber|typeof|type|wait|tick)\\b"
            + "|\\b(0x[0-9a-fA-F_]+|\\d+(\\.\\d+)?)\\b"
    );

    private final TextView titleView, statsView;
    private final EditText codeField;
    private final Listener listener;
    private Instance target;
    private boolean updating;

    ScriptEditorView(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF141821);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF1D222E);
        top.setPadding(dp(10), dp(4), dp(8), dp(4));

        top.addView(new StudioIcon(ctx, "script", 0xFF6F9BFF), new LayoutParams(dp(16), dp(16)));

        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(VERTICAL);
        titles.setPadding(dp(8), 0, dp(8), 0);
        titleView = makeText(ctx, "Script", 12.5f, true, 0xFFF1F5FC);
        statsView = makeText(ctx, "Luau · 1 line", 10f, false, 0xFF8C97AB);
        titles.addView(titleView);
        titles.addView(statsView);
        top.addView(titles, new LayoutParams(0, -2, 1));

        top.addView(pillButton(ctx, "Run", 0xFF1F7A4D, 0xFFFFFFFF, () -> {
            if (target != null && listener != null) {
                String src = codeField.getText().toString();
                listener.onSaveScript(target, src);
                listener.onRunScript(target, src);
            }
        }), marginLp(dp(4), 0, -2, dp(30)));

        top.addView(pillButton(ctx, "Save", 0xFF2D56E8, 0xFFFFFFFF, () -> {
            if (target != null && listener != null) {
                listener.onSaveScript(target, codeField.getText().toString());
            }
        }), marginLp(dp(6), 0, -2, dp(30)));

        FrameLayout closeBtn = new FrameLayout(ctx);
        closeBtn.addView(new StudioIcon(ctx, "close", 0xFFC5CEE0), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        closeBtn.setOnClickListener(v -> {
            if (target != null && listener != null) listener.onSaveScript(target, codeField.getText().toString());
            if (listener != null) listener.onCloseEditor();
        });
        top.addView(closeBtn, marginLp(dp(4), 0, dp(34), dp(34)));

        addView(top, new LayoutParams(-1, dp(44)));
        addView(divider(ctx), new LayoutParams(-1, dp(1)));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        codeField = new EditText(ctx);
        codeField.setBackgroundColor(0xFF141821);
        codeField.setTextColor(0xFFE6ECF7);
        codeField.setHintTextColor(0xFF5A6478);
        codeField.setHint("-- Write Luau code here...");
        codeField.setTypeface(Typeface.MONOSPACE);
        codeField.setTextSize(12.5f);
        codeField.setGravity(Gravity.TOP | Gravity.START);
        codeField.setPadding(dp(12), dp(10), dp(12), dp(40));
        codeField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeField.setHorizontallyScrolling(false);
        codeField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) {
                if (updating) return;
                highlight(s);
                updateStats(s);
            }
        });
        scroll.addView(codeField, new ScrollView.LayoutParams(-1, -1));
        addView(scroll, new LayoutParams(-1, 0, 1));

        addView(divider(ctx), new LayoutParams(-1, dp(1)));
        addView(buildQuickBar(ctx), new LayoutParams(-1, dp(38)));
    }

    void open(Instance script) {
        this.target = script;
        titleView.setText(script.name + "  (" + script.className + ")");
        String src = script.getString("Source", "");
        updating = true;
        codeField.setText(src);
        highlight(codeField.getText());
        updateStats(codeField.getText());
        updating = false;
        setVisibility(VISIBLE);
    }

    Instance currentScript() { return target; }

    private View buildQuickBar(Context ctx) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setBackgroundColor(0xFF1A1F2B);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), 0, dp(6), 0);
        String[][] keys = {
            {"TAB", "    "}, {"local", "local "}, {"fn()", "function()\n    \nend"},
            {"end", "end"}, {"()", "()"}, {"{}", "{}"}, {"\"\"", "\"\""},
            {"=", " = "}, {"game:", "game:GetService(\"\")"}, {"wait", "task.wait(1)"},
            {"print", "print(\"\")"}, {"Touched", ".Touched:Connect(function(hit)\n    \nend)"}
        };
        for (final String[] k : keys) {
            TextView btn = makeText(ctx, k[0], 11f, true, 0xFFD4DDF0);
            btn.setTypeface(Typeface.MONOSPACE);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(9), dp(4), dp(9), dp(4));
            btn.setBackground(roundBg(0xFF283042, 6));
            btn.setOnClickListener(v -> insertSnippet(k[1]));
            row.addView(btn, marginLp(dp(4), 0, -2, dp(28)));
        }
        hsv.addView(row);
        return hsv;
    }

    private void insertSnippet(String snippet) {
        int start = Math.max(0, codeField.getSelectionStart());
        int end = Math.max(0, codeField.getSelectionEnd());
        Editable ed = codeField.getText();
        ed.replace(Math.min(start, end), Math.max(start, end), snippet);
    }

    private void highlight(Editable s) {
        updating = true;
        ForegroundColorSpan[] old = s.getSpans(0, s.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : old) s.removeSpan(span);
        if (s.length() <= 24000) {
            Matcher m = LUAU_TOKENS.matcher(s);
            while (m.find()) {
                int color = 0xFFE6ECF7;
                if (m.group(1) != null) color = 0xFF6B7B8F;
                else if (m.group(2) != null) color = 0xFF98E08C;
                else if (m.group(6) != null) color = 0xFFFF7B9C;
                else if (m.group(7) != null) color = 0xFF72B6FF;
                else if (m.group(8) != null) color = 0xFFFFC86B;
                s.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        updating = false;
    }

    private void updateStats(CharSequence s) {
        int lines = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        statsView.setText("Luau  ·  " + lines + (lines == 1 ? " line" : " lines") + "  ·  " + s.length() + " chars");
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private View divider(Context ctx) { View v = new View(ctx); v.setBackgroundColor(0xFF2D3545); return v; }
    private TextView makeText(Context ctx, String t, float sp, boolean bold, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        return tv;
    }
    private TextView pillButton(Context ctx, String label, int bg, int fg, Runnable action) {
        TextView tv = makeText(ctx, label, 11.5f, true, fg);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(4), dp(12), dp(4));
        tv.setBackground(roundBg(bg, 8));
        tv.setOnClickListener(v -> action.run());
        return tv;
    }
    private GradientDrawable roundBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }
    private LayoutParams marginLp(int left, int top, int w, int h) {
        LayoutParams p = new LayoutParams(w, h);
        p.leftMargin = left;
        p.topMargin = top;
        return p;
    }
}
