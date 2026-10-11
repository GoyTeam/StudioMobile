package dev.falix.studiomobile;

/**
 * Studio-style transform handles. Pure math (no Android types) so the drag behaviour is unit
 * tested: the same functions screen-project the handles, pick them and convert a touch into an
 * axis translation, an axis rotation or a scale factor.
 *
 * Handles keep a constant on-screen size: {@link #handleLength} scales the world length from the
 * camera distance and field of view, exactly like Studio's gizmos do.
 */
final class EngineGizmo {
    static final int MOVE = 0, ROTATE = 1, SCALE = 2, GRAB = 3;
    /** On-screen half length of an axis handle, in pixels at a 1080p-ish reference height. */
    static final double HANDLE_PIXELS = 78;

    private EngineGizmo() { }

    static double[] axis(int index) {
        switch (index) {
            case 0: return new double[]{1, 0, 0};
            case 1: return new double[]{0, 1, 0};
            default: return new double[]{0, 0, 1};
        }
    }

    /** World length of a handle that stays {@link #HANDLE_PIXELS} pixels long at this camera distance. */
    static double handleLength(double distance, double fovDeg, int viewportHeight, double uiScale) {
        double halfHeight = distance * Math.tan(Math.toRadians(fovDeg) / 2);
        double pixels = HANDLE_PIXELS * Math.max(0.5, uiScale);
        return Math.max(0.6, 2 * halfHeight * pixels / Math.max(1, viewportHeight));
    }

