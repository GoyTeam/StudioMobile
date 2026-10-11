package dev.falix.studiomobile;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;

/**
 * Viewport surface for the Roblox engine.
 *
 * Touch model, matching Roblox Studio's on a phone:
 *  - one finger drags the world: yaw follows the finger and pitch is *not* inverted, so dragging
 *    down looks down at the scene;
 *  - two fingers pan and pinch-zoom the camera;
 *  - a tap selects the part under it;
 *  - touching a move/rotate/scale handle starts a transform drag, and with the grab tool a drag on
 *    the selected part slides it (both snap when snapping is on);
 *  - the on-screen joystick (owned by the activity) flies the editor camera while nothing is
 *    dragged, so a drag on empty space still orbits.
 */
final class EngineView extends GLSurfaceView {
    interface Listener { void onPick(Instance hit); void onCameraMoved(); void onRendererError(String message); }

    private static final double FOV = 60;

    final EngineScene scene;
    private final EngineRenderer renderer;
    private final EngineTextures textures;
    private Listener listener;
    private float lastX, lastY, downX, downY, panX, panY, pinchStart;
    private long downTime;
    private boolean moved, dragging, pendingGrab;
    private float[] pendingGrabPoint;

    EngineView(Context context, EngineScene scene) {
        super(context);
        this.scene = scene;
        setEGLContextClientVersion(2);
        textures = new EngineTextures(context.getCacheDir());
        // A creator key lets EngineTextures use Open Cloud asset delivery instead of the public CDN.
        try {
            String key = new CredentialVault(context).load();
            if (key != null && !key.isEmpty()) textures.setApiKey(key);
        } catch (Throwable ignored) { }
        renderer = new EngineRenderer(scene, textures, (stage, error) -> {
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

    /** Streamed-texture stats for the editor's engine dialog. */
    String textureNote() { return textures.note(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = x; lastY = downY = y;
                downTime = System.currentTimeMillis();
                moved = false;
                pendingGrab = false;
                if (!scene.playing && grabHandle(x, y)) return true;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    panX = (event.getX(0) + event.getX(1)) / 2;
                    panY = (event.getY(0) + event.getY(1)) / 2;
                    pinchStart = spacing(event);
                    moved = true;
                    scene.endGizmoDrag();
                    dragging = false;
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
                } else if (dragging) {
                    double[][] ray = ray(x, y);
                    if (ray != null) scene.updateGizmoDrag(ray[0], ray[1]);
                } else {
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x; lastY = y;
                    if (Math.abs(x - downX) + Math.abs(y - downY) > dp(6)) moved = true;
                    if (moved && pendingGrab) startPendingGrab();
                    if (dragging) {
                        double[][] ray = ray(x, y);
                        if (ray != null) scene.updateGizmoDrag(ray[0], ray[1]);
                    } else {
                        // Not inverted: dragging down looks down, so the scene follows the finger.
                        scene.moveCamera(-dx * 0.008, -dy * 0.006, 0, false, 0, 0);
                    }
                }
                if (listener != null) listener.onCameraMoved();
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    scene.endGizmoDrag();
                    dragging = false;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } else if (!moved && System.currentTimeMillis() - downTime < 250) {
                    pick(x, y);
                }
                pendingGrab = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) { scene.endGizmoDrag(); dragging = false; }
                pendingGrab = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Studio's transform handles: with move/rotate/scale an axis handle under the finger is grabbed,
     * with grab a part under the finger is dragged. Returns true when the touch was consumed.
     */
    private boolean grabHandle(float x, float y) {
        if (scene.playing || getWidth() <= 0 || getHeight() <= 0) return false;
        int tool = scene.gizmoTool;
        if (tool == EngineGizmo.GRAB) {
            double[][] ray = ray(x, y);
            if (ray == null) return false;
            Instance hit = scene.pick(ray[0], ray[1]);
            if (hit == null) return false;
            if (hit != scene.selection) {
                scene.selection = hit;
                if (listener != null) listener.onPick(hit);
            }
            // Grab starts on the first movement so a tap still only selects.
            pendingGrab = true;
            pendingGrabPoint = new float[]{x, y};
            return true;
        }
        Instance selection = scene.selection;
        if (selection == null || !selection.isRenderable()) return false;
        double[] c = selection.cframe();
        double[] centre = {c[0], c[1], c[2]};
        double distance = length(centre[0] - scene.camEye[0], centre[1] - scene.camEye[1], centre[2] - scene.camEye[2]);
        double handle = EngineGizmo.handleLength(distance, FOV, getHeight(), 1);
        double radius = tool == EngineGizmo.ROTATE ? handle * 0.9 : handle;
        int axis = tool == EngineGizmo.ROTATE
                ? EngineGizmo.pickRing(scene.viewProjection, centre, getWidth(), getHeight(), radius, dp(30), x, y)
                : EngineGizmo.pickAxis(scene.viewProjection, centre, getWidth(), getHeight(), handle, dp(26), x, y);
        if (axis < 0) return false;
        double[][] ray = ray(x, y);
        if (ray == null) return false;
        if (!scene.beginGizmoDrag(tool, axis, ray[0], ray[1], handle)) return false;
        dragging = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        return true;
    }

    private void startPendingGrab() {
        pendingGrab = false;
        if (pendingGrabPoint == null) return;
        double[][] ray = ray(pendingGrabPoint[0], pendingGrabPoint[1]);
        if (ray == null) return;
        if (scene.beginGizmoDrag(EngineGizmo.GRAB, -1, ray[0], ray[1], 1)) {
            dragging = true;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private static double length(double x, double y, double z) { return Math.sqrt(x * x + y * y + z * z); }

    private static float spacing(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    /** World-space ray through a screen point, or null when the viewport has no size yet. */
    private double[][] ray(float px, float py) {
        if (getWidth() <= 0 || getHeight() <= 0) return null;
        double ndcX = 2.0 * px / getWidth() - 1.0;
        double ndcY = 1.0 - 2.0 * py / getHeight();
        // screenRay expects a ray in world space; eye is refreshed by the renderer every frame.
        double[][] ray = scene.screenRay(ndcX, ndcY, (double) getWidth() / getHeight(), FOV);
        if (ray == null || ray[0] == null || ray[1] == null) return null;
        return ray;
    }

    void pick(float px, float py) {
        double[][] ray = ray(px, py);
        if (ray == null) return;
        Instance hit = scene.pick(ray[0], ray[1]);
        if (listener != null) listener.onPick(hit);
    }
}
