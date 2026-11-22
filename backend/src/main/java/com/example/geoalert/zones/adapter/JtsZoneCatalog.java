package com.example.geoalert.zones.adapter;

import com.example.geoalert.zones.domain.Zone;
import com.example.geoalert.zones.domain.ZoneCatalog;
import com.example.geoalert.zones.domain.ZoneClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The zones as JTS shapes, built once at start-up from the zones file.
 *
 * <p>"Inside" means inside the outer boundary and not inside a hole; a zone may have several
 * parts. A point exactly on an edge counts as inside ({@code covers}). JTS treats coordinates
 * as flat x and y and knows nothing about the 180 degree meridian, so a zone whose edges jump
 * across it is made continuous by adding 360 to its negative longitudes, and a point with a
 * negative longitude is shifted the same way before it is tested against such a zone.
 */
@Component
class JtsZoneCatalog implements ZoneCatalog {

    private static final Logger log = LoggerFactory.getLogger(JtsZoneCatalog.class);

    private record LoadedZone(Zone zone, PreparedGeometry shape) {}

    private final GeometryFactory factory = new GeometryFactory();
    private final List<LoadedZone> loaded;
    private final Map<String, ZoneClass> classes = new LinkedHashMap<>();

    JtsZoneCatalog(
            ObjectMapper mapper,
            @Value("${app.zones.location:classpath:zones.geojson}") String location)
            throws IOException {
        Resource resource = new DefaultResourceLoader().getResource(location);
        try (InputStream in = resource.getInputStream()) {
            this.loaded = load(mapper.readTree(in));
        }
        loaded.forEach(z -> classes.put(z.zone().id(), z.zone().zoneClass()));
        log.info(
                "Loaded {} zones ({} restricted)",
                loaded.size(),
                classes.values().stream().filter(c -> c == ZoneClass.RESTRICTED).count());
    }

    @Override
    public Set<String> zonesContaining(double lat, double lon) {
        return loaded.stream()
                .filter(z -> covers(z, lat, lon))
                .map(z -> z.zone().id())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean covers(LoadedZone z, double lat, double lon) {
        double x = z.zone().crossesAntimeridian() && lon < 0 ? lon + 360 : lon;
        Point point = factory.createPoint(new Coordinate(x, lat));
        return z.shape().covers(point);
    }

    @Override
    public boolean isRestricted(String zoneId) {
        return classes.get(zoneId) == ZoneClass.RESTRICTED;
    }

    @Override
    public List<Zone> zones() {
        return loaded.stream().map(LoadedZone::zone).toList();
    }

    // ---- loading

    private List<LoadedZone> load(JsonNode collection) {
        List<LoadedZone> zones = new ArrayList<>();
        for (JsonNode feature : collection.get("features")) {
            JsonNode props = feature.get("properties");
            String id = props.get("zoneId").asText();
            String name = props.get("name").asText();
            ZoneClass zoneClass = ZoneClass.valueOf(props.get("class").asText());
            JsonNode geometry = feature.get("geometry");

            List<List<List<double[]>>> polygons = readPolygons(geometry);
            boolean crosses = crossesAntimeridian(polygons);
            if (crosses) {
                polygons = unwrap(polygons);
            }
            Geometry shape = toJts(polygons);

            TopologyValidationError problem = new IsValidOp(shape).getValidationError();
            if (problem != null) {
                log.warn("Zone {} is not a valid shape: {}", id, problem.getMessage());
            }
            if (crosses) {
                log.info("Zone {} crosses the 180 degree meridian; its longitudes are made continuous", id);
            }
            zones.add(
                    new LoadedZone(
                            new Zone(id, name, zoneClass, crosses, geoJson(geometry, polygons)),
                            PreparedGeometryFactory.prepare(shape)));
        }
        return List.copyOf(zones);
    }

    /** polygons, each a list of rings (the first is the outer boundary), each a list of [lon, lat]. */
    private static List<List<List<double[]>>> readPolygons(JsonNode geometry) {
        String type = geometry.get("type").asText();
        JsonNode coordinates = geometry.get("coordinates");
        List<List<List<double[]>>> polygons = new ArrayList<>();
        if (type.equals("Polygon")) {
            polygons.add(readRings(coordinates));
        } else if (type.equals("MultiPolygon")) {
            for (JsonNode polygon : coordinates) {
                polygons.add(readRings(polygon));
            }
        } else {
            throw new IllegalStateException("Unsupported zone geometry: " + type);
        }
        return polygons;
    }

    private static List<List<double[]>> readRings(JsonNode rings) {
        List<List<double[]>> result = new ArrayList<>();
        for (JsonNode ring : rings) {
            List<double[]> points = new ArrayList<>();
            for (JsonNode p : ring) {
                points.add(new double[] {p.get(0).asDouble(), p.get(1).asDouble()});
            }
            result.add(points);
        }
        return result;
    }

    /** A step of more than 180 degrees of longitude between two points means the edge crosses 180. */
    private static boolean crossesAntimeridian(List<List<List<double[]>>> polygons) {
        for (var polygon : polygons) {
            for (var ring : polygon) {
                for (int i = 1; i < ring.size(); i++) {
                    if (Math.abs(ring.get(i)[0] - ring.get(i - 1)[0]) > 180) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<List<List<double[]>>> unwrap(List<List<List<double[]>>> polygons) {
        List<List<List<double[]>>> result = new ArrayList<>();
        for (var polygon : polygons) {
            List<List<double[]>> rings = new ArrayList<>();
            for (var ring : polygon) {
                List<double[]> points = new ArrayList<>();
                for (double[] p : ring) {
                    points.add(new double[] {p[0] < 0 ? p[0] + 360 : p[0], p[1]});
                }
                rings.add(points);
            }
            result.add(rings);
        }
        return result;
    }

    private Geometry toJts(List<List<List<double[]>>> polygons) {
        Polygon[] shapes = new Polygon[polygons.size()];
        for (int i = 0; i < polygons.size(); i++) {
            List<List<double[]>> rings = polygons.get(i);
            LinearRing outer = ring(rings.get(0));
            LinearRing[] holes = new LinearRing[rings.size() - 1];
            for (int h = 1; h < rings.size(); h++) {
                holes[h - 1] = ring(rings.get(h));
            }
            shapes[i] = factory.createPolygon(outer, holes);
        }
        return shapes.length == 1 ? shapes[0] : factory.createMultiPolygon(shapes);
    }

    private LinearRing ring(List<double[]> points) {
        Coordinate[] coordinates = new Coordinate[points.size()];
        for (int i = 0; i < points.size(); i++) {
            coordinates[i] = new Coordinate(points.get(i)[0], points.get(i)[1]);
        }
        return factory.createLinearRing(coordinates);
    }

    /** GeoJSON for display, with continuous longitudes when the zone crosses 180. */
    private static Object geoJson(JsonNode original, List<List<List<double[]>>> polygons) {
        boolean multi = original.get("type").asText().equals("MultiPolygon");
        List<Object> all = new ArrayList<>();
        for (var polygon : polygons) {
            List<Object> rings = new ArrayList<>();
            for (var ring : polygon) {
                List<Object> points = new ArrayList<>();
                for (double[] p : ring) {
                    points.add(List.of(p[0], p[1]));
                }
                rings.add(points);
            }
            all.add(rings);
        }
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", multi ? "MultiPolygon" : "Polygon");
        geometry.put("coordinates", multi ? all : all.get(0));
        return geometry;
    }
}
