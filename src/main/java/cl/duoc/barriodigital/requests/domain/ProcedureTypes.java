package cl.duoc.barriodigital.requests.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Formato del código de tipo de trámite y armado del título.
 * Catalog decide qué códigos existen. Acá solo se valida el formato y se construye el título con la etiqueta y la dirección.
 */
public final class ProcedureTypes {

    /** Mismo formato que el value de catalog: minúsculas, números, guion o guion bajo, hasta 40 caracteres. */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9_-]{1,40}$");

    public static final Map<String, String> LABELS = Map.of(
            "bache", "Bache en calle",
            "alumbrado", "Alumbrado público",
            "basura", "Basura o limpieza",
            "agua", "Agua o alcantarillado",
            "ruido", "Ruido molesto",
            "otro", "Otro"
    );

    /** No se instancia: solo expone el formato y el título. */
    private ProcedureTypes() {
    }

    /** Quita espacios y pasa el código a minúsculas. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Indica si el código cumple el formato. No consulta catalog. */
    public static boolean hasValidFormat(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    /** Devuelve la etiqueta de la lista fija. Si el código no está, devuelve el mismo código. */
    public static String labelOf(String code) {
        return LABELS.getOrDefault(code, code);
    }

    /** Arma el título con la etiqueta de la lista fija y la dirección, recortado a 200 caracteres. */
    public static String buildTitle(String procedureTypeCode, String address) {
        return buildTitle(procedureTypeCode, address, null);
    }

    /**
     * Arma el título con la etiqueta que mandó catalog y la dirección, recortado a 200 caracteres.
     * Si la etiqueta no viene, usa la de la lista fija.
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

    /** Copia la lista fija de códigos y etiquetas. */
    public static Map<String, String> asMap() {
        return new LinkedHashMap<>(LABELS);
    }
}