    /** Screen position of a world point as {x, y, depth}, or null when it is behind the camera. */
    static double[] project(float[] mvp, double[] world, int width, int height) {
        double x = world[0], y = world[1], z = world[2];
        double cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12];
        double cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13];
        double cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15];
        if (cw <= 1e-6) return null;
        return new double[]{(cx / cw * 0.5 + 0.5) * width, (1 - (cy / cw * 0.5 + 0.5)) * height, cw};
    }

    static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lengthSq = dx * dx + dy * dy;
        if (lengthSq < 1e-9) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSq));
        return Math.hypot(px - (ax + dx * t), py - (ay + dy * t));
    }

    /**
     * Axis handle under a touch. Returns 0..2 for X/Y/Z or -1. The closest handle within
     * `pickRadiusPx` wins, which keeps overlapping axes usable at any camera angle.
     */
    static int pickAxis(float[] mvp, double[] center, int width, int height, double length, double pickRadiusPx, double px, double py) {
        double[] origin = project(mvp, center, width, height);
        if (origin == null) return -1;
        int best = -1;
        double bestDistance = pickRadiusPx;
        for (int axis = 0; axis < 3; axis++) {
            double[] d = axis(axis);
            double[] tip = project(mvp, new double[]{center[0] + d[0] * length, center[1] + d[1] * length, center[2] + d[2] * length}, width, height);
            if (tip == null) continue;
            double distance = distanceToSegment(px, py, origin[0], origin[1], tip[0], tip[1]);
            if (distance < bestDistance) { bestDistance = distance; best = axis; }
        }
        return best;
    }

    /** Rotation ring under a touch, projected as a screen polygon. Returns 0..2 or -1. */
    static int pickRing(float[] mvp, double[] center, int width, int height, double radius, double pickRadiusPx, double px, double py) {
        int best = -1;
        double bestDistance = pickRadiusPx;
        for (int axis = 0; axis < 3; axis++) {
            double distance = ringScreenDistance(mvp, center, width, height, radius, axis, px, py);
            if (distance < bestDistance) { bestDistance = distance; best = axis; }
        }
        return best;
    }

    /**
     * Distance from the touch ray to every segment of a projected ring, used to pick a rotation
     * ring with a stable tolerance at any camera angle.
     */
    static double ringScreenDistance(float[] mvp, double[] center, int width, int height, double radius, int axis, double px, double py) {
        double[] a = axis(axis);
        double[] u = Math.abs(a[0]) > 0.5 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        double[] v = cross(a, u);
        double closest = Double.MAX_VALUE;
        int steps = 64;
        double[] previous = null;
        for (int i = 0; i <= steps; i++) {
            double t = i * 2 * Math.PI / steps;
            double[] point = {center[0] + (u[0] * Math.cos(t) + v[0] * Math.sin(t)) * radius,
                    center[1] + (u[1] * Math.cos(t) + v[1] * Math.sin(t)) * radius,
                    center[2] + (u[2] * Math.cos(t) + v[2] * Math.sin(t)) * radius};
            double[] screen = project(mvp, point, width, height);
            if (screen != null && previous != null) closest = Math.min(closest, distanceToSegment(px, py, previous[0], previous[1], screen[0], screen[1]));
            previous = screen;
        }
        return closest;
    }

    /** Parameter along the axis line closest to the touch ray; the drag delta is the change of it. */
    static double rayAxisParameter(double[] rayOrigin, double[] rayDir, double[] axisOrigin, double[] axisDirection) {
        double[] w = {rayOrigin[0] - axisOrigin[0], rayOrigin[1] - axisOrigin[1], rayOrigin[2] - axisOrigin[2]};
        double b = dot(rayDir, axisDirection);
        double d = dot(rayDir, w);
        double e = dot(axisDirection, w);
        double denominator = 1 - b * b;
        if (Math.abs(denominator) < 1e-6) return 0;             // looking straight down the axis
        return (e - b * d) / denominator;
    }

    /** Angle (radians) of the touch ray on the plane of a rotation ring, or NaN when it misses. */
    static double ringAngle(double[] rayOrigin, double[] rayDir, double[] center, int axis) {
        double[] n = axis(axis);
        double denominator = dot(rayDir, n);
        if (Math.abs(denominator) < 1e-6) return Double.NaN;
        double[] toCenter = {center[0] - rayOrigin[0], center[1] - rayOrigin[1], center[2] - rayOrigin[2]};
        double t = dot(toCenter, n) / denominator;
        if (t <= 0) return Double.NaN;
        double[] hit = {rayOrigin[0] + rayDir[0] * t - center[0], rayOrigin[1] + rayDir[1] * t - center[1], rayOrigin[2] + rayDir[2] * t - center[2]};
        double[] u = Math.abs(n[0]) > 0.5 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        double[] v = cross(n, u);
        return Math.atan2(dot(hit, v), dot(hit, u));
    }

    /** Intersection of the touch ray with the plane through `center` facing the camera, or null. */
    static double[] rayPlanePoint(double[] rayOrigin, double[] rayDir, double[] center, double[] planeNormal) {
        double denominator = dot(rayDir, planeNormal);
        if (Math.abs(denominator) < 1e-6) return null;
        double[] toCenter = {center[0] - rayOrigin[0], center[1] - rayOrigin[1], center[2] - rayOrigin[2]};
        double t = dot(toCenter, planeNormal) / denominator;
        if (t <= 0) return null;
        return new double[]{rayOrigin[0] + rayDir[0] * t, rayOrigin[1] + rayDir[1] * t, rayOrigin[2] + rayDir[2] * t};
    }

    /** Rotation matrix (row major, 9 floats) around X, Y or Z by `angle` radians. */
    static double[] rotationMatrix(int axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        switch (axis) {
            case 0: return new double[]{1, 0, 0, 0, c, -s, 0, s, c};
            case 1: return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
            default: return new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
        }
    }

    /** Multiplies two row-major 3x3 matrices (a * b). */
    static double[] multiply(double[] a, double[] b) {
        double[] out = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) {
                double v = 0;
                for (int k = 0; k < 3; k++) v += a[r * 3 + k] * b[k * 3 + c];
                out[r * 3 + c] = v;
            }
        return out;
    }

    /** Copies a 12-element CFrame with its 3x3 rotation replaced (column-major layout used by Instance). */
    static double[] withRotation(double[] cframe, double[] rotationColumnMajor) {
        double[] out = cframe.clone();
        out[3] = rotationColumnMajor[0]; out[4] = rotationColumnMajor[1]; out[5] = rotationColumnMajor[2];
        out[6] = rotationColumnMajor[3]; out[7] = rotationColumnMajor[4]; out[8] = rotationColumnMajor[5];
        out[9] = rotationColumnMajor[6]; out[10] = rotationColumnMajor[7]; out[11] = rotationColumnMajor[8];
        return out;
    }

    /** Rotation stored in a CFrame (column-major, 9 values) as a row-major matrix. */
    static double[] rotationOf(double[] cframe) {
        return new double[]{cframe[3], cframe[6], cframe[9], cframe[4], cframe[7], cframe[10], cframe[5], cframe[8], cframe[11]};
    }

    /** Row-major rotation back into the column-major CFrame layout. */
    static double[] rotationToCframeLayout(double[] rowMajor) {
        return new double[]{rowMajor[0], rowMajor[3], rowMajor[6], rowMajor[1], rowMajor[4], rowMajor[7], rowMajor[2], rowMajor[5], rowMajor[8]};
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
