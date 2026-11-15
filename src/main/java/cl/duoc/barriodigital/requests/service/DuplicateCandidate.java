package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;

/** Un posible duplicado. distanceMeters es la distancia exacta, o null si coincidió por sector. */
public record DuplicateCandidate(MunicipalRequest request, Double distanceMeters) {
}
