package cl.duoc.barriodigital.requests.application.usecase;

import cl.duoc.barriodigital.requests.domain.CivilTime;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.RequestView;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CSV del listado para Excel en configuración chilena: UTF-8 con BOM, separador punto y coma y fin de línea CRLF.
 * Cada celda va entre comillas (RFC 4180). Solo escribe lo que ya trae el {@link RequestView} de quien exporta.
 */
public final class RequestCsvWriter {

    static final String SEPARATOR = ";";
    static final String LINE_END = "\r\n";
    private static final String BOM = "﻿";

    static final List<String> HEADER = List.of(
            "Folio", "Estado", "Tipo", "Título", "Dirección", "Sector", "Ingreso", "Última actualización",
            "Plazo (días hábiles)", "En atención", "Responsable", "Solicitante", "Correo solicitante",
            "Motivo de rechazo");

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(CivilTime.ZONE);

    private RequestCsvWriter() {
    }

    public static byte[] write(List<RequestView> rows) {
        StringBuilder csv = new StringBuilder(BOM);
        appendLine(csv, HEADER);
        for (RequestView row : rows) {
            appendLine(csv, List.of(
                    text(row.folio()),
                    row.status() == null ? "" : row.status().name(),
                    text(row.procedureLabel() != null ? row.procedureLabel() : row.procedureType()),
                    text(row.title()),
                    text(row.address()),
                    text(sectorLabel(row.sector())),
                    dateTime(row.createdAt()),
                    dateTime(row.updatedAt()),
                    row.slaBusinessDays() == null ? "" : row.slaBusinessDays().toString(),
                    row.hasAssignee() ? "Sí" : "No",
                    text(row.assigneeName()),
                    text(row.solicitanteNombre()),
                    text(row.solicitanteEmail()),
                    text(row.rejectionReason())));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Neutraliza una celda que Excel o LibreOffice ejecutarían como fórmula (CSV injection, OWASP).
     * Mira el primer carácter visible, después de espacios y caracteres de control, porque las planillas los
     * saltan al interpretar. Un control al inicio también se neutraliza: tabulador y retorno son disparadores.
     */
    static String neutralize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        boolean leadingControl = false;
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || isInvisibleFormat(c)) {
                leadingControl = true;
            } else if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                break;
            }
            i++;
        }
        boolean formula = i < value.length() && isFormulaTrigger(value.charAt(i));
        return leadingControl || formula ? "'" + value : value;
    }

    private static boolean isFormulaTrigger(char c) {
        return switch (c) {
            // Más sus formas de ancho completo, que Excel también acepta.
            case '=', '+', '-', '@', '＝', '＋', '－', '＠' -> true;
            default -> false;
        };
    }

    /** BOM, marcas de dirección y espacios de ancho cero: no se ven y una planilla los puede saltar. */
    private static boolean isInvisibleFormat(char c) {
        return Character.getType(c) == Character.FORMAT;
    }

    private static String text(String value) {
        return value == null ? "" : neutralize(value);
    }

    private static String dateTime(Instant value) {
        return value == null ? "" : DATE_TIME.format(value);
    }

    private static String sectorLabel(String key) {
        return key == null ? null : LocationRules.SECTORS.getOrDefault(key, key);
    }

    private static void appendLine(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(SEPARATOR);
            }
            csv.append('"').append(cells.get(i).replace("\"", "\"\"")).append('"');
        }
        csv.append(LINE_END);
    }
}
