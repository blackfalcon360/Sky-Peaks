package com.blackfalcon.skypeaks;

import android.content.Context;
import android.graphics.Bitmap;
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

/** Offline relief map made from the downloaded terrain tiles: pan, pinch-zoom, peaks, your position and where the Sun/Moon are. */
public class MapView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private Dem dem;
    private double gx, gy;                   // centre, global pixels at zoom Dem.Z
    private float scale = 1f;                // screen px per global px
    private double myLat, myLon;
    private boolean hasMe = false, showHeading = false;
    private float heading = 0f;
    private float sunAz = 0, sunAlt = -90, moonAz = 0, moonAlt = -90;
    private List<Peak> peaks = new ArrayList<>();
    private boolean centred = false;
    private final ScaleGestureDetector sgd;
    private float lastX, lastY;

    public MapView(Context c) {
        super(c);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                scale = Math.max(0.2f, Math.min(8f, scale * d.getScaleFactor()));
                invalidate();
                return true;
            }
        });
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setDem(Dem d) { dem = d; invalidate(); }
    public void setPeaks(List<Peak> list) { peaks = list; invalidate(); }

    public void setMe(double lat, double lon, boolean has) {
        myLat = lat; myLon = lon; hasMe = has;
        if (has && !centred) centerOnMe();
        invalidate();
    }

    public void setHeading(float h, boolean show) { heading = h; showHeading = show; invalidate(); }

    public void setSky(float sAz, float sAlt, float mAz, float mAlt) {
        sunAz = sAz; sunAlt = sAlt; moonAz = mAz; moonAlt = mAlt;
        invalidate();
    }

    public void centerOnMe() {
        if (!hasMe) return;
        double[] g = Dem.pixel(myLat, myLon);
        gx = g[0];
        gy = g[1];
        centred = true;
        invalidate();
    }

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
                if (ev.getPointerCount() == 1) {
                    gx -= (ev.getX() - lastX) / scale;
                    gy -= (ev.getY() - lastY) / scale;
                    invalidate();
                }
                lastX = ev.getX();
                lastY = ev.getY();
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        cv.drawColor(0xFF06122A);
        if (dem == null) return;
        double gx0 = gx - W / 2.0 / scale, gy0 = gy - H / 2.0 / scale;
        int tx0 = (int) Math.floor(gx0 / 256), tx1 = (int) Math.floor((gx0 + W / scale) / 256);
        int ty0 = (int) Math.floor(gy0 / 256), ty1 = (int) Math.floor((gy0 + H / scale) / 256);
        int drawn = 0;
        if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) <= 64) {
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    Bitmap b = dem.relief(tx, ty);
                    if (b == null) continue;
                    RectF dst = new RectF((float) ((tx * 256.0 - gx0) * scale), (float) ((ty * 256.0 - gy0) * scale),
                            (float) (((tx + 1) * 256.0 - gx0) * scale) + 1f, (float) (((ty + 1) * 256.0 - gy0) * scale) + 1f);
                    cv.drawBitmap(b, null, dst, bp);
                    drawn++;
                }
            }
        }
        if (drawn == 0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF9E9E9E);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(14));
            cv.drawText("No offline map here yet", W / 2f, H / 2f - dp(8), p);
            cv.drawText("Open the Offline tab and download", W / 2f, H / 2f + dp(12), p);
        }

        // peaks
        p.setTextAlign(Paint.Align.CENTER);
        int shown = 0;
        for (Peak pk : peaks) {
            double[] g = Dem.pixel(pk.lat, pk.lon);
            float x = (float) ((g[0] - gx0) * scale), y = (float) ((g[1] - gy0) * scale);
            if (x < 0 || x > W || y < 0 || y > H) continue;
            if (scale < 0.6f && shown > 40) continue;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1B1B1B);
            path.reset();
            path.moveTo(x, y - dp(6));
            path.lineTo(x - dp(5), y + dp(3));
            path.lineTo(x + dp(5), y + dp(3));
            path.close();
            cv.drawPath(path, p);
            if (scale >= 1.1f && shown < 60) {
                p.setTextSize(dp(11));
                label(cv, pk.name, x, y - dp(9), 0xFFFFFFFF);
            }
            shown++;
            if (shown > 250) break;
        }

        if (hasMe) {
            double[] g = Dem.pixel(myLat, myLon);
            float mx = (float) ((g[0] - gx0) * scale), my = (float) ((g[1] - gy0) * scale);
            float len = Math.min(W, H) * 0.42f;
            // Sun and Moon directions
            for (int w = 0; w < 2; w++) {
                float az = w == 0 ? sunAz : moonAz, alt = w == 0 ? sunAlt : moonAlt;
                double a = Math.toRadians(az);
                float ex = mx + (float) (Math.sin(a) * len), ey = my - (float) (Math.cos(a) * len);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(w == 0 ? (alt > 0 ? 0xFFFFD54F : 0x66FFD54F) : (alt > 0 ? 0xFFFFFFFF : 0x66FFFFFF));
                cv.drawLine(mx, my, ex, ey, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(ex, ey, dp(9), p);
                p.setTextSize(dp(11));
                label(cv, w == 0 ? "Sun" : "Moon", ex, ey - dp(13), 0xFFFFFFFF);
            }
            if (showHeading) {
                double a = Math.toRadians(heading);
                float r = dp(70), half = (float) Math.toRadians(26);
                path.reset();
                path.moveTo(mx, my);
                path.lineTo(mx + (float) (Math.sin(a - half) * r), my - (float) (Math.cos(a - half) * r));
                path.lineTo(mx + (float) (Math.sin(a + half) * r), my - (float) (Math.cos(a + half) * r));
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x554FC3F7);
                cv.drawPath(path, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(mx, my, dp(8), p);
            p.setColor(0xFF4FC3F7);
            cv.drawCircle(mx, my, dp(5), p);
        }

        // north + scale bar
        p.setTextSize(dp(15));
        label(cv, "N \u2191", W - dp(28), dp(24), 0xFFFF8A80);
        double mpp = Dem.metresPerPixel(hasMe ? myLat : Dem.latLon(gx, gy)[0]) / scale;
        double[] nice = {100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000};
        double pick = nice[0];
        for (double n : nice) if (n / mpp <= dp(150)) pick = n;
        float bl = (float) (pick / mpp);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3));
        p.setColor(0xFFFFFFFF);
        cv.drawLine(dp(12), H - dp(18), dp(12) + bl, H - dp(18), p);
        p.setTextSize(dp(11));
        p.setTextAlign(Paint.Align.LEFT);
        p.setStyle(Paint.Style.FILL);
        cv.drawText(pick >= 1000 ? String.format(Locale.US, "%.0f km", pick / 1000) : String.format(Locale.US, "%.0f m", pick), dp(12), H - dp(24), p);
    }

    private void label(Canvas cv, String t, float x, float y, int color) {
        p.setTextAlign(Paint.Align.CENTER);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.5f));
        p.setColor(0xCC000000);
        cv.drawText(t, x, y, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        cv.drawText(t, x, y, p);
    }
}
