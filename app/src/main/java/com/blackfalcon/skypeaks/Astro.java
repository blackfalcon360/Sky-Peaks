package com.blackfalcon.skypeaks;

/**
 * Offline positions of the Sun, Moon and the seven planets (Mercury ... Neptune).
 * Sun/Moon: low-precision series (about 0.01 deg / 0.3 deg). Planets: JPL "approximate positions" Keplerian elements (1800-2050).
 */
public final class Astro {
    private Astro() { }

    public static final int SUN = 0, MOON = 1, COUNT = 9;
    public static final String[] NAME = {"Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};
    public static final String[] URDU = {"\u0633\u0648\u0631\u062C", "\u0686\u0627\u0646\u062F", "\u0639\u0637\u0627\u0631\u062F", "\u0632\u06C1\u0631\u06C1", "\u0645\u0631\u06CC\u062E", "\u0645\u0634\u062A\u0631\u06CC", "\u0632\u062D\u0644", "\u06CC\u0648\u0631\u06CC\u0646\u0633", "\u0646\u06CC\u067E\u0686\u0648\u0646"};
    public static final int[] COLOR = {0xFFFFD54F, 0xFFECEFF1, 0xFFB0A090, 0xFFFFF3C4, 0xFFFF7043, 0xFFFFE0B2, 0xFFE6C98A, 0xFF80DEEA, 0xFF5C8CFF};

    // a, e, I, L, long.peri, long.node  and their rates per century (JPL Table 1)
    private static final double[][] EL = {
            {0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593},
            {0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255},
            {1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0},
            {1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891},
            {5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909},
            {9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448},
            {19.18916464, 0.04725744, 0.77263783, 313.23810451, 170.95427630, 74.01692503},
            {30.06992276, 0.00859048, 1.77004347, -55.12002969, 44.96476227, 131.78422574}
    };
    private static final double[][] RT = {
            {0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081},
            {0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418},
            {0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0},
            {0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343},
            {-0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106},
            {-0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794},
            {-0.00196176, -0.00004397, -0.00242939, 428.48202785, 0.40805281, 0.04240589},
            {0.00026291, 0.00005105, 0.00035372, 218.45945325, -0.32241464, -0.00508664}
    };

    private static double rad(double d) { return Math.toRadians(d); }
    private static double deg(double r) { return Math.toDegrees(r); }
    private static double norm360(double a) { a = a % 360.0; return a < 0 ? a + 360.0 : a; }

    public static double jd(long ms) { return ms / 86400000.0 + 2440587.5; }

    private static double obliquity(double T) { return 23.439291 - 0.0130042 * T; }

    /** Sun ecliptic longitude of date (apparent), degrees. */
    private static double sunLon(double T) {
        double L0 = 280.46646 + 36000.76983 * T;
        double M = rad(357.52911 + 35999.05029 * T);
        double C = (1.914602 - 0.004817 * T) * Math.sin(M) + (0.019993 - 0.000101 * T) * Math.sin(2 * M) + 0.000289 * Math.sin(3 * M);
        double om = rad(125.04 - 1934.136 * T);
        return norm360(L0 + C - 0.00569 - 0.00478 * Math.sin(om));
    }

    /** {lon, lat, parallax} of the Moon, degrees (geocentric, of date). */
    private static double[] moonEcl(double T) {
        double lon = 218.32 + 481267.881 * T + 6.29 * Math.sin(rad(135.0 + 477198.87 * T)) - 1.27 * Math.sin(rad(259.3 - 413335.36 * T))
                + 0.66 * Math.sin(rad(235.7 + 890534.22 * T)) + 0.21 * Math.sin(rad(269.9 + 954397.74 * T))
                - 0.19 * Math.sin(rad(357.5 + 35999.05 * T)) - 0.11 * Math.sin(rad(186.5 + 966404.03 * T));
        double lat = 5.13 * Math.sin(rad(93.3 + 483202.02 * T)) + 0.28 * Math.sin(rad(228.2 + 960400.89 * T))
                - 0.28 * Math.sin(rad(318.3 + 6003.15 * T)) - 0.17 * Math.sin(rad(217.6 - 407332.21 * T));
        double par = 0.9508 + 0.0518 * Math.cos(rad(135.0 + 477198.87 * T)) + 0.0095 * Math.cos(rad(259.3 - 413335.36 * T))
                + 0.0078 * Math.cos(rad(235.7 + 890534.22 * T)) + 0.0028 * Math.cos(rad(269.9 + 954397.74 * T));
        return new double[]{norm360(lon), lat, par};
    }

    /** heliocentric ecliptic (J2000) position in au for elements index 0..7 (7 = Neptune, 2 = Earth-Moon barycentre) */
    private static double[] helio(int idx, double T) {
        double[] e = EL[idx], r = RT[idx];
        double a = e[0] + r[0] * T, ec = e[1] + r[1] * T, I = e[2] + r[2] * T, L = e[3] + r[3] * T;
        double w = e[4] + r[4] * T, node = e[5] + r[5] * T;
        double argp = w - node;
        double M = L - w;
        M = ((M + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        double ecDeg = deg(ec);
        double E = M + ecDeg * Math.sin(rad(M));
        for (int i = 0; i < 12; i++) {
            double dM = M - (E - ecDeg * Math.sin(rad(E)));
            double dE = dM / (1 - ec * Math.cos(rad(E)));
            E += dE;
            if (Math.abs(dE) < 1e-9) break;
        }
        double xp = a * (Math.cos(rad(E)) - ec);
        double yp = a * Math.sqrt(1 - ec * ec) * Math.sin(rad(E));
        double cw = Math.cos(rad(argp)), sw = Math.sin(rad(argp)), cn = Math.cos(rad(node)), sn = Math.sin(rad(node));
        double ci = Math.cos(rad(I)), si = Math.sin(rad(I));
        double x = (cw * cn - sw * sn * ci) * xp + (-sw * cn - cw * sn * ci) * yp;
        double y = (cw * sn + sw * cn * ci) * xp + (-sw * sn + cw * cn * ci) * yp;
        double z = (sw * si) * xp + (cw * si) * yp;
        return new double[]{x, y, z};
    }

    /** {RA, Dec} in degrees (equator of date) for a body. */
    public static double[] equatorial(int body, double jd) {
        double T = (jd - 2451545.0) / 36525.0;
        double eps = obliquity(T);
        double lon, lat;
        if (body == SUN) {
            lon = sunLon(T);
            lat = 0;
        } else if (body == MOON) {
            double[] m = moonEcl(T);
            lon = m[0];
            lat = m[1];
        } else {
            double[] earth = helio(2, T);
            // body: 2 Mercury ->0, 3 Venus ->1, 4 Mars ->3, 5 Jupiter ->4, 6 Saturn ->5, 7 Uranus ->6, 8 Neptune ->7 (index 2 is Earth)
            int map = body - 2;
            double[] p = helio(map <= 1 ? map : map + 1, T);
            double gx = p[0] - earth[0], gy = p[1] - earth[1], gz = p[2] - earth[2];
            lon = norm360(deg(Math.atan2(gy, gx)) + 1.3969713 * T);   // precess from J2000 to date
            lat = deg(Math.atan2(gz, Math.hypot(gx, gy)));
        }
        double l = rad(lon), b = rad(lat), e = rad(eps);
        double x = Math.cos(b) * Math.cos(l);
        double y = Math.cos(e) * Math.cos(b) * Math.sin(l) - Math.sin(e) * Math.sin(b);
        double z = Math.sin(e) * Math.cos(b) * Math.sin(l) + Math.cos(e) * Math.sin(b);
        return new double[]{norm360(deg(Math.atan2(y, x))), deg(Math.asin(z))};
    }

    /** {azimuth from true north clockwise, altitude} in degrees, refraction (and Moon parallax) included. */
    public static double[] horizontal(int body, long ms, double latDeg, double lonDeg) {
        double jd = jd(ms);
        double[] eq = equatorial(body, jd);
        double gmst = norm360(280.46061837 + 360.98564736629 * (jd - 2451545.0));
        double H = rad(norm360(gmst + lonDeg - eq[0]));
        double phi = rad(latDeg), dec = rad(eq[1]);
        double sinAlt = Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(H);
        double alt = deg(Math.asin(Math.max(-1, Math.min(1, sinAlt))));
        double az = norm360(deg(Math.atan2(Math.sin(H), Math.cos(H) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi))) + 180.0);
        if (body == MOON) {
            double T = (jd - 2451545.0) / 36525.0;
            double par = moonEcl(T)[2];
            alt -= deg(Math.asin(Math.sin(rad(par)) * Math.cos(rad(alt))));
        }
        if (alt > -1.0) alt += 1.02 / Math.tan(rad(alt + 10.3 / (alt + 5.11))) / 60.0;
        return new double[]{az, alt};
    }

    /** {illuminated fraction 0..1, elongation of Moon east of Sun 0..360 deg}. */
    public static double[] moonPhase(long ms) {
        double T = (jd(ms) - 2451545.0) / 36525.0;
        double el = norm360(moonEcl(T)[0] - sunLon(T));
        return new double[]{(1 - Math.cos(rad(el))) / 2.0, el};
    }

    public static String phaseName(double elongation) {
        String[] n = {"New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous", "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent"};
        return n[(int) (((elongation + 22.5) % 360.0) / 45.0)];
    }
}
