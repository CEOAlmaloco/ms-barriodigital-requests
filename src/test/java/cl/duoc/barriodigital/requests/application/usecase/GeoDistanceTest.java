package cl.duoc.barriodigital.requests.application.usecase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Haversine y la caja de la consulta de duplicados (EP3-75), con puntos de la comuna. */
class GeoDistanceTest {

    private static final double LAT = -37.9;
    private static final double LON = -71.6;

    @Test
    void unPuntoConsigoMismoEstaACeroMetros() {
        assertEquals(0.0, GeoDistance.meters(LAT, LON, LAT, LON), 1e-9);
    }

    @Test
    void unaMilesimaDeGradoDeLatitudSonUnos111Metros() {
        assertEquals(111.19, GeoDistance.meters(LAT, LON, LAT + 0.001, LON), 0.01);
    }

    @Test
    void enLongitudUnGradoEsMasCortoQueEnLatitud() {
        double alongParallel = GeoDistance.meters(LAT, LON, LAT, LON + 0.001);
        assertEquals(111.19 * Math.cos(Math.toRadians(LAT)), alongParallel, 0.05);
    }

    @Test
    void esSimetrica() {
        assertEquals(
                GeoDistance.meters(LAT, LON, -37.91, -71.62),
                GeoDistance.meters(-37.91, -71.62, LAT, LON),
                1e-9);
    }

    @Test
    void laCajaContieneTodoElBordeDelCirculoEnCualquierDireccion() {
        double radius = 2000;
        GeoDistance.Box box = GeoDistance.boxAround(LAT, LON, radius);
        for (int bearing = 0; bearing < 360; bearing += 5) {
            double[] edge = destination(LAT, LON, bearing, radius);
            assertEquals(radius, GeoDistance.meters(LAT, LON, edge[0], edge[1]), 0.01);
            assertTrue(edge[0] >= box.south() && edge[0] <= box.north(), "latitud en rumbo " + bearing);
            assertTrue(edge[1] >= box.west() && edge[1] <= box.east(), "longitud en rumbo " + bearing);
        }
    }

    /** Punto a esa distancia y rumbo sobre la misma esfera. */
    private static double[] destination(double lat, double lon, double bearingDegrees, double meters) {
        double delta = meters / GeoDistance.EARTH_RADIUS_METERS;
        double theta = Math.toRadians(bearingDegrees);
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lon);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta) + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
        double lambda2 = lambda1 + Math.atan2(
                Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
        return new double[]{Math.toDegrees(phi2), Math.toDegrees(lambda2)};
    }
}
