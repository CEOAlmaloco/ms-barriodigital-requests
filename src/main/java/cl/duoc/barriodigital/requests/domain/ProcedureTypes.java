package cl.duoc.barriodigital.requests.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Códigos canónicos de tipo de trámite (contrato front ↔ back).
 * El front debe enviar el código (value), no el texto visible del combo.
 */
public final class ProcedureTypes {

    public static final Map<String, String> LABELS = Map.of(
            "bache", "Bache en calle",
            "alumbrado", "Alumbrado publico",
            "basura", "Basura o limpieza",
            "agua", "Agua o alcantarillado",
            "ruido", "Ruido molesto",
            "otro", "Otro"
    );

    public static final Set<String> CODES = LABELS.keySet();

    private ProcedureTypes() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isAllowed(String code) {
        return code != null && CODES.contains(code);
    }

    public static String labelOf(String code) {
        return LABELS.getOrDefault(code, code);
    }

    /** Título autogenerado: "{etiqueta} — {direccion}" (máx. 200). */
    public static String buildTitle(String procedureTypeCode, String address) {
        String base = labelOf(procedureTypeCode) + " — " + address.trim();
        if (base.length() <= 200) {
            return base;
        }
        return base.substring(0, 200);
    }

    public static Map<String, String> asMap() {
        return new LinkedHashMap<>(LABELS);
    }
}
