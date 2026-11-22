package com.example.geoalert.assets.adapter.web;

import com.example.geoalert.assets.application.AssetQueryService;
import com.example.geoalert.assets.domain.AssetPosition;
import com.example.geoalert.assets.domain.BoundingBox;
import com.example.geoalert.assets.domain.TrackPoint;
import com.example.geoalert.common.web.ApiException;
import com.example.geoalert.common.web.Limits;
import com.example.geoalert.common.web.Page;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private static final int ASSETS_DEFAULT = 1000;
    private static final int ASSETS_MAX = 5000;
    private static final int TRACK_DEFAULT = 500;
    private static final int TRACK_MAX = 5000;

    private final AssetQueryService assets;

    public AssetController(AssetQueryService assets) {
        this.assets = assets;
    }

    /** Current positions inside a bounding box (the map viewport), ordered by asset id. */
    @GetMapping
    public Page<AssetPosition> inBox(
            @RequestParam String bbox, @RequestParam(required = false) Integer limit) {
        int max = Limits.check(limit, ASSETS_DEFAULT, ASSETS_MAX);
        return Page.of(assets.inBox(parseBox(bbox), max), max);
    }

    @GetMapping("/{assetId}")
    public AssetPosition one(@PathVariable String assetId) {
        return assets.find(assetId).orElseThrow(AssetController::notFound);
    }

    /** The stored reports of one asset, oldest first (late reports included). */
    @GetMapping("/{assetId}/track")
    public Page<TrackPoint> track(
            @PathVariable String assetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        int max = Limits.check(limit, TRACK_DEFAULT, TRACK_MAX);
        Instant start = parseInstant(from, "from");
        Instant end = parseInstant(to, "to");
        assets.find(assetId).orElseThrow(AssetController::notFound);
        return Page.of(assets.track(assetId, start, end, max), max);
    }

    // ---- parameters

    static BoundingBox parseBox(String bbox) {
        String[] parts = bbox.split(",", -1);
        if (parts.length != 4) {
            throw invalidBox("bbox must be minLon,minLat,maxLon,maxLat.");
        }
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                v[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw invalidBox("bbox must hold four numbers.");
            }
            if (!Double.isFinite(v[i])) {
                throw invalidBox("bbox must hold four finite numbers.");
            }
        }
        double minLon = v[0];
        double minLat = v[1];
        double maxLon = v[2];
        double maxLat = v[3];
        if (minLat < -90 || maxLat > 90 || minLat > maxLat) {
            throw invalidBox("Latitudes must be from -90 to 90, with minLat not above maxLat.");
        }
        if (minLon < -180 || minLon > 180 || maxLon < -180 || maxLon > 180) {
            throw invalidBox("Longitudes must be from -180 to 180.");
        }
        return new BoundingBox(minLon, minLat, maxLon, maxLat);
    }

    private static Instant parseInstant(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    name + " must be an ISO-8601 date-time.");
        }
    }

    private static ApiException invalidBox(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "There is no such asset.");
    }
}
