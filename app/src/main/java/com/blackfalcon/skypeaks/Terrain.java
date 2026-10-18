package com.blackfalcon.skypeaks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The mountain skyline seen from one spot, computed from elevation data (ray casting with earth curvature and refraction).
 * Split into distance bands so near ridges can be drawn darker than far ones.
 */
public final class Terrain {
    public static final int NAZ = 720, BANDS = 5;
    public static final double STEP_DEG = 360.0 / NAZ;
    public static final float[] BAND_END = {3000f, 10000f, 25000f, 60000f, 140000f};
    private static final double EARTH_R = 6371000.0, REFRACTION = 0.13;

    public final float[][] ang = new float[BANDS][NAZ];   // highest elevation angle (deg) per band and azimuth
    public final float[] env = new float[NAZ];            // skyline = max over bands
    public final float[] envDist = new float[NAZ];
    public final float[] envEle = new float[NAZ];
    public final List<Label> labels = new ArrayList<>();
    public boolean valid = false;
    public double lat, lon, h0;
    public float maxRangeKm = 0;

    public static final class Label {
        public String name = "";
        public float ele, az, ang, dist;
    }

    private static double curv(double d) { return d * d / (2 * EARTH_R) * (1 - REFRACTION); }

    public static Terrain compute(ElevationSource dem, double lat, double lon, List<Peak> peaks) {
        Terrain t = new Terrain();
        t.lat = lat;
        t.lon = lon;
        double e0 = dem.elevation(lat, lon);
        if (Double.isNaN(e0)) return t;
        t.valid = true;
        t.h0 = e0 + 2.0;
        double cosLat = Math.cos(Math.toRadians(lat));
        double reach = 0;
        for (int i = 0; i < NAZ; i++) {
            double az = Math.toRadians(i * STEP_DEG), sa = Math.sin(az), ca = Math.cos(az);
            for (int b = 0; b < BANDS; b++) t.ang[b][i] = -90f;
            float best = -90f, bestD = 0, bestE = 0;
            double d = 0;
            while (true) {
                d += d < 5000 ? 100 : (d < 20000 ? 250 : 500);
                if (d > BAND_END[BANDS - 1]) break;
                double h = dem.elevation(lat + d * ca / 111320.0, lon + d * sa / (111320.0 * cosLat));
                if (Double.isNaN(h)) break;
                reach = Math.max(reach, d);
                float a = (float) Math.toDegrees(Math.atan2(h - t.h0 - curv(d), d));
                int b = 0;
                while (b < BANDS - 1 && d > BAND_END[b]) b++;
                if (a > t.ang[b][i]) t.ang[b][i] = a;
                if (a > best) { best = a; bestD = (float) d; bestE = (float) h; }
            }
            t.env[i] = best;
            t.envDist[i] = bestD;
            t.envEle[i] = bestE;
        }
        t.maxRangeKm = (float) (reach / 1000.0);

        if (peaks != null && !peaks.isEmpty()) {
            for (Peak p : peaks) {
                double dy = (p.lat - lat) * 111320.0, dx = (p.lon - lon) * 111320.0 * cosLat;
                double d = Math.hypot(dx, dy);
                if (d < 400 || d > BAND_END[BANDS - 1]) continue;
                double ele = Double.isNaN(p.ele) ? dem.elevation(p.lat, p.lon) : p.ele;
                if (Double.isNaN(ele)) continue;
                double a = Math.toDegrees(Math.atan2(ele - t.h0 - curv(d), d));
                boolean hidden = false;
                double dd = 0;
                while (!hidden) {
                    dd += dd < 5000 ? 100 : (dd < 20000 ? 250 : 500);
                    if (dd > d - 300) break;
                    double f = dd / d;
                    double h = dem.elevation(lat + (p.lat - lat) * f, lon + (p.lon - lon) * f);
                    if (Double.isNaN(h)) break;
                    if (Math.toDegrees(Math.atan2(h - t.h0 - curv(dd), dd)) > a + 0.12) hidden = true;
                }
                if (hidden) continue;
                Label l = new Label();
                l.name = p.name;
                l.ele = (float) ele;
                l.az = (float) ((Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0);
                l.ang = (float) a;
                l.dist = (float) d;
                t.labels.add(l);
            }
        } else {
            // no peak names: mark the skyline summits with their height
            for (int i = 0; i < NAZ; i++) {
                if (t.env[i] < -5f || t.envDist[i] < 800f || t.envEle[i] < t.h0 + 100) continue;
                boolean top = t.env[(i - 1 + NAZ) % NAZ] < t.env[i];
                float low = t.env[i];
                for (int k = -12; k <= 12 && top; k++) {
                    float v = t.env[(i + k + NAZ) % NAZ];
                    if (v > t.env[i]) top = false;
                    low = Math.min(low, v);
                }
                if (!top || t.env[i] - low < 0.4f) continue;
                Label l = new Label();
                l.ele = t.envEle[i];
                l.az = (float) (i * STEP_DEG);
                l.ang = t.env[i];
                l.dist = t.envDist[i];
                t.labels.add(l);
            }
        }
        Collections.sort(t.labels, new Comparator<Label>() {
            @Override public int compare(Label a, Label b) { return Float.compare(b.ele, a.ele); }
        });
        while (t.labels.size() > 150) t.labels.remove(t.labels.size() - 1);
        return t;
    }

    /** Skyline elevation angle (deg) towards an azimuth, NaN where there is no data. */
    public float envAt(double azDeg) {
        if (!valid) return Float.NaN;
        double x = (((azDeg % 360.0) + 360.0) % 360.0) / STEP_DEG;
        int i0 = (int) Math.floor(x) % NAZ, i1 = (i0 + 1) % NAZ;
        double f = x - Math.floor(x);
        float a = env[i0], b = env[i1];
        if (a < -80f || b < -80f) return Float.NaN;
        return (float) (a * (1 - f) + b * f);
    }
}
