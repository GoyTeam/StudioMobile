package dev.falix.studiomobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Analog movement stick: reports x (right positive) and y (forward positive), each -1..1. */
final class JoystickView extends View {
    interface Listener { void onMove(double x, double y); }

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private float knobX, knobY;
    private boolean active;

    JoystickView(Context context) {
        super(context);
        base.setColor(0x55202633);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp(2));
        rim.setColor(0x99ffffff);
        knob.setColor(0xCC4F7DFF);
        setContentDescription("Movement joystick");
    }

    void setListener(Listener listener) { this.listener = listener; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2, cy = h / 2;
        float radius = Math.min(w, h) / 2 - dp(6);
        canvas.drawCircle(cx, cy, radius, base);
        canvas.drawCircle(cx, cy, radius, rim);
        float kx = cx + knobX * radius, ky = cy + knobY * radius;
        canvas.drawCircle(kx, ky, radius * 0.42f, knob);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float w = getWidth(), h = getHeight();
        float radius = Math.min(w, h) / 2 - dp(6);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                active = true;
                float dx = event.getX() - w / 2, dy = event.getY() - h / 2;
                float len = (float) Math.hypot(dx, dy);
                if (len > radius) { dx = dx / len * radius; dy = dy / len * radius; }
                knobX = radius <= 0 ? 0 : dx / radius;
                knobY = radius <= 0 ? 0 : dy / radius;
                publish();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = false;
                knobX = knobY = 0;
                publish();
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void publish() { if (listener != null) listener.onMove(knobX, -knobY); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) dp(132);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }
}
