package com.example.geoalert.ingest.application;

import java.util.List;

/** The answer to a batch: the counts, and which line was rejected and why. */
public record BatchResult(
        int received, int accepted, int duplicates, int rejected, List<LineRejection> rejections) {

    public record LineRejection(int line, String code, String detail) {}
}
