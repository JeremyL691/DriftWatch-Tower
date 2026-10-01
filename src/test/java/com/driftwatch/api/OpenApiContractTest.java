package com.driftwatch.api;

import com.driftwatch.support.ContainerIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The generated OpenAPI document must stay in step with the input validation contract
 * (execution guide, sections 4.2 and 4.5): the documented event schema requires the same
 * fields the API rejects when missing, and the ingest endpoint is described with its 202
 * response.
 */
class OpenApiContractTest extends ContainerIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void eventSchemaMatchesTheValidationContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/api-docs").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode document = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode eventSchema = document.at("/components/schemas/DataEvent");
        assertThat(eventSchema.isMissingNode())
                .as("DataEvent schema must be published in the OpenAPI document")
                .isFalse();
        assertThat(eventSchema.get("required")).isNotNull();
        assertThat(toTextSet(eventSchema.get("required")))
                .containsExactlyInAnyOrder("event_id", "source", "event_type", "event_timestamp", "payload");

        JsonNode ingest = document.at("/paths/~1api~1v1~1events/post");
        assertThat(ingest.isMissingNode()).isFalse();
        java.util.List<String> responseCodes = new java.util.ArrayList<>();
        ingest.get("responses").fieldNames().forEachRemaining(responseCodes::add);
        assertThat(responseCodes)
                .as("ingest is asynchronous: 202 accepted is the documented success")
                .contains("202");
        assertThat(ingest.get("requestBody")).isNotNull();
    }

    @Test
    void managementEndpointsAreDocumented() throws Exception {
        MvcResult result = mockMvc.perform(get("/api-docs").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode document = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(document.at("/paths").has("/api/v1/alerts")).isTrue();
        assertThat(document.at("/paths").has("/api/v1/incidents")).isTrue();
        assertThat(document.at("/paths").has("/api/v1/schemas")).isTrue();
    }

    private static java.util.List<String> toTextSet(java.util.Iterator<JsonNode> nodes) {
        java.util.List<String> values = new java.util.ArrayList<>();
        nodes.forEachRemaining(node -> values.add(node.asText()));
        return values;
    }

    private static java.util.List<String> toTextSet(JsonNode arrayNode) {
        return toTextSet(arrayNode.elements());
    }
}