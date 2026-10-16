package cl.duoc.barriodigital.requests.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Códigos canónicos de tipo de trámite (contrato front ↔ back).
 * El front debe enviar el código (value), no el texto visible del combo.
 * Qué códigos existen lo decide catalog (lo valida el BFF). Acá solo se revisa el formato.
 */
public final class ProcedureTypes {

    /** Mismo formato que el value de catalog. */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9_-]{1,40}$");

    public static final Map<String, String> LABELS = Map.of(
            "bache", "Bache en calle",
            "alumbrado", "Alumbrado público",
            "basura", "Basura o limpieza",
            "agua", "Agua o alcantarillado",
            "ruido", "Ruido molesto",
            "otro", "Otro"
    );

    private ProcedureTypes() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean hasValidFormat(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    public static String labelOf(String code) {
        return LABELS.getOrDefault(code, code);
    }

    /** Título autogenerado: "{etiqueta} — {direccion}" (máx. 200). */
    public static String buildTitle(String procedureTypeCode, String address) {
        return buildTitle(procedureTypeCode, address, null);
    }

    /**
     * Igual que {@link #buildTitle(String, String)}, pero la etiqueta es el label que mandó catalog.
     * Si no viene, usa {@link #labelOf(String)}.
     */
    public static String buildTitle(String procedureTypeCode, String address, String procedureLabel) {
        String label = procedureLabel == null || procedureLabel.isBlank()
                ? labelOf(procedureTypeCode)
                : procedureLabel.trim();
        String base = label + " — " + address.trim();
        if (base.length() <= 200) {
            return base;
        }
        return base.substring(0, 200);
    }

    public static Map<String, String> asMap() {
        return new LinkedHashMap<>(LABELS);
    }
}
