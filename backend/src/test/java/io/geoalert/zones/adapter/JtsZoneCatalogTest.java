package io.geoalert.zones.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.geoalert.zones.domain.Zone;
import io.geoalert.zones.domain.ZoneClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The zone geometry: holes, several parts, overlaps, edges and the 180 degree meridian. */
class JtsZoneCatalogTest {

    private static JtsZoneCatalog zones;

    @BeforeAll
    static void load() throws IOException {
        zones = new JtsZoneCatalog(new ObjectMapper(), "classpath:zones.geojson");
    }

    private static Set<String> at(double lat, double lon) {
        return zones.zonesContaining(lat, lon);
    }

    @Test
    void theTwelveSuppliedZonesAreLoaded() {
        List<Zone> all = zones.zones();
        assertThat(all).hasSize(12);
        assertThat(all.stream().filter(z -> z.zoneClass() == ZoneClass.RESTRICTED)).hasSize(10);
        assertThat(all.stream().filter(z -> z.zoneClass() == ZoneClass.ADVISORY))
                .extracting(Zone::id)
                .containsExactlyInAnyOrder("Z-09", "Z-10");
    }

    @Test
    void aPlainZoneContainsItsInsideAndNotItsOutside() {
        // Z-01 covers lon 168.0 to 168.8 and lat 38.0 to 38.6
        assertThat(at(38.2, 168.4)).containsExactly("Z-01");
        assertThat(at(37.9, 168.4)).isEmpty();
        assertThat(at(38.2, 167.9)).isEmpty();
        assertThat(at(38.7, 168.4)).isEmpty();
    }

    @Test
    void aPointOnTheEdgeOrCornerCountsAsInside() {
        assertThat(at(38.0, 168.4)).contains("Z-01");
        assertThat(at(38.0, 168.0)).contains("Z-01");
        assertThat(at(38.6, 168.8)).contains("Z-01");
    }

    @Test
    void aPointInsideAHoleIsNotInTheZone() {
        // Z-03: outer lon 172.5-173.9, lat 39.0-40.1; hole lon 173.0-173.4, lat 39.4-39.8
        assertThat(at(39.6, 173.2)).doesNotContain("Z-03");
        assertThat(at(39.05, 172.55)).contains("Z-03");
        assertThat(at(40.0, 173.2)).contains("Z-03");
        assertThat(at(39.6, 172.7)).contains("Z-03");
    }

    @Test
    void aPointOnTheEdgeOfAHoleCountsAsInTheZone() {
        assertThat(at(39.6, 173.0)).contains("Z-03");
    }

    @Test
    void aZoneWithSeveralPartsContainsAPointInEitherPart() {
        // Z-06 parts: lon 167.0-167.5 lat 33.0-33.5, and lon 167.9-168.4 lat 33.2-33.8
        assertThat(at(33.25, 167.25)).contains("Z-06");
        assertThat(at(33.5, 168.15)).contains("Z-06");
        // between the parts
        assertThat(at(33.25, 167.7)).doesNotContain("Z-06");
    }

    @Test
    void overlappingZonesBothContainThePointInTheOverlap() {
        // Z-04 lon 174.0-175.0 lat 32.0-32.8; Z-05 lon 174.6-175.6 lat 32.4-33.2
        assertThat(at(32.6, 174.8)).containsExactlyInAnyOrder("Z-04", "Z-05");
        assertThat(at(32.1, 174.1)).containsExactly("Z-04");
        assertThat(at(33.1, 175.5)).containsExactly("Z-05");
    }

    @Test
    void advisoryZonesAreFoundButAreNotRestricted() {
        Zone advisory =
                zones.zones().stream().filter(z -> z.id().equals("Z-09")).findFirst().orElseThrow();
        assertThat(zones.isRestricted("Z-09")).isFalse();
        assertThat(zones.isRestricted("Z-10")).isFalse();
        assertThat(zones.isRestricted("Z-01")).isTrue();
        assertThat(advisory.zoneClass()).isEqualTo(ZoneClass.ADVISORY);
        assertThat(zones.isRestricted("no-such-zone")).isFalse();
    }

    @Test
    void aVerySmallZoneIsStillFoundAndTheNeighbourhoodIsNot() {
        // Z-11 is 0.03 degrees wide: lon 169.0-169.03, lat 31.0-31.03
        assertThat(at(31.015, 169.015)).containsExactly("Z-11");
        assertThat(at(31.015, 169.05)).isEmpty();
    }

    @Test
    void aZoneAcrossThe180MeridianIsFoundOnBothSides() {
        // Z-08 lon 179.3 east to -179.4 (that is 180.6), lat 34-35
        assertThat(at(34.5, 179.6)).containsExactly("Z-08");
        assertThat(at(34.5, 180.0)).containsExactly("Z-08");
        assertThat(at(34.5, -180.0)).containsExactly("Z-08");
        assertThat(at(34.5, -179.8)).containsExactly("Z-08");
        assertThat(at(34.5, -179.5)).containsExactly("Z-08");
        // outside on either side
        assertThat(at(34.5, 179.0)).isEmpty();
        assertThat(at(34.5, -179.3)).isEmpty();
        assertThat(at(35.5, 179.6)).isEmpty();
    }

    @Test
    void aZoneAcrossThe180MeridianIsMarkedAndHasContinuousLongitudes() {
        Zone z08 =
                zones.zones().stream().filter(z -> z.id().equals("Z-08")).findFirst().orElseThrow();
        assertThat(z08.crossesAntimeridian()).isTrue();
        JsonNode geometry = new ObjectMapper().valueToTree(z08.geometry());
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (JsonNode point : geometry.get("coordinates").get(0)) {
            minLon = Math.min(minLon, point.get(0).asDouble());
            maxLon = Math.max(maxLon, point.get(0).asDouble());
        }
        assertThat(minLon).isEqualTo(179.3);
        assertThat(maxLon).isEqualTo(180.6);
    }

    @Test
    void aNeighbouringZoneWithNegativeLongitudesIsNotAffected() {
        // Z-12 lon -178.5 to -177.6, lat 37.0-37.8: it does not cross, so it is tested as it is
        Zone z12 =
                zones.zones().stream().filter(z -> z.id().equals("Z-12")).findFirst().orElseThrow();
        assertThat(z12.crossesAntimeridian()).isFalse();
        assertThat(at(37.4, -178.0)).containsExactly("Z-12");
        assertThat(at(37.4, -179.0)).isEmpty();
        // a positive longitude is never mistaken for it
        assertThat(at(37.4, 178.0)).isEmpty();
    }

    @Test
    void aPointOutsideEveryZoneContainsNothing() {
        assertThat(at(0, 0)).isEmpty();
        assertThat(at(-45, 100)).isEmpty();
    }

    @Test
    void onlyOneZoneAcrossTheMeridianIsMarked() {
        assertThat(zones.zones().stream().filter(Zone::crossesAntimeridian))
                .extracting(Zone::id)
                .containsExactly("Z-08");
    }
}
