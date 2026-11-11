package cl.duoc.barriodigital.requests.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Página de un listado: la forma de EP3-07, sección 2.1, que también usan report y audit.
 * No se serializa el Page de Spring porque su JSON no es un contrato estable.
 */
public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    /** Convierte cada elemento con mapper y conserva los datos de la página. */
    public static <S, T> PageView<T> of(Page<S> source, Function<? super S, ? extends T> mapper) {
        List<T> content = source.getContent().stream().<T>map(mapper).toList();
        return new PageView<>(content, source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages());
    }
}
