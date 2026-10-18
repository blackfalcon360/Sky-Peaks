package com.blackfalcon.skypeaks;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SensorEventListener {

    private static final int GREEN = 0xFF2ECC71, GRAY = 0xFF9E9E9E, CARD = 0xFF0C0C1E, EDGE = 0xFF3A3A60,
            AMBER = 0xFFFFB300, BLUE = 0xFF4FC3F7, RED = 0xFFE57373, GOLD = 0xFFFFD54F;

    private SharedPreferences prefs;
    private LocationManager lm;
    private SensorManager sm;
    private Sensor rot;
    private Dem dem;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService ioDl = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelDl = new AtomicBoolean(false);

    // location
    private double lat, lon;
    private boolean hasLoc = false, running = false;
    private float declination = 0f;

    // time
    private boolean live = true;
    private long customMs = 0;

    // sky
    private final float[] bodyAz = new float[Astro.COUNT], bodyAlt = new float[Astro.COUNT];
    private final float[][] pAz = new float[Astro.COUNT][SkyView.PATH_N], pAlt = new float[Astro.COUNT][SkyView.PATH_N];
    private final String[] riseFlat = new String[Astro.COUNT], riseTer = new String[Astro.COUNT];
    private long pathsDayStart = -1;
    private double pathsLat = 999, pathsLon = 999;
    private Terrain terrain, pathsTerrain;
    private boolean terrainBusy = false;
    private double terrLat = 999, terrLon = 999;
    private int demVersion = 0, terrVersion = -1;
    private double moonFrac = 0.5, moonElong = 0;
    private int tick = 0;
    private boolean dlRunning = false;

    // modes
    private boolean sensorOn, cameraOn = false;
    private float panoFov = 60f;
    private final float[] rotMat = new float[9];
    private final double[] sR = {1, 0, 0}, sU = {0, 0, 1}, sF = {0, 1, 0};
    private boolean firstSample = true;
    private Camera cam;

    // ui
    private int tab = 0;
    private final TextView[] tabs = new TextView[4];
    private final View[] pages = new View[4];
    private SkyView sky;
    private MapView mapView;
    private TextureView tex;
    private TextView camBtn, senBtn, bodiesInfo, offStatusTv, offProgressTv;
    private LinearLayout bodiesBox;
    private SeekBar timeBar;
    private EditText customEt;
    private final TextView[] radiusChips = new TextView[3];
    private final int[] RADII = {25, 50, 100};

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------ callbacks
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick++;
            updateSky();
            if (tab == 2 && tick % 5 == 0) refreshBodies();
            handler.postDelayed(this, 1000);
        }
    };

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { setLocation(l.getLatitude(), l.getLongitude(), l.getAltitude()); }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
    };

    // ------------------------------------------------------------- lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("skypeaks", MODE_PRIVATE);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        dem = new Dem(getFilesDir());
        sensorOn = rot != null;
        tab = prefs.getInt("tab", 0);
        if (prefs.contains("lat")) {
            lat = Double.longBitsToDouble(prefs.getLong("lat", 0));
            lon = Double.longBitsToDouble(prefs.getLong("lon", 0));
            hasLoc = true;
        }
        buildUi();
        if (hasLoc) { mapView.setMe(lat, lon, true); ensureTerrain(); }
        if (!hasPerm()) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
    }

    @Override protected void onResume() { super.onResume(); start(); }
    @Override protected void onPause() { super.onPause(); stop(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelDl.set(true);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 1) start();
        if (code == 2 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCameraMode();
    }

    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void start() {
        if (running) return;
        running = true;
        handler.post(ticker);
        registerSensor();
        if (cameraOn && tex.isAvailable()) openCamera(tex.getSurfaceTexture());
        if (hasPerm()) requestLocation();
    }

    private void stop() {
        handler.removeCallbacks(ticker);
        try { lm.removeUpdates(locListener); } catch (Exception e) { }
        sm.unregisterListener(this);
        closeCamera();
        running = false;
    }

    private void requestLocation() {
        try {
            Location a = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location b = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location best = a != null && (b == null || a.getTime() >= b.getTime()) ? a : b;
            if (best != null) setLocation(best.getLatitude(), best.getLongitude(), best.getAltitude());
            lm.removeUpdates(locListener);
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 50, locListener);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 50, locListener);
        } catch (SecurityException | IllegalArgumentException e) { /* the Sky tab shows "waiting for location" */ }
    }

    private void setLocation(double la, double lo, double alt) {
        boolean first = !hasLoc;
        boolean moved = first || Math.hypot((la - lat) * 111320.0, (lo - lon) * 111320.0 * Math.cos(Math.toRadians(la))) > 100;
        lat = la; lon = lo; hasLoc = true;
        declination = new GeomagneticField((float) la, (float) lo, (float) alt, System.currentTimeMillis()).getDeclination();
        if (moved) {
            prefs.edit().putLong("lat", Double.doubleToLongBits(la)).putLong("lon", Double.doubleToLongBits(lo)).apply();
            mapView.setMe(la, lo, true);
            ensureTerrain();
        }
        updateSky();
    }

    // ------------------------------------------------------------- sensors
    private void registerSensor() {
        firstSample = true;
        if (sensorOn && rot != null) sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME);
    }

    private double[] decl(double e, double n, double u) {
        double d = Math.toRadians(declination);
        return new double[]{e * Math.cos(d) + n * Math.sin(d), -e * Math.sin(d) + n * Math.cos(d), u};
    }

    @Override
    public void onSensorChanged(SensorEvent ev) {
        if (!sensorOn || ev.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rotMat, ev.values);
        double[] r = decl(rotMat[0], rotMat[3], rotMat[6]);       // device X axis in the world
        double[] u = decl(rotMat[1], rotMat[4], rotMat[7]);       // device Y axis (screen up)
        double[] f = decl(-rotMat[2], -rotMat[5], -rotMat[8]);    // back camera direction
        double a = firstSample ? 1.0 : 0.25;
        firstSample = false;
        for (int i = 0; i < 3; i++) {
            sR[i] += a * (r[i] - sR[i]);
            sU[i] += a * (u[i] - sU[i]);
            sF[i] += a * (f[i] - sF[i]);
        }
        double fl = Math.sqrt(sF[0] * sF[0] + sF[1] * sF[1] + sF[2] * sF[2]);
        double[] fw = {sF[0] / fl, sF[1] / fl, sF[2] / fl};
        double d = sR[0] * fw[0] + sR[1] * fw[1] + sR[2] * fw[2];
        double[] rt = {sR[0] - d * fw[0], sR[1] - d * fw[1], sR[2] - d * fw[2]};
        double rl = Math.sqrt(rt[0] * rt[0] + rt[1] * rt[1] + rt[2] * rt[2]);
        rt = new double[]{rt[0] / rl, rt[1] / rl, rt[2] / rl};
        double[] up = {rt[1] * fw[2] - rt[2] * fw[1], rt[2] * fw[0] - rt[0] * fw[2], rt[0] * fw[1] - rt[1] * fw[0]};
        sky.setBasis(rt, up, fw);
        mapView.setHeading((float) sky.viewAz(), true);
        // keep the smoothed vectors from drifting in length
        sF[0] = fw[0]; sF[1] = fw[1]; sF[2] = fw[2];
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void toggleSensor() {
        if (rot == null) { Toast.makeText(this, "This phone has no rotation sensor", Toast.LENGTH_SHORT).show(); return; }
        if (cameraOn) return;
        sensorOn = !sensorOn;
        if (sensorOn) { sky.setManual(false); registerSensor(); }
        else {
            sm.unregisterListener(this);
            sky.pointTo(sky.viewAz(), sky.viewAlt());
            sky.setManual(true);
            mapView.setHeading(0, false);
        }
        updateModeButtons();
    }

    // ------------------------------------------------------------- camera (AR)
    private void toggleCamera() {
        if (cameraOn) { stopCameraMode(); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 2);
            return;
        }
        startCameraMode();
    }

    private void startCameraMode() {
        if (rot == null) { Toast.makeText(this, "AR needs a rotation sensor", Toast.LENGTH_SHORT).show(); return; }
        cameraOn = true;
        sensorOn = true;
        panoFov = sky.getFov();
        sky.setManual(false);
        sky.setCamera(true);
        registerSensor();
        tex.setVisibility(View.VISIBLE);
        if (tex.isAvailable()) openCamera(tex.getSurfaceTexture());
        updateModeButtons();
    }

    private void stopCameraMode() {
        cameraOn = false;
        closeCamera();
        tex.setVisibility(View.GONE);
        sky.setCamera(false);
        sky.setFov(panoFov);
        updateModeButtons();
    }

    private final TextureView.SurfaceTextureListener stl = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { if (cameraOn) openCamera(st); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { closeCamera(); return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
    };

    private void openCamera(SurfaceTexture st) {
        if (cam != null) return;
        try {
            cam = Camera.open();
            Camera.Parameters p = cam.getParameters();
            int w = Math.max(1, tex.getWidth()), h = Math.max(1, tex.getHeight());
            float target = h > w ? h / (float) w : 1.78f;
            Camera.Size best = null;
            double bestDiff = 1e9;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width > 1920) continue;
                double diff = Math.abs(s.width / (double) s.height - target);
                if (best == null || diff < bestDiff - 0.02 || (Math.abs(diff - bestDiff) <= 0.02 && s.width > best.width)) { best = s; bestDiff = diff; }
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            cam.setParameters(p);
            cam.setDisplayOrientation(90);
            cam.setPreviewTexture(st);
            cam.startPreview();
            float fov = p.getVerticalViewAngle();      // portrait: the sensor's short side runs across the screen
            if (fov < 20f || fov > 120f) fov = 55f;
            sky.setFov(fov);
        } catch (Exception e) {
            closeCamera();
            Toast.makeText(this, "Camera could not be opened", Toast.LENGTH_SHORT).show();
            stopCameraMode();
        }
    }

    private void closeCamera() {
        if (cam != null) {
            try { cam.stopPreview(); } catch (Exception e) { }
            try { cam.release(); } catch (Exception e) { }
            cam = null;
        }
    }

    // ------------------------------------------------------------- time + sky
    private long simMs() { return live ? System.currentTimeMillis() : customMs; }

    private static long dayStart(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String hm(float minutes) {
        int m = Math.round(minutes);
        return String.format(Locale.US, "%02d:%02d", (m / 60) % 25, m % 60);
    }

    private void updateSky() {
        if (sky == null) return;
        if (!hasLoc) {
            sky.setHud(hasPerm() ? "Waiting for your location\u2026" : "Location permission is needed");
            return;
        }
        long ms = simMs();
        for (int b = 0; b < Astro.COUNT; b++) {
            double[] h = Astro.horizontal(b, ms, lat, lon);
            bodyAz[b] = (float) h[0];
            bodyAlt[b] = (float) h[1];
        }
        double[] ph = Astro.moonPhase(ms);
        moonFrac = ph[0];
        moonElong = ph[1];
        sky.setBodies(bodyAz, bodyAlt, moonFrac);
        mapView.setSky(bodyAz[0], bodyAlt[0], bodyAz[1], bodyAlt[1]);

        if (dayStart(ms) != pathsDayStart || Math.abs(lat - pathsLat) > 0.02 || Math.abs(lon - pathsLon) > 0.02 || terrain != pathsTerrain) computePaths(ms);

        String t = new SimpleDateFormat("EEE d MMM yyyy  HH:mm:ss", Locale.ENGLISH).format(ms) + (live ? "  \u2022 LIVE" : "  \u2022 custom time");
        String ts;
        if (terrainBusy) ts = "Computing mountains\u2026";
        else if (terrain == null || !terrain.valid) ts = "No offline terrain here \u2014 Offline tab \u2192 Download";
        else ts = String.format(Locale.US, "Terrain ready \u00B7 range %.0f km \u00B7 %d peaks", terrain.maxRangeKm, terrain.labels.size());
        sky.setHud(t + "\n" + ts);
        if (live && timeBar != null) {
            Calendar c = Calendar.getInstance();
            timeBar.setProgress(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE));
        }
    }

    private float thr(int b, int k, boolean ter) {
        float th = b < 2 ? -0.27f : 0f;
        if (!ter) return th;
        float e = terrain.envAt(pAz[b][k]);
        return Float.isNaN(e) ? th : Math.max(e, th);
    }

    private String events(int b, boolean ter) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < SkyView.PATH_N - 1; k++) {
            float a0 = pAlt[b][k] - thr(b, k, ter), a1 = pAlt[b][k + 1] - thr(b, k + 1, ter);
            if ((a0 < 0 && a1 >= 0) || (a0 >= 0 && a1 < 0)) {
                float f = a0 / (a0 - a1);
                if (sb.length() > 0) sb.append("   ");
                sb.append(a1 >= 0 ? "Rise " : "Set ").append(hm((k + f) * 10f));
            }
        }
        if (sb.length() == 0) return pAlt[b][0] - thr(b, 0, ter) >= 0 ? "Above the horizon all day" : "Below the horizon all day";
        return sb.toString();
    }

    private void computePaths(long ms) {
        long ds = dayStart(ms);
        pathsDayStart = ds;
        pathsLat = lat;
        pathsLon = lon;
        pathsTerrain = terrain;
        for (int b = 0; b < Astro.COUNT; b++) {
            for (int k = 0; k < SkyView.PATH_N; k++) {
                double[] h = Astro.horizontal(b, ds + k * 600000L, lat, lon);
                pAz[b][k] = (float) h[0];
                pAlt[b][k] = (float) h[1];
            }
        }
        sky.setPaths(pAz[0], pAlt[0], pAz[1], pAlt[1]);
        boolean ter = terrain != null && terrain.valid;
        for (int b = 0; b < Astro.COUNT; b++) {
            riseFlat[b] = events(b, false);
            riseTer[b] = ter ? events(b, true) : null;
        }
        if (tab == 2) refreshBodies();
    }

    private void ensureTerrain() {
        if (!hasLoc || terrainBusy) return;
        boolean moved = Math.hypot((lat - terrLat) * 111320.0, (lon - terrLon) * 111320.0 * Math.cos(Math.toRadians(lat))) > 250;
        if (!moved && terrVersion == demVersion) return;
        terrainBusy = true;
        final double la = lat, lo = lon;
        final int ver = demVersion;
        io.execute(new Runnable() {
            @Override public void run() {
                final List<Peak> peaks = dem.loadPeaks();
                final Terrain t = Terrain.compute(dem, la, lo, peaks);
                handler.post(new Runnable() {
                    @Override public void run() {
                        terrain = t;
                        terrLat = la;
                        terrLon = lo;
                        terrVersion = ver;
                        terrainBusy = false;
                        sky.setTerrain(t);
                        mapView.setPeaks(peaks);
                        updateSky();
                        if (ver != demVersion || Math.hypot((lat - la) * 111320.0, (lon - lo) * 111320.0) > 250) ensureTerrain();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------- UI helpers
    private GradientDrawable box(int fill, int edge, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1), edge);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView tv(String text, int size, int color) {
        TextView x = new TextView(this);
        x.setText(text);
        x.setTextSize(size);
        x.setTextColor(color);
        return x;
    }

    private TextView chip(String text, int edge, View.OnClickListener l) {
        TextView c = tv(text, 13, Color.WHITE);
        c.setGravity(Gravity.CENTER);
        c.setBackground(box(0xCC0C0C1E, edge, 10));
        c.setPadding(dp(10), dp(7), dp(10), dp(7));
        c.setOnClickListener(l);
        return c;
    }

    private TextView button(String text) {
        TextView b = tv(text, 15, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(0xFF1C1C3A, BLUE, 10));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private static String dir8(float b) {
        String[] d = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return d[Math.round(((b % 360) + 360) % 360 / 45f) % 8];
    }

    // ------------------------------------------------------------- UI
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), dp(26), dp(6), dp(4));

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setPadding(0, 0, 0, dp(6));
        String[] names = {"Sky", "Map", "Bodies", "Offline"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView x = tv(names[i], 14, Color.WHITE);
            x.setGravity(Gravity.CENTER);
            x.setPadding(0, dp(9), 0, dp(9));
            x.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tab = idx; prefs.edit().putInt("tab", tab).apply(); showTab(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabRow.addView(x, lp);
            tabs[i] = x;
        }
        col.addView(tabRow);

        FrameLayout frame = new FrameLayout(this);
        pages[0] = buildSkyPage();
        pages[1] = buildMapPage();
        pages[2] = buildBodiesPage();
        pages[3] = buildOfflinePage();
        for (View v : pages) frame.addView(v);
        col.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // credit: always in the lower RIGHT corner
        TextView credit = tv("By: Black Falcon \uD83E\uDD85", 12, Color.WHITE);
        credit.setPadding(dp(8), dp(3), dp(8), dp(3));
        credit.setBackground(box(0x99000000, 0x00000000, 8));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        cl.setMargins(0, 0, dp(8), dp(6));
        root.addView(credit, cl);

        setContentView(root);
        if (rot == null) { sensorOn = false; sky.setManual(true); }
        showTab();
        updateModeButtons();
        mapView.setDem(dem);
        updateSky();
    }

    private void showTab() {
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
            tabs[i].setBackground(box(i == tab ? 0xFF1C1C3A : CARD, i == tab ? BLUE : EDGE, 10));
            tabs[i].setTypeface(i == tab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (tab == 2) refreshBodies();
        if (tab == 3) refreshOffline();
    }

    private void updateModeButtons() {
        camBtn.setText("\uD83D\uDCF7 AR " + (cameraOn ? "ON" : "OFF"));
        camBtn.setBackground(box(0xCC0C0C1E, cameraOn ? GREEN : EDGE, 10));
        senBtn.setText("\uD83E\uDDED " + (sensorOn ? "ON" : "OFF"));
        senBtn.setBackground(box(0xCC0C0C1E, sensorOn ? GREEN : EDGE, 10));
    }

    private View buildSkyPage() {
        FrameLayout page = new FrameLayout(this);
        tex = new TextureView(this);
        tex.setSurfaceTextureListener(stl);
        tex.setVisibility(View.GONE);
        page.addView(tex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sky = new SkyView(this);
        page.addView(sky, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        camBtn = chip("", EDGE, new View.OnClickListener() { @Override public void onClick(View v) { toggleCamera(); } });
        senBtn = chip("", EDGE, new View.OnClickListener() { @Override public void onClick(View v) { toggleSensor(); } });
        top.addView(camBtn);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = dp(6);
        top.addView(senBtn, sl);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        tl.setMargins(0, dp(4), dp(4), 0);
        page.addView(top, tl);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        String[] labels = {"\u22121d", "\u22121h", "Now", "+1h", "+1d"};
        final long[] shift = {-86400000L, -3600000L, 0, 3600000L, 86400000L};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            TextView c = chip(labels[i], i == 2 ? GREEN : EDGE, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == 2) live = true;
                    else { customMs = simMs() + shift[idx]; live = false; }
                    if (!live) syncBar();
                    updateSky();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(5);
            row.addView(c, lp);
        }
        bottom.addView(row);
        timeBar = new SeekBar(this);
        timeBar.setMax(1439);
        timeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                customMs = dayStart(simMs()) + p * 60000L;
                live = false;
                updateSky();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(2);
        bottom.addView(timeBar, bl);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.setMargins(dp(4), 0, dp(4), dp(30));
        page.addView(bottom, bp);
        return page;
    }

    private void syncBar() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(simMs());
        timeBar.setProgress(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE));
    }

    private View buildMapPage() {
        FrameLayout page = new FrameLayout(this);
        mapView = new MapView(this);
        page.addView(mapView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView me = chip("\u25CE My location", EDGE, new View.OnClickListener() { @Override public void onClick(View v) { mapView.centerOnMe(); } });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        lp.setMargins(dp(6), dp(6), 0, 0);
        page.addView(me, lp);
        return page;
    }

    private View buildBodiesPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(2), dp(2), dp(2), dp(40));
        bodiesInfo = tv("", 12, GRAY);
        bodiesInfo.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(bodiesInfo);
        bodiesBox = new LinearLayout(this);
        bodiesBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(bodiesBox);
        sv.addView(box);
        return sv;
    }

    private void refreshBodies() {
        if (bodiesBox == null) return;
        bodiesBox.removeAllViews();
        if (!hasLoc) { bodiesInfo.setText("Waiting for your location\u2026"); return; }
        boolean ter = terrain != null && terrain.valid;
        bodiesInfo.setText("Times are for today (" + new SimpleDateFormat("d MMM", Locale.ENGLISH).format(simMs()) + ")" + (ter ? ", also shown with the mountains in the way." : ". Download offline terrain to also see sunrise/sunset behind the mountains."));
        for (int b = 0; b < Astro.COUNT; b++) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setBackground(box(CARD, EDGE, 12));
            c.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            c.setLayoutParams(lp);
            TextView title = tv(Astro.NAME[b] + "  \u00B7  " + Astro.URDU[b], 17, Astro.COLOR[b]);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            c.addView(title);
            boolean up = bodyAlt[b] > 0;
            String where = String.format(Locale.US, "Az %.1f\u00B0 %s   Alt %.1f\u00B0   ", bodyAz[b], dir8(bodyAz[b]), bodyAlt[b]);
            float env = ter ? terrain.envAt(bodyAz[b]) : Float.NaN;
            String state = !up ? "below the horizon" : (!Float.isNaN(env) && bodyAlt[b] < env ? "behind the mountains" : "visible");
            TextView t1 = tv(where + state, 13, up ? Color.WHITE : GRAY);
            c.addView(t1);
            if (b == Astro.MOON) c.addView(tv(Astro.phaseName(moonElong) + "  \u00B7  " + Math.round(moonFrac * 100) + "% lit", 13, 0xFFECEFF1));
            if (riseFlat[b] != null) c.addView(tv("Flat horizon:  " + riseFlat[b], 12, GRAY));
            if (riseTer[b] != null) c.addView(tv("With mountains:  " + riseTer[b], 12, GOLD));
            bodiesBox.addView(c);
        }
    }

    private View buildOfflinePage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(4), dp(6), dp(44));
        sv.addView(box);

        box.addView(tv("Offline map & mountains", 18, AMBER));
        TextView why = tv("Download the terrain around you once. After that the mountain skyline, peak labels, offline map and "
                + "sunrise/sunset behind the mountains all work with no internet.", 13, GRAY);
        why.setPadding(0, dp(2), 0, dp(8));
        box.addView(why);

        offStatusTv = tv("", 14, Color.WHITE);
        offStatusTv.setBackground(box(CARD, EDGE, 12));
        offStatusTv.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(offStatusTv);

        TextView rt = tv("Download radius", 15, AMBER);
        rt.setPadding(0, dp(12), 0, dp(4));
        box.addView(rt);
        LinearLayout rr = new LinearLayout(this);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView c = chip(RADII[i] + " km", EDGE, new View.OnClickListener() {
                @Override public void onClick(View v) { prefs.edit().putInt("radius", RADII[idx]).apply(); updateRadiusChips(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            rr.addView(c, lp);
            radiusChips[i] = c;
        }
        box.addView(rr);
        TextView hint = tv("25 km \u2248 2\u20134 MB, 50 km \u2248 5\u201310 MB, 100 km \u2248 15\u201335 MB (Wi-Fi recommended).", 12, GRAY);
        hint.setPadding(0, dp(4), 0, dp(8));
        box.addView(hint);

        TextView ct = tv("Other place (optional): latitude, longitude", 14, Color.WHITE);
        box.addView(ct);
        customEt = new EditText(this);
        customEt.setTextColor(Color.WHITE);
        customEt.setHintTextColor(GRAY);
        customEt.setHint("empty = around my location, e.g. 35.88, 74.46");
        customEt.setSingleLine(true);
        customEt.setTextSize(14);
        box.addView(customEt);

        TextView dl = button("\u2B07  Download terrain + peak names");
        dl.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startDownload(); } });
        box.addView(dl);
        TextView cancel = button("Cancel download");
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { cancelDl.set(true); } });
        box.addView(cancel);
        offProgressTv = tv("", 13, GREEN);
        offProgressTv.setPadding(0, dp(6), 0, 0);
        box.addView(offProgressTv);

        TextView del = button("Delete all offline data");
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Delete offline data?").setMessage("All downloaded terrain tiles and peak names will be removed.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                dem.deleteAll();
                                demVersion++;
                                terrain = null;
                                sky.setTerrain(null);
                                mapView.setPeaks(new java.util.ArrayList<Peak>());
                                ensureTerrain();
                                refreshOffline();
                                updateSky();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        box.addView(del);

        TextView src = tv("Data: terrain \u2013 Terrain Tiles on AWS Open Data (SRTM and other public sources); peak names \u2013 \u00A9 OpenStreetMap contributors (ODbL). "
                + "Sun, Moon and planet positions are calculated on the phone.", 11, GRAY);
        src.setPadding(0, dp(14), 0, 0);
        box.addView(src);
        TextView credit = tv("Brought to you By: Black Falcon \uD83E\uDD85", 13, GRAY);
        credit.setPadding(0, dp(12), 0, 0);
        box.addView(credit);
        updateRadiusChips();
        return sv;
    }

    private void updateRadiusChips() {
        int r = prefs.getInt("radius", 50);
        for (int i = 0; i < 3; i++) {
            radiusChips[i].setBackground(box(RADII[i] == r ? 0xFF1C1C3A : CARD, RADII[i] == r ? GREEN : EDGE, 10));
            radiusChips[i].setTypeface(RADII[i] == r ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void refreshOffline() {
        if (offStatusTv == null) return;
        int tiles = dem.tileCount();
        double mb = dem.sizeBytes() / 1048576.0;
        int peaks = dem.loadPeaks().size();
        String s = tiles == 0 ? "No offline data yet." : String.format(Locale.US, "%d terrain tiles  \u00B7  %.1f MB  \u00B7  %d named peaks", tiles, mb, peaks);
        if (terrain != null && terrain.valid) s += String.format(Locale.US, "\nHere: terrain ready, you can see up to %.0f km", terrain.maxRangeKm);
        else if (hasLoc && tiles > 0) s += "\nYour current spot is not covered yet \u2014 download around it.";
        offStatusTv.setText(s);
    }

    private void startDownload() {
        if (dlRunning) return;
        double la = lat, lo = lon;
        String txt = customEt.getText().toString().trim();
        if (txt.length() > 0) {
            try {
                String[] sp = txt.split("[,\\s]+");
                la = Double.parseDouble(sp[0]);
                lo = Double.parseDouble(sp[1]);
                if (la < -85 || la > 85 || lo < -180 || lo > 180) throw new NumberFormatException();
            } catch (Exception e) {
                Toast.makeText(this, "Write it like: 35.88, 74.46", Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (!hasLoc) {
            Toast.makeText(this, "Waiting for your location\u2026", Toast.LENGTH_SHORT).show();
            return;
        }
        final double fla = la, flo = lo;
        final int radius = prefs.getInt("radius", 50);
        dlRunning = true;
        cancelDl.set(false);
        offProgressTv.setTextColor(GREEN);
        offProgressTv.setText("Starting\u2026");
        ioDl.execute(new Runnable() {
            @Override public void run() {
                final int[] res = dem.download(fla, flo, radius, new Dem.Progress() {
                    @Override public void on(final int done, final int total) {
                        handler.post(new Runnable() { @Override public void run() { offProgressTv.setText("Terrain tiles: " + done + " / " + total); } });
                    }
                }, cancelDl);
                int peaks = -2;
                if (!cancelDl.get()) {
                    handler.post(new Runnable() { @Override public void run() { offProgressTv.setText("Downloading peak names\u2026"); } });
                    peaks = dem.downloadPeaks(fla, flo, radius);
                }
                dem.reset();
                final int pk = peaks;
                final boolean cancelled = cancelDl.get();
                handler.post(new Runnable() {
                    @Override public void run() {
                        dlRunning = false;
                        demVersion++;
                        String m;
                        if (cancelled) m = "Cancelled.";
                        else {
                            m = res[1] == 0 ? "Done \u2714  " + res[0] + " tiles." : res[0] + " tiles saved, " + res[1] + " failed (check internet and try again).";
                            m += pk >= 0 ? "  " + pk + " peak names." : "  Peak names could not be loaded (heights will still be shown).";
                        }
                        offProgressTv.setTextColor(res[1] == 0 && !cancelled ? GREEN : AMBER);
                        offProgressTv.setText(m);
                        refreshOffline();
                        ensureTerrain();
                        mapView.invalidate();
                    }
                });
            }
        });
    }
}
