package dev.falix.studiomobile;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * In-app Roblox WebView login dialog that captures the .ROBLOSECURITY session cookie
 * after normal Roblox sign-in (supporting 2FA/passkeys/captcha) and hands it to the caller
 * for encrypted Android Keystore storage.
 */
final class RobloxWebLoginDialog {
    interface Callback {
        void onCookieCaptured(String roblosecurityCookie);
    }

    @SuppressLint("SetJavaScriptEnabled")
    static void show(Context context, Callback callback) {
        final float d = context.getResources().getDisplayMetrics().density;
        final int pad = (int) (10 * d + 0.5f);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1F1F1F);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF252525);
        header.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(context);
        title.setText("Sign in to Roblox");
        title.setTextSize(14f);
        title.setTextColor(0xFFE8E8E8);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView status = new TextView(context);
        status.setText("Encrypted Keystore");
        status.setTextSize(11f);
        status.setTextColor(0xFF00A2FF);
        header.addView(status);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.getIndeterminateDrawable().setColorFilter(0xFF00A2FF, android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(progress, new LinearLayout.LayoutParams(-1, (int) (3 * d + 0.5f)));

        FrameLayout webWrap = new FrameLayout(context);
        WebView web = new WebView(context);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setOnTouchListener((v, ev) -> { v.requestFocus(View.FOCUS_DOWN); return false; });
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        int heightPx = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.72f);
        webWrap.addView(web, new FrameLayout.LayoutParams(-1, heightPx));
        root.addView(webWrap, new LinearLayout.LayoutParams(-1, heightPx));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(root)
                .setNegativeButton("Cancel", null)
                .create();

        final boolean[] captured = {false};
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable checkCookie = new Runnable() {
            @Override public void run() {
                if (captured[0] || !dialog.isShowing()) return;
                String c = extractRoblosecurity(cm.getCookie("https://www.roblox.com"));
                if (c == null) c = extractRoblosecurity(cm.getCookie("https://roblox.com"));
                if (c != null && c.length() > 40) {
                    captured[0] = true;
                    status.setText("Verified!");
                    dialog.dismiss();
                    if (callback != null) callback.onCookieCaptured(c);
                    return;
                }
                handler.postDelayed(this, 1000);
            }
        };

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.INVISIBLE);
                checkCookie.run();
            }
        });

        dialog.setOnDismissListener(d1 -> {
            handler.removeCallbacks(checkCookie);
            web.stopLoading();
            web.destroy();
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        web.requestFocus(View.FOCUS_DOWN);
        web.loadUrl("https://www.roblox.com/login");
        handler.postDelayed(checkCookie, 1500);
    }

    static String extractRoblosecurity(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return null;
        String[] parts = cookieHeader.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith(".ROBLOSECURITY=")) {
                String val = trimmed.substring(".ROBLOSECURITY=".length()).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }
}
