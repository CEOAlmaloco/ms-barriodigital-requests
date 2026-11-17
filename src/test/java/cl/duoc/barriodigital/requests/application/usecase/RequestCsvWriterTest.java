package cl.duoc.barriodigital.requests.application.usecase;

import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.RequestView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Formato del CSV para Excel y neutralización de fórmulas (EP3-63). */
class RequestCsvWriterTest {

    private static final String HEADER_LINE = "\"Folio\";\"Estado\";\"Tipo\";\"Título\";\"Dirección\";\"Sector\";"
            + "\"Ingreso\";\"Última actualización\";\"Plazo (días hábiles)\";\"En atención\";\"Responsable\";"
            + "\"Solicitante\";\"Correo solicitante\";\"Motivo de rechazo\"\r\n";

    @Test
    void sinFilasEntregaElBomYSoloElEncabezado() {
        byte[] csv = RequestCsvWriter.write(List.of());

        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
        assertEquals("﻿" + HEADER_LINE, new String(csv, StandardCharsets.UTF_8));
    }

    @Test
    void escribeCadaCeldaEntreComillasConSeparadorPuntoYComaYCrlf() {
        RequestView row = view("Poste \"caído\"; sin luz", "Calle 1\nfrente a la plaza", "Ñuñoa Pérez",
                "vecino@correo.cl", "ana", true, "Duplicado");

        String line = lines(RequestCsvWriter.write(List.of(row)))[1];

        assertEquals("\"BD-2026-000123\";\"EN_GESTION\";\"Bache en la vía\";\"Poste \"\"caído\"\"; sin luz\";"
                + "\"Calle 1\nfrente a la plaza\";\"Cauñicú\";\"2026-11-02 12:00\";\"2026-11-03 09:30\";\"10\";"
                + "\"Sí\";\"ana\";\"Ñuñoa Pérez\";\"vecino@correo.cl\";\"Duplicado\"", line);
    }

    @Test
    void lasFechasVanEnLaHoraDeChileTambienDespuesDelCambioDeHora() {
        // El 2026-04-05 Chile pasa a UTC-4 y el 2026-09-06 vuelve a UTC-3.
        RequestView invierno = withDates(Instant.parse("2026-04-05T12:00:00Z"), Instant.parse("2026-09-06T12:00:00Z"));

        String line = lines(RequestCsvWriter.write(List.of(invierno)))[1];

        assertTrue(line.contains("\"2026-04-05 08:00\";\"2026-09-06 09:00\""), line);
    }

    @Test
    void losCamposAusentesQuedanVaciosYSinResponsableDiceNo() {
        RequestView row = new RequestView("id-1", "BD-2026-000001", "t", "d", "bache", null, "dir", null, null,
                null, null, null, "vecino-a", null, null, RequestStatus.INGRESADO, null, false,
                Instant.parse("2026-11-02T15:00:00Z"), Instant.parse("2026-11-02T15:00:00Z"), false, null, null,
                null, null, null);

        String line = lines(RequestCsvWriter.write(List.of(row)))[1];

        assertEquals("\"BD-2026-000001\";\"INGRESADO\";\"bache\";\"t\";\"dir\";\"\";\"2026-11-02 12:00\";"
                + "\"2026-11-02 12:00\";\"\";\"No\";\"\";\"\";\"\";\"\"", line);
    }

    @ParameterizedTest
    @ValueSource(strings = {"=HYPERLINK(\"http://x\")", "+1", "-1+1", "@SUM(A1)", "  =1+1", "  -2",
            "　+3", "\t=1", "\r=1", "\n@x", "\u0000=1", "​=1", "﻿+1", "‎@x", "＝1+1",
            "＋1", "－1", "＠x", "\tTexto", "\u0007campana"})
    void neutralizaFormulasAunqueLasPrecedanEspaciosOControles(String value) {
        assertEquals("'" + value, RequestCsvWriter.neutralize(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bache frente al 12", "  Hoyo", "correo@municipio.cl", "BD-2026-000001", "1-2",
            "Calle 1 = esquina", "'ya citado", ""})
    void noTocaElTextoQueNoEmpiezaComoFormula(String value) {
        assertEquals(value, RequestCsvWriter.neutralize(value));
    }

    @Test
    void neutralizaTodasLasCeldasDeTextoDelVecinoYDelPersonal() {
        RequestView row = view("=cmd|' /C calc'!A0", "+Calle", "-Nombre", "@correo", "=Responsable", true, "=Motivo");

        String line = lines(RequestCsvWriter.write(List.of(row)))[1];

        for (String cell : List.of("'=cmd|' /C calc'!A0", "'+Calle", "'-Nombre", "'@correo", "'=Responsable",
                "'=Motivo")) {
            assertTrue(line.contains("\"" + cell + "\""), cell + " en " + line);
        }
    }

    private static String[] lines(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8);
        String[] lines = text.split("\r\n", -1);
        assertEquals("", lines[lines.length - 1], "cada línea termina en CRLF");
        return lines;
    }

    private static RequestView view(String title, String address, String name, String email, String assignee,
                                    boolean hasAssignee, String rejection) {
        return new RequestView("id-1", "BD-2026-000123", title, "desc", "bache", "Bache en la vía", address,
                "caunicu", null, null, null, null, "vecino-a", name, email, RequestStatus.EN_GESTION, rejection,
                false, Instant.parse("2026-11-02T15:00:00Z"), Instant.parse("2026-11-03T12:30:00Z"), hasAssignee,
                "func-1", assignee, 10, null, null);
    }

    private static RequestView withDates(Instant created, Instant updated) {
        return new RequestView("id-1", "BD-2026-000123", "t", "d", "bache", null, "dir", null, null, null, null,
                null, "vecino-a", null, null, RequestStatus.INGRESADO, null, false, created, updated, false, null,
                null, null, null, null);
    }
}
