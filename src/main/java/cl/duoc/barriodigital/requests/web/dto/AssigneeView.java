package cl.duoc.barriodigital.requests.web.dto;

/** Funcionario al que se le puede asignar un trámite. Solo id y nombre: sin correo ni fechas (EP3-05, sección 5.2). */
public record AssigneeView(String id, String name) {
}
