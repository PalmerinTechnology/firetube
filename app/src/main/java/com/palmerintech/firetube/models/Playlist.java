package com.palmerintech.firetube.models;

import java.io.Serializable;
import java.util.ArrayList;

/** Wire-compatible copy of FireTube 1.x's playlist model. See {@link Video}. */
@SuppressWarnings({"unused", "FieldMayBeFinal"})
public class Playlist implements Serializable {
    private static final long serialVersionUID = -6672388342816919324L;

    private int id;
    private String title;
    private ArrayList<Video> videos;

    public Playlist(int id, String title, ArrayList<Video> videos) {
        this.id = id;
        this.title = title;
        this.videos = videos;
    }

    public String getTitle() { return title; }
    public ArrayList<Video> getVideos() { return videos; }
}
