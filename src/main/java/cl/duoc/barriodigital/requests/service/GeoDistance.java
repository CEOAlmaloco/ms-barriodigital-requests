package cl.duoc.barriodigital.requests.service;

/** Distancias sobre una esfera, suficientes para radios de cientos de metros dentro de la comuna. */
final class GeoDistance {

    /** Radio medio de la Tierra (IUGG). */
    static final double EARTH_RADIUS_METERS = 6_371_008.8;

    /** Holgura de la caja: nunca debe dejar fuera un punto que la distancia exacta acepta. */
    private static final double BOX_MARGIN = 1.05;

    private GeoDistance() {
    }

    /** Distancia de Haversine en metros. */
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * Caja que contiene el círculo del radio. El ancho en longitud usa la latitud más alejada del ecuador dentro de
     * la caja, donde un grado es más corto. Vale lejos de los polos y del antimeridiano, como en la comuna.
     */
    static Box boxAround(double latitude, double longitude, double radiusMeters) {
        double dLat = Math.toDegrees(radiusMeters * BOX_MARGIN / EARTH_RADIUS_METERS);
        double widestLatitude = Math.toRadians(Math.abs(latitude) + dLat);
        double dLon = Math.toDegrees(radiusMeters * BOX_MARGIN / (EARTH_RADIUS_METERS * Math.cos(widestLatitude)));
        return new Box(latitude - dLat, latitude + dLat, longitude - dLon, longitude + dLon);
    }

    record Box(double south, double north, double west, double east) {
    }
}
