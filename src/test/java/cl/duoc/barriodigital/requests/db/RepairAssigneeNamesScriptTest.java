package cl.duoc.barriodigital.requests.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Corre el script real de reparación sobre H2 en modo Oracle, con solo las columnas que usa.
 * Valida la regla (nombre igual al id y fuente válida), no el entorno Oracle desplegado.
 */
class RepairAssigneeNamesScriptTest {

    private Connection db;

    @BeforeEach
    void schema() throws SQLException {
        db = DriverManager.getConnection("jdbc:h2:mem:repair-" + UUID.randomUUID() + ";MODE=Oracle");
        exec("CREATE TABLE staff_member (oid VARCHAR2(64) PRIMARY KEY, name VARCHAR2(200) NOT NULL)");
        exec("CREATE TABLE municipal_requests (id VARCHAR2(36) PRIMARY KEY, assignee_id VARCHAR2(64),"
                + " assignee_name VARCHAR2(200))");
        exec("CREATE TABLE request_activity (id VARCHAR2(36) PRIMARY KEY, assignee_id VARCHAR2(64),"
                + " assignee_name VARCHAR2(200), previous_assignee_id VARCHAR2(64),"
                + " previous_assignee_name VARCHAR2(200))");
        exec("INSERT INTO staff_member VALUES ('oid-ana', '  Ana María  ')");
        exec("INSERT INTO staff_member VALUES ('oid-luis', 'oid-luis')");
    }

    @Test
    void reparaSoloLosNombresIgualesAlIdConFuenteValida() throws Exception {
        exec("INSERT INTO municipal_requests VALUES ('r-1', 'oid-ana', 'oid-ana')");
        exec("INSERT INTO municipal_requests VALUES ('r-2', 'oid-luis', 'oid-luis')");
        exec("INSERT INTO municipal_requests VALUES ('r-3', 'oid-ana', 'Ana Antes')");
        exec("INSERT INTO municipal_requests VALUES ('r-4', 'oid-ext', 'oid-ext')");
        exec("INSERT INTO municipal_requests VALUES ('r-5', NULL, NULL)");
        exec("INSERT INTO request_activity VALUES ('a-1', 'oid-ana', 'oid-ana', 'oid-luis', 'oid-luis')");
        exec("INSERT INTO request_activity VALUES ('a-2', 'oid-luis', 'oid-luis', 'oid-ana', 'oid-ana')");

        runScript();

        assertEquals("Ana María", value("SELECT assignee_name FROM municipal_requests WHERE id = 'r-1'"));
        assertEquals("oid-luis", value("SELECT assignee_name FROM municipal_requests WHERE id = 'r-2'"));
        assertEquals("Ana Antes", value("SELECT assignee_name FROM municipal_requests WHERE id = 'r-3'"));
        assertEquals("oid-ext", value("SELECT assignee_name FROM municipal_requests WHERE id = 'r-4'"));
        assertNull(value("SELECT assignee_name FROM municipal_requests WHERE id = 'r-5'"));
        assertEquals("Ana María", value("SELECT assignee_name FROM request_activity WHERE id = 'a-1'"));
        assertEquals("oid-luis", value("SELECT previous_assignee_name FROM request_activity WHERE id = 'a-1'"));
        assertEquals("oid-luis", value("SELECT assignee_name FROM request_activity WHERE id = 'a-2'"));
        assertEquals("Ana María", value("SELECT previous_assignee_name FROM request_activity WHERE id = 'a-2'"));
    }

    @Test
    void sePuedeCorrerDosVeces() throws Exception {
        exec("INSERT INTO municipal_requests VALUES ('r-1', 'oid-ana', 'oid-ana')");

        runScript();
        runScript();

        assertEquals("Ana María", value("SELECT assignee_name FROM municipal_requests WHERE id = 'r-1'"));
    }

    /** WHENEVER SQLERROR es una directiva de SQLcl y SQL*Plus, no SQL: el resto del archivo corre tal cual. */
    private void runScript() throws Exception {
        String script;
        try (var in = new ClassPathResource("db/repair-assignee-names.sql").getInputStream()) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("WHENEVER"))
                    .collect(Collectors.joining("\n"));
        }
        ScriptUtils.executeSqlScript(db, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private String value(String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
