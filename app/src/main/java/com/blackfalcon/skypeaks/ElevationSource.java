package com.blackfalcon.skypeaks;

public interface ElevationSource {
    /** metres above sea level, NaN where no data is available */
    double elevation(double lat, double lon);
}
