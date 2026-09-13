package cl.duoc.barriodigital.requests;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EP1-21: en git solo van placeholders; las claves reales viven en .env local.
 */
class OracleEnvExampleTest {

    @Test
    void envExampleTieneLasClavesOracleSinValoresSecretos() throws Exception {
        Path example = Path.of(".env.example");
        assertTrue(Files.exists(example), "Debe existir .env.example en la raíz del módulo");

        List<String> lines = Files.readAllLines(example);
        String content = String.join("\n", lines);

        assertTrue(content.contains("ORACLE_URL="), "Falta ORACLE_URL");
        assertTrue(content.contains("ORACLE_USER="), "Falta ORACLE_USER");
        assertTrue(content.contains("ORACLE_PASSWORD="), "Falta ORACLE_PASSWORD");
        assertTrue(content.contains("ORACLE_WALLET_DIR="), "Falta ORACLE_WALLET_DIR");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("ORACLE_") && trimmed.contains("=")) {
                String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                assertTrue(value.isEmpty() || value.startsWith("#"),
                        "El example no debe traer secretos reales: " + trimmed);
            }
        }
    }

    @Test
    void gitignoreIgnoraEnvYWallets() throws Exception {
        String gitignore = Files.readString(Path.of(".gitignore"));
        assertTrue(gitignore.contains(".env"));
        assertTrue(gitignore.contains("wallet/"));
        assertTrue(gitignore.contains("!.env.example"));
    }
}
