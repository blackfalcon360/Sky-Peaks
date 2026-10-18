package com.blackfalcon.skypeaks;

/** A named summit (from OpenStreetMap). */
public final class Peak {
    public final String name;
    public final double lat, lon;
    public final double ele;     // metres, NaN if unknown

    public Peak(String name, double lat, double lon, double ele) {
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.ele = ele;
    }
}
