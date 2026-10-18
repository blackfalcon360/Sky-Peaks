package com.blackfalcon.skypeaks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sky + mountain panorama. One pinhole camera looks along `fwd` (east, north, up); the same maths serves the live
 * camera (AR) overlay and the plain panorama. Sun, Moon, planets, their day paths and the terrain skyline are drawn here.
 */
public class SkyView extends View {
    public static final int PATH_N = 145;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] tmp = new float[2];

    private double[] right = {1, 0, 0}, up = {0, 0, 1}, fwd = {0, 1, 0};
    private float fovH = 60f;
    private boolean camera = false, manual = false;
    private double manAz = 180, manAlt = 8;
    private Terrain terrain;
    private final float[] bAz = new float[Astro.COUNT], bAlt = new float[Astro.COUNT];
    private double moonFrac = 0.5;
    private float[] sunPathAz, sunPathAlt, moonPathAz, moonPathAlt;
    private String hud = "";
    private float W, H, F;
    private final ScaleGestureDetector sgd;
    private float lastX, lastY;

    public SkyView(Context c) {
        super(c);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (!camera) { fovH = Math.max(15f, Math.min(110f, fovH / d.getScaleFactor())); invalidate(); }
                return true;
            }
        });
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private static double rad(double d) { return Math.toRadians(d); }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    // ------------------------------------------------------------------ state from the activity
    public void setBasis(double[] r, double[] u, double[] f) { right = r; up = u; fwd = f; invalidate(); }
    public void setFov(float f) { fovH = f; invalidate(); }
    public float getFov() { return fovH; }
    public void setCamera(boolean c) { camera = c; invalidate(); }
    public void setTerrain(Terrain t) { terrain = t; invalidate(); }
    public void setHud(String s) { hud = s; invalidate(); }
    public boolean isManual() { return manual; }

    public void pointTo(double az, double alt) {
        manAz = az;
        manAlt = Math.max(-85, Math.min(85, alt));
        if (manual) updateManual();
        invalidate();
    }

    public void setManual(boolean m) {
        manual = m;
        if (m) updateManual();
        invalidate();
    }

    private void updateManual() {
        double a = rad(manAz), e = rad(manAlt);
        fwd = new double[]{Math.sin(a) * Math.cos(e), Math.cos(a) * Math.cos(e), Math.sin(e)};
        right = new double[]{Math.cos(a), -Math.sin(a), 0};
        up = cross(right, fwd);
    }

    public void setBodies(float[] az, float[] alt, double moonFraction) {
        System.arraycopy(az, 0, bAz, 0, Astro.COUNT);
        System.arraycopy(alt, 0, bAlt, 0, Astro.COUNT);
        moonFrac = moonFraction;
        invalidate();
    }

    public void setPaths(float[] sAz, float[] sAlt, float[] mAz, float[] mAlt) {
        sunPathAz = sAz; sunPathAlt = sAlt; moonPathAz = mAz; moonPathAlt = mAlt;
        invalidate();
    }

    public double viewAz() { return (Math.toDegrees(Math.atan2(fwd[0], fwd[1])) + 360.0) % 360.0; }
    public double viewAlt() { return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fwd[2])))); }

    // ------------------------------------------------------------------ touch (drag only when the sensor is off)
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        sgd.onTouchEvent(ev);
        if (sgd.isInProgress()) return true;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = ev.getX();
                lastY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (manual && ev.getPointerCount() == 1) {
                    double k = Math.toDegrees(1.0 / Math.max(1f, F));
                    manAz = (manAz - (ev.getX() - lastX) * k + 360.0) % 360.0;
                    manAlt = Math.max(-85, Math.min(85, manAlt + (ev.getY() - lastY) * k));
                    updateManual();
                    invalidate();
                }
                lastX = ev.getX();
                lastY = ev.getY();
                return true;
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------ projection
    private boolean proj(double e, double n, double u, float[] out) {
        double dep = e * fwd[0] + n * fwd[1] + u * fwd[2];
        if (dep <= 0.02) return false;
        double x = e * right[0] + n * right[1] + u * right[2], y = e * up[0] + n * up[1] + u * up[2];
        out[0] = (float) (W / 2f + F * x / dep);
        out[1] = (float) (H / 2f - F * y / dep);
        return true;
    }

    private boolean projAA(double azDeg, double altDeg, float[] out) {
        double a = rad(azDeg), e = rad(altDeg);
        return proj(Math.sin(a) * Math.cos(e), Math.cos(a) * Math.cos(e), Math.sin(e), out);
    }

    /** like projAA but never fails (depth is clamped) - used for the filled terrain polygons */
    private void projSoft(double azDeg, double altDeg, float[] out) {
        double a = rad(azDeg), e = rad(altDeg);
        double ex = Math.sin(a) * Math.cos(e), no = Math.cos(a) * Math.cos(e), u = Math.sin(e);
        double dep = Math.max(0.02, ex * fwd[0] + no * fwd[1] + u * fwd[2]);
        double x = ex * right[0] + no * right[1] + u * right[2], y = ex * up[0] + no * up[1] + u * up[2];
        out[0] = (float) (W / 2f + F * x / dep);
        out[1] = (float) (H / 2f - F * y / dep);
    }

    // ------------------------------------------------------------------ colours
    private static int lerp(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private int skyColor() {
        float s = bAlt[0];
        float[] h = {-18, -12, -6, -1, 4, 15};
        int[] c = {0xFF02030A, 0xFF0B1030, 0xFF2A3A78, 0xFFE0805A, 0xFF7FA8D8, 0xFF4A90D9};
        if (s <= h[0]) return c[0];
        for (int i = 1; i < h.length; i++) if (s <= h[i]) return lerp(c[i - 1], c[i], (s - h[i - 1]) / (h[i] - h[i - 1]));
        return c[c.length - 1];
    }

    private void outlined(Canvas cv, String t, float x, float y, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.5f));
        p.setColor(0xCC000000);
        cv.drawText(t, x, y, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        cv.drawText(t, x, y, p);
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas cv) {
        W = getWidth();
        H = getHeight();
        F = (W / 2f) / (float) Math.tan(Math.toRadians(fovH / 2.0));
        int sky = skyColor();
        if (!camera) cv.drawColor(sky);
        drawGrid(cv);
        drawPaths(cv);
        drawBodies(cv);
        if (camera) drawSkylineOutline(cv); else drawTerrain(cv, sky);
        drawAzimuthStrip(cv);
        drawLabels(cv);
        drawHud(cv);
    }

    private void drawGrid(Canvas cv) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        int[] alts = {0, 15, 30, 45, 60, 75};
        for (int alt : alts) {
            p.setColor(alt == 0 ? 0x88FFFFFF : 0x33FFFFFF);
            path.reset();
            boolean pen = false;
            for (int az = 0; az <= 360; az += 3) {
                if (projAA(az, alt, tmp)) {
                    if (!pen) { path.moveTo(tmp[0], tmp[1]); pen = true; } else path.lineTo(tmp[0], tmp[1]);
                } else pen = false;
            }
            cv.drawPath(path, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(dp(11));
        p.setTextAlign(Paint.Align.LEFT);
        double va = viewAz();
        for (int alt = 15; alt <= 75; alt += 15) {
            if (projAA(va, alt, tmp) && tmp[1] > dp(40) && tmp[1] < H - dp(100)) outlined(cv, alt + "\u00B0", dp(6), tmp[1] - dp(2), 0xCCFFFFFF);
        }
    }

    private void drawPaths(Canvas cv) {
        for (int w = 0; w < 2; w++) {
            float[] az = w == 0 ? sunPathAz : moonPathAz, alt = w == 0 ? sunPathAlt : moonPathAlt;
            if (az == null) continue;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(w == 0 ? 0xCCFFD54F : 0xAAB0BEC5);
            path.reset();
            boolean pen = false;
            for (int k = 0; k < PATH_N; k++) {
                if (alt[k] > -30 && projAA(az[k], alt[k], tmp)) {
                    if (!pen) { path.moveTo(tmp[0], tmp[1]); pen = true; } else path.lineTo(tmp[0], tmp[1]);
                } else pen = false;
            }
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(10));
            p.setTextAlign(Paint.Align.CENTER);
            for (int k = 0; k < PATH_N - 1; k += 6) {
                if (alt[k] > -5 && projAA(az[k], alt[k], tmp) && tmp[0] > 0 && tmp[0] < W && tmp[1] > 0 && tmp[1] < H) {
                    p.setColor(w == 0 ? 0xFFFFD54F : 0xFFB0BEC5);
                    cv.drawCircle(tmp[0], tmp[1], dp(2.5f), p);
                    if (w == 0) outlined(cv, String.valueOf(k / 6), tmp[0], tmp[1] - dp(6), 0xFFFFE082);
                }
            }
        }
    }

    private boolean hiddenByTerrain(float az, float alt) {
        if (terrain == null || !terrain.valid) return false;
        float e = terrain.envAt(az);
        return !Float.isNaN(e) && alt < e;
    }

    private void drawBodies(Canvas cv) {
        p.setTextSize(dp(12));
        p.setTextAlign(Paint.Align.CENTER);
        for (int b = Astro.COUNT - 1; b >= 0; b--) {
            if (!projAA(bAz[b], bAlt[b], tmp)) continue;
            float x = tmp[0], y = tmp[1];
            if (x < -dp(40) || x > W + dp(40) || y < -dp(40) || y > H + dp(40)) continue;
            boolean hid = camera && hiddenByTerrain(bAz[b], bAlt[b]);
            int alpha = hid ? 90 : 255;
            if (b == Astro.SUN) {
                p.setStyle(Paint.Style.FILL);
                p.setColor((alpha / 4 << 24) | 0xFFD54F);
                cv.drawCircle(x, y, dp(30), p);
                p.setColor((alpha << 24) | 0xFFE082);
                cv.drawCircle(x, y, dp(15), p);
                p.setColor((alpha << 24) | 0xFFFFFF);
                cv.drawCircle(x, y, dp(9), p);
            } else if (b == Astro.MOON) {
                drawMoon(cv, x, y, dp(15), alpha);
            } else {
                float r = dp(b == 3 || b == 5 ? 6 : (b == 4 || b == 6 ? 5 : (b == 2 ? 4 : 3.5f)));
                p.setStyle(Paint.Style.FILL);
                p.setColor((alpha / 3 << 24) | (Astro.COLOR[b] & 0xFFFFFF));
                cv.drawCircle(x, y, r * 2.2f, p);
                p.setColor((alpha << 24) | (Astro.COLOR[b] & 0xFFFFFF));
                cv.drawCircle(x, y, r, p);
            }
            outlined(cv, Astro.NAME[b] + (hid ? " (hidden)" : ""), x, y + dp(b <= 1 ? 32 : 20), hid ? 0x99FFFFFF : 0xFFFFFFFF);
        }
    }

    private void drawMoon(Canvas cv, float x, float y, float r, int alpha) {
        // direction of the Sun as seen from the Moon, in screen terms: the lit side faces it
        double[] m = vec(bAz[1], bAlt[1]), s = vec(bAz[0], bAlt[0]);
        double d = m[0] * s[0] + m[1] * s[1] + m[2] * s[2];
        double[] t = {s[0] - d * m[0], s[1] - d * m[1], s[2] - d * m[2]};
        double sx = t[0] * right[0] + t[1] * right[1] + t[2] * right[2];
        double sy = t[0] * up[0] + t[1] * up[1] + t[2] * up[2];
        float rot = (float) Math.toDegrees(Math.atan2(-sy, sx));
        cv.save();
        cv.translate(x, y);
        cv.rotate(rot);
        p.setStyle(Paint.Style.FILL);
        p.setColor((alpha << 24) | 0x3A3F4C);
        cv.drawCircle(0, 0, r, p);
        float k = (float) moonFrac;
        float a = r * (1f - 2f * k);
        Path lit = new Path();
        lit.moveTo(0, -r);
        lit.arcTo(new RectF(-r, -r, r, r), -90, 180);
        lit.arcTo(new RectF(-Math.abs(a), -r, Math.abs(a), r), 90, a > 0 ? -180 : 180);
        lit.close();
        p.setColor((alpha << 24) | 0xF2EFE4);
        cv.drawPath(lit, p);
        cv.restore();
    }

    private static double[] vec(double azDeg, double altDeg) {
        double a = Math.toRadians(azDeg), e = Math.toRadians(altDeg);
        return new double[]{Math.sin(a) * Math.cos(e), Math.cos(a) * Math.cos(e), Math.sin(e)};
    }

    private void drawTerrain(Canvas cv, int sky) {
        if (terrain == null || !terrain.valid) return;
        double va = viewAz();
        double halfDiag = Math.toDegrees(Math.atan(Math.hypot(W, H) / 2.0 / F));
        double span = Math.abs(viewAlt()) > 55 ? 180 : Math.min(180, halfDiag + 20);
        int i0 = (int) Math.floor((va - span) / Terrain.STEP_DEG), i1 = (int) Math.ceil((va + span) / Terrain.STEP_DEG);
        int haze = lerp(sky, 0xFFFFFFFF, 0.18f);
        for (int b = Terrain.BANDS - 1; b >= 0; b--) {
            path.reset();
            boolean first = true;
            for (int i = i0; i <= i1; i++) {
                int idx = ((i % Terrain.NAZ) + Terrain.NAZ) % Terrain.NAZ;
                projSoft(i * Terrain.STEP_DEG, Math.max(terrain.ang[b][idx], -60f), tmp);
                if (first) { path.moveTo(tmp[0], tmp[1]); first = false; } else path.lineTo(tmp[0], tmp[1]);
            }
            for (int i = i1; i >= i0; i--) {
                projSoft(i * Terrain.STEP_DEG, -60, tmp);
                path.lineTo(tmp[0], tmp[1]);
            }
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(lerp(0xFF0F160F, haze, b / (float) (Terrain.BANDS - 1) * 0.9f));
            cv.drawPath(path, p);
        }
    }

    private void drawSkylineOutline(Canvas cv) {
        if (terrain == null || !terrain.valid) return;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0xDDFFEB3B);
        path.reset();
        boolean pen = false;
        double va = viewAz();
        int i0 = (int) Math.floor((va - 90) / Terrain.STEP_DEG), i1 = (int) Math.ceil((va + 90) / Terrain.STEP_DEG);
        for (int i = i0; i <= i1; i++) {
            int idx = ((i % Terrain.NAZ) + Terrain.NAZ) % Terrain.NAZ;
            if (terrain.env[idx] > -80f && projAA(i * Terrain.STEP_DEG, terrain.env[idx], tmp)) {
                if (!pen) { path.moveTo(tmp[0], tmp[1]); pen = true; } else path.lineTo(tmp[0], tmp[1]);
            } else pen = false;
        }
        cv.drawPath(path, p);
    }

    private void drawAzimuthStrip(Canvas cv) {
        float y0 = H - dp(98);
        String[] card = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        double va = viewAz(), vl = viewAlt();
        p.setTextAlign(Paint.Align.CENTER);
        for (int az = 0; az < 360; az += 5) {
            if (!projAA(az, vl, tmp)) continue;
            float x = tmp[0];
            if (x < -dp(20) || x > W + dp(20)) continue;
            boolean big = az % 15 == 0;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(big ? 2 : 1));
            p.setColor(0xCCFFFFFF);
            cv.drawLine(x, y0, x, y0 + dp(big ? 10 : 6), p);
            if (big) {
                p.setTextSize(dp(az % 45 == 0 ? 15 : 11));
                outlined(cv, az % 45 == 0 ? card[az / 45] : String.valueOf(az), x, y0 - dp(5), az == 0 ? 0xFFFF8A80 : 0xFFFFFFFF);
            }
        }
        // centre marker
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.5f));
        p.setColor(0x88FFFFFF);
        cv.drawLine(W / 2f - dp(10), H / 2f, W / 2f + dp(10), H / 2f, p);
        cv.drawLine(W / 2f, H / 2f - dp(10), W / 2f, H / 2f + dp(10), p);
    }

    private void drawLabels(Canvas cv) {
        if (terrain == null || !terrain.valid) return;
        p.setTextAlign(Paint.Align.CENTER);
        List<RectF> used = new ArrayList<>();
        int shown = 0;
        for (Terrain.Label l : terrain.labels) {
            if (!projAA(l.az, l.ang, tmp)) continue;
            float px = tmp[0], py = tmp[1];
            if (px < -dp(10) || px > W + dp(10) || py < dp(60) || py > H - dp(110)) continue;
            String t1 = l.name;
            String t2 = Math.round(l.ele) + " m \u00B7 " + String.format(Locale.US, "%.1f km", l.dist / 1000f);
            p.setTextSize(dp(12));
            float w1 = p.measureText(t1), w2;
            p.setTextSize(dp(10));
            w2 = p.measureText(t2);
            float w = Math.max(w1, w2) + dp(8), h = t1.length() > 0 ? dp(28) : dp(15);
            for (int lvl = 0; lvl < 4; lvl++) {
                float bottom = py - dp(10) - lvl * dp(32);
                RectF r = new RectF(px - w / 2, bottom - h, px + w / 2, bottom);
                if (r.top < dp(45)) break;
                boolean hit = false;
                for (RectF u : used) if (RectF.intersects(u, r)) { hit = true; break; }
                if (hit) continue;
                used.add(r);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(0xAAFFFFFF);
                cv.drawLine(px, py - dp(3), px, bottom, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                Path tri = new Path();
                tri.moveTo(px, py - dp(2));
                tri.lineTo(px - dp(4), py - dp(9));
                tri.lineTo(px + dp(4), py - dp(9));
                tri.close();
                cv.drawPath(tri, p);
                float ty = bottom - dp(3);
                p.setTextSize(dp(10));
                outlined(cv, t2, px, ty, 0xFFB3E5FC);
                if (t1.length() > 0) {
                    p.setTextSize(dp(12));
                    p.setFakeBoldText(true);
                    outlined(cv, t1, px, ty - dp(13), 0xFFFFFFFF);
                    p.setFakeBoldText(false);
                }
                shown++;
                break;
            }
            if (shown >= 45) break;
        }
    }

    private void drawHud(Canvas cv) {
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(12));
        float y = dp(18);
        String line1 = String.format(Locale.US, "Az %.0f\u00B0  Alt %.0f\u00B0  FOV %.0f\u00B0", viewAz(), viewAlt(), fovH);
        outlined(cv, line1, dp(8), y, 0xFFFFFFFF);
        for (String s : hud.split("\n")) {
            if (s.length() == 0) continue;
            y += dp(15);
            outlined(cv, s, dp(8), y, 0xFFE0E0E0);
        }
    }
}
