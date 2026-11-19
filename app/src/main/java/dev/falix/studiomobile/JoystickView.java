package dev.falix.studiomobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

final class JoystickView extends View {
    interface Listener { void onMove(double x, double y); }

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private float knobX, knobY;
    private boolean active;
    private int accentColor = 0xFF4F7DFF;

    JoystickView(Context context) {
        super(context);
        base.setColor(0x44141821);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp(1.5f));
        rim.setColor(0x66DCE4F2);
        knob.setColor(0xAA4F7DFF);
        knobRim.setStyle(Paint.Style.STROKE);
        knobRim.setStrokeWidth(dp(1.5f));
        knobRim.setColor(0xAAFFFFFF);
        setContentDescription("Movement joystick");
    }

    void setListener(Listener listener) { this.listener = listener; }
    void setAccentColor(int color) { this.accentColor = color; knob.setColor(MaterialYou.withAlpha(color, 0xCC)); invalidate(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(4);
        if (radius <= 2) return;
        base.setColor(active ? 0x661A2233 : 0x3D141821);
        rim.setColor(active ? MaterialYou.withAlpha(accentColor, 0xCC) : 0x55DCE4F2);
        canvas.drawCircle(cx, cy, radius, base);
        canvas.drawCircle(cx, cy, radius, rim);
        float kx = cx + knobX * (radius * 0.65f), ky = cy + knobY * (radius * 0.65f);
        float kr = radius * 0.36f;
        canvas.drawCircle(kx, ky, kr, knob);
        canvas.drawCircle(kx, ky, kr, knobRim);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float w = getWidth(), h = getHeight();
        float radius = Math.min(w, h) / 2f - dp(4);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                active = true;
                float dx = event.getX() - w / 2f, dy = event.getY() - h / 2f;
                float len = (float) Math.hypot(dx, dy);
                if (len > radius && radius > 0) { dx = dx / len * radius; dy = dy / len * radius; }
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
        int size = (int) dp(96);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }
}
