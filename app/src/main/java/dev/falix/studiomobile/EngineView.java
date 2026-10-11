package dev.falix.studiomobile;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

/** Viewport surface: one finger orbits, two fingers pan/zoom, tap picks a part. */
final class EngineView extends GLSurfaceView {
    interface Listener { void onPick(Instance hit); void onCameraMoved(); void onRendererError(String message); }

    final EngineScene scene;
    private final EngineRenderer renderer;
    private Listener listener;
    private float lastX, lastY, downX, downY, panX, panY, pinchStart;
    private long downTime;
    private boolean moved;

    EngineView(Context context, EngineScene scene) {
        super(context);
        this.scene = scene;
        setEGLContextClientVersion(2);
        renderer = new EngineRenderer(scene, (stage, error) -> {
            // A GL-thread failure must never kill the process: surface it in the editor instead.
            final String detail = stage + " — " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            post(() -> { if (listener != null) listener.onRendererError(detail); });
        });
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setZOrderOnTop(false);
    }

    void setListener(Listener listener) { this.listener = listener; }

    /** Reason the post-processing pass is unavailable, or an empty string when it works. */
    String postNote() { return renderer.postNote(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = x; lastY = downY = y;
                downTime = System.currentTimeMillis();
                moved = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    panX = (event.getX(0) + event.getX(1)) / 2;
                    panY = (event.getY(0) + event.getY(1)) / 2;
                    pinchStart = spacing(event);
                    moved = true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    float cx = (event.getX(0) + event.getX(1)) / 2, cy = (event.getY(0) + event.getY(1)) / 2;
                    float px = cx - panX, py = cy - panY;
                    panX = cx; panY = cy;
                    float spacing = spacing(event);
                    double zoom = pinchStart > 10 ? (pinchStart - spacing) / pinchStart : 0;
                    pinchStart = spacing;
                    scene.moveCamera(0, 0, zoom, !scene.playing, px, py);
                } else {
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x; lastY = y;
                    if (Math.abs(x - downX) + Math.abs(y - downY) > dp(6)) moved = true;
                    scene.moveCamera(-dx * 0.008, dy * 0.006, 0, false, 0, 0);
                }
                if (listener != null) listener.onCameraMoved();
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved && System.currentTimeMillis() - downTime < 250 && !scene.playing) pick(x, y);
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
        }
        return super.onTouchEvent(event);
    }

    private static float spacing(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    void pick(float px, float py) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        double ndcX = 2.0 * px / getWidth() - 1.0;
        double ndcY = 1.0 - 2.0 * py / getHeight();
        // screenRay expects a ray in world space; eye is refreshed by the renderer every frame.
        double[][] ray = scene.screenRay(ndcX, ndcY, (double) getWidth() / getHeight(), 60);
        Instance hit = scene.pick(ray[0], ray[1]);
        if (listener != null) listener.onPick(hit);
    }
}
