package com.blackfalcon.skypeaks;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline terrain store. Elevation comes from the open "Terrarium" terrain tiles (AWS Open Data, built from SRTM and other public
 * sources). Tiles are downloaded once, kept in the app's private folder and used with no internet afterwards.
 * Peak names come from OpenStreetMap (Overpass API) and are stored in small JSON files next to the tiles.
 */
public final class Dem implements ElevationSource {
    public static final int Z = 10;
    private static final int N = 1 << Z;
    private static final String UA = "SkyPeaks-BlackFalcon/1.0";

    private final File dir;
    private final Map<Long, short[]> cache = new LinkedHashMap<Long, short[]>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, short[]> e) { return size() > 96; }
    };
    private final Map<Long, Bitmap> relief = new LinkedHashMap<Long, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> e) { return size() > 40; }
    };
    private final Set<Long> missing = new HashSet<>();

    public interface Progress { void on(int done, int total); }

    public Dem(File filesDir) {
        dir = new File(filesDir, "dem");
        dir.mkdirs();
    }

    private static long key(int x, int y) { return (((long) x) << 32) | (y & 0xFFFFFFFFL); }
    private File tileFile(int x, int y) { return new File(dir, x + "_" + y + ".png"); }

    /** Global pixel position {x, y} at zoom Z (Web-Mercator). */
    public static double[] pixel(double lat, double lon) {
        double la = Math.max(-85.05, Math.min(85.05, lat));
        double s = Math.sin(Math.toRadians(la));
        double x = (lon + 180.0) / 360.0 * N * 256.0;
        double y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * N * 256.0;
        return new double[]{x, y};
    }

    /** {lat, lon} of a global pixel. */
    public static double[] latLon(double gx, double gy) {
        double lon = gx / (N * 256.0) * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * gy / (N * 256.0)))));
        return new double[]{lat, lon};
    }

    public static double metresPerPixel(double lat) { return 156543.03392 * Math.cos(Math.toRadians(lat)) / N; }

    // ------------------------------------------------------------------ reading elevations
    public synchronized short[] tile(int x, int y) {
        if (x < 0 || y < 0 || x >= N || y >= N) return null;
        long k = key(x, y);
        short[] t = cache.get(k);
        if (t != null) return t;
        if (missing.contains(k)) return null;
        File f = tileFile(x, y);
        if (!f.exists()) { missing.add(k); return null; }
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null || b.getWidth() != 256 || b.getHeight() != 256) { missing.add(k); return null; }
        int[] px = new int[256 * 256];
        b.getPixels(px, 0, 256, 0, 0, 256, 256);
        b.recycle();
        t = new short[256 * 256];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            t[i] = (short) Math.round(((c >> 16) & 255) * 256 + ((c >> 8) & 255) + (c & 255) / 256f - 32768f);
        }
        cache.put(k, t);
        return t;
    }

    private double px(int gx, int gy) {
        short[] t = tile(gx >> 8, gy >> 8);
        return t == null ? Double.NaN : t[(gy & 255) * 256 + (gx & 255)];
    }

    @Override
    public double elevation(double lat, double lon) {
        double[] g = pixel(lat, lon);
        double gx = g[0] - 0.5, gy = g[1] - 0.5;
        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy);
        double fx = gx - x0, fy = gy - y0;
        double a = px(x0, y0), b = px(x0 + 1, y0), c = px(x0, y0 + 1), d = px(x0 + 1, y0 + 1);
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d)) return Double.NaN;
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    /** forget "missing" markers and cached bitmaps (after a download or delete) */
    public synchronized void reset() {
        missing.clear();
        cache.clear();
        relief.clear();
    }

    public int tileCount() {
        String[] l = dir.list();
        int n = 0;
        if (l != null) for (String s : l) if (s.endsWith(".png")) n++;
        return n;
    }

    public long sizeBytes() {
        File[] l = dir.listFiles();
        long n = 0;
        if (l != null) for (File f : l) n += f.length();
        return n;
    }

    public synchronized void deleteAll() {
        File[] l = dir.listFiles();
        if (l != null) for (File f : l) f.delete();
        reset();
    }

    // ------------------------------------------------------------------ relief map bitmaps (for the offline map tab)
    private static int[] RAMP_H = {-1, 1, 400, 1000, 2000, 3000, 4000, 5000};
    private static int[] RAMP_C = {0x2B5C8A, 0x6FAF6B, 0xA9C27A, 0xD2C28A, 0xB79F7C, 0xA3958A, 0xD8D4D0, 0xFFFFFF};

    private static int ramp(float h) {
        if (h <= RAMP_H[0]) return RAMP_C[0];
        for (int i = 1; i < RAMP_H.length; i++) {
            if (h <= RAMP_H[i]) {
                float f = (h - RAMP_H[i - 1]) / (float) (RAMP_H[i] - RAMP_H[i - 1]);
                int a = RAMP_C[i - 1], b = RAMP_C[i];
                int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
                int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
                int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
                return (r << 16) | (g << 8) | bl;
            }
        }
        return RAMP_C[RAMP_C.length - 1];
    }

    /** Hill-shaded, colour-by-height picture of one tile, or null if the tile is not downloaded. */
    public synchronized Bitmap relief(int x, int y) {
        long k = key(x, y);
        Bitmap bm = relief.get(k);
        if (bm != null) return bm;
        short[] t = tile(x, y);
        if (t == null) return null;
        double lat = latLon(x * 256.0 + 128, y * 256.0 + 128)[0];
        float sp = (float) metresPerPixel(lat);
        float exag = 2.2f;
        int[] out = new int[256 * 256];
        for (int j = 0; j < 256; j++) {
            for (int i = 0; i < 256; i++) {
                float h = t[j * 256 + i];
                float hl = t[j * 256 + Math.max(i - 1, 0)], hr = t[j * 256 + Math.min(i + 1, 255)];
                float hn = t[Math.max(j - 1, 0) * 256 + i], hs = t[Math.min(j + 1, 255) * 256 + i];
                float dzdx = (hr - hl) / (2 * sp) * exag, dzdn = (hn - hs) / (2 * sp) * exag;
                float nx = -dzdx, ny = -dzdn;
                float shade = (nx * -0.5f + ny * 0.5f + 0.707f) / (float) Math.sqrt(nx * nx + ny * ny + 1f);
                float f = Math.max(0.3f, Math.min(1.35f, 0.35f + 0.65f * shade / 0.707f));
                int c = ramp(h);
                int r = Math.min(255, (int) (((c >> 16) & 255) * f));
                int g = Math.min(255, (int) (((c >> 8) & 255) * f));
                int b = Math.min(255, (int) ((c & 255) * f));
                out[j * 256 + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        bm = Bitmap.createBitmap(out, 256, 256, Bitmap.Config.ARGB_8888);
        relief.put(k, bm);
        return bm;
    }

    // ------------------------------------------------------------------ downloading
    private boolean fetchTile(int x, int y) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                URL u = new URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/" + Z + "/" + x + "/" + y + ".png");
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", UA);
                if (c.getResponseCode() != 200) continue;
                File tmp = new File(dir, x + "_" + y + ".tmp");
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(tmp);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                if (tmp.renameTo(tileFile(x, y))) return true;
            } catch (Exception e) {
                /* try again, then give up on this tile */
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }

    /** Downloads every missing terrain tile within radiusKm of the point. Returns {downloaded or already present, failed}. */
    public int[] download(double lat, double lon, double radiusKm, Progress cb, AtomicBoolean cancel) {
        double dLat = radiusKm / 111.32;
        double dLon = radiusKm / (111.32 * Math.max(0.05, Math.cos(Math.toRadians(lat))));
        double[] a = pixel(lat + dLat, lon - dLon), b = pixel(lat - dLat, lon + dLon);
        int x0 = Math.max(0, (int) Math.floor(a[0] / 256)), x1 = Math.min(N - 1, (int) Math.floor(b[0] / 256));
        int y0 = Math.max(0, (int) Math.floor(a[1] / 256)), y1 = Math.min(N - 1, (int) Math.floor(b[1] / 256));
        int total = (x1 - x0 + 1) * (y1 - y0 + 1), done = 0, ok = 0, fail = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (cancel.get()) return new int[]{ok, fail};
                if (tileFile(x, y).exists() || fetchTile(x, y)) ok++; else fail++;
                done++;
                cb.on(done, total);
            }
        }
        return new int[]{ok, fail};
    }

    /** Fetches named peaks around the point from OpenStreetMap and stores them. Returns how many, or -1 on failure. */
    public int downloadPeaks(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / 111.32;
        double dLon = radiusKm / (111.32 * Math.max(0.05, Math.cos(Math.toRadians(lat))));
        String q = String.format(Locale.US, "[out:json][timeout:60];node[\"natural\"=\"peak\"][\"name\"](%f,%f,%f,%f);out 4000;",
                lat - dLat, lon - dLon, lat + dLat, lon + dLon);
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return -1;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONArray el = new JSONObject(sb.toString()).getJSONArray("elements");
            JSONArray out = new JSONArray();
            for (int i = 0; i < el.length(); i++) {
                JSONObject o = el.getJSONObject(i);
                JSONObject tags = o.optJSONObject("tags");
                if (tags == null || !o.has("lat") || !o.has("lon")) continue;
                String name = tags.optString("name", "");
                if (name.length() == 0) continue;
                JSONObject p = new JSONObject();
                p.put("n", name);
                p.put("la", o.getDouble("lat"));
                p.put("lo", o.getDouble("lon"));
                p.put("e", parseEle(tags.optString("ele", "")));
                out.put(p);
            }
            FileOutputStream fo = new FileOutputStream(new File(dir, "peaks_" + System.currentTimeMillis() + ".json"));
            fo.write(out.toString().getBytes("UTF-8"));
            fo.close();
            return out.length();
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static double parseEle(String s) {
        try {
            String t = s.split(";")[0].replaceAll("[^0-9.\\-]", "");
            return t.length() == 0 ? -9999 : Double.parseDouble(t);
        } catch (Exception e) {
            return -9999;
        }
    }

    public List<Peak> loadPeaks() {
        List<Peak> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File[] files = dir.listFiles();
        if (files == null) return list;
        for (File f : files) {
            if (!f.getName().startsWith("peaks_") || !f.getName().endsWith(".json")) continue;
            try {
                InputStream in = new FileInputStream(f);
                byte[] data = new byte[(int) f.length()];
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
                in.close();
                JSONArray arr = new JSONArray(new String(data, "UTF-8"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.getString("n") + Math.round(o.getDouble("la") * 1000) + "," + Math.round(o.getDouble("lo") * 1000);
                    if (!seen.add(id)) continue;
                    double e = o.optDouble("e", -9999);
                    list.add(new Peak(o.getString("n"), o.getDouble("la"), o.getDouble("lo"), e <= -9000 ? Double.NaN : e));
                }
            } catch (Exception e) { /* skip a damaged file */ }
        }
        return list;
    }
}
