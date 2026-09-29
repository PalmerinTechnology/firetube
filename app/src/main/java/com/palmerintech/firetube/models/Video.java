package com.palmerintech.firetube.models;

import java.io.Serializable;

/**
 * Wire-compatible copy of FireTube 1.x's song model, kept only so 2.x can read what 1.x
 * Java-serialized into SharedPreferences (see {@code LegacyImport}). Class name, field names/types
 * and serialVersionUID must stay exactly as in 1.x. Don't use it for anything else.
 */
@SuppressWarnings({"unused", "FieldMayBeFinal"})
public class Video implements Serializable {
    private static final long serialVersionUID = -4960093298988928374L;

    private int track;
    private int i;
    private String title;
    private String yid;
    private String thumbUrl;
    private int duration;
    private String description;
    private boolean liveVideo;
    private int size;

    public Video(String title, String yid, String thumbUrl, int duration) {
        this.title = title;
        this.yid = yid;
        this.thumbUrl = thumbUrl;
        this.duration = duration;
    }

    public String getTitle() { return title; }
    public String getYid() { return yid; }
    public String getThumbUrl() { return thumbUrl; }
    public int getDuration() { return duration; }
}
