package cl.duoc.barriodigital.requests.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * EP3.5-04: cada eventType tiene schemaVersion; un sample incompatible falla.
 */
class EventSchemaContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cadaEventTypeTieneEsquemaYSampleCompatible() throws Exception {
        JsonNode catalog = read("contracts/events/catalog.json");
        assertThat(catalog.get("eventTypes").isObject()).isTrue();
        assertThat(catalog.get("eventTypes").size()).isGreaterThan(0);

        Iterator<Map.Entry<String, JsonNode>> it = catalog.get("eventTypes").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String eventType = entry.getKey();
            int version = entry.getValue().get("schemaVersion").asInt();
            String file = entry.getValue().get("file").asText();
            String sampleName = file.replace(".schema.json", ".json");

            JsonNode sample = read("contracts/events/samples/" + sampleName);
            assertThat(sample.get("eventType").asText()).isEqualTo(eventType);
            assertThat(sample.get("schemaVersion").asInt())
                    .as("schemaVersion incompatible para %s", eventType)
                    .isEqualTo(version);
            assertThat(sample.get("eventId").asText()).isNotBlank();
            assertThat(sample.get("aggregateId").asText()).isNotBlank();
            assertThat(sample.get("occurredAt").asText()).isNotBlank();
            assertThat(sample.get("payload").isObject()).isTrue();

            JsonNode schema = read("contracts/events/" + file);
            assertThat(schema.get("properties").get("eventType").get("const").asText())
                    .isEqualTo(eventType);
            assertThat(schema.get("properties").get("schemaVersion").get("const").asInt())
                    .isEqualTo(version);
        }
    }

    private JsonNode read(String classpath) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpath)) {
            assertThat(in).as(classpath).isNotNull();
            return mapper.readTree(in);
        }
    }
}
