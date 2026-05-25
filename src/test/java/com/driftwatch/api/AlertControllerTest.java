package com.driftwatch.api;

import com.driftwatch.persistence.QualityAlertEntity;
import com.driftwatch.persistence.QualityAlertRepository;
import com.driftwatch.quality.AlertType;
import com.driftwatch.quality.Severity;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertControllerTest {

    @Test
    void listUsesCombinedFilterWhenTypeAndSourceAreBothProvided() throws Exception {
        AtomicReference<AlertType> capturedType = new AtomicReference<>();
        AtomicReference<String> capturedSource = new AtomicReference<>();
        AtomicReference<PageRequest> capturedPage = new AtomicReference<>();

        QualityAlertEntity alert = alert(AlertType.SCHEMA_DRIFT, "demo-api");
        QualityAlertRepository repository = proxy(QualityAlertRepository.class, (method, args) -> switch (method.getName()) {
            case "findByAlertTypeAndSourceOrderByCreatedAtDesc" -> {
                capturedType.set((AlertType) args[0]);
                capturedSource.set((String) args[1]);
                capturedPage.set((PageRequest) args[2]);
                yield new PageImpl<>(List.of(alert));
            }
            default -> unsupported(method.getName());
        });

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AlertController(repository)).build();

        mockMvc.perform(get("/alerts")
                        .param("type", "SCHEMA_DRIFT")
                        .param("source", "demo-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alert_type").value("SCHEMA_DRIFT"))
                .andExpect(jsonPath("$[0].source").value("demo-api"));

        assertThat(capturedType.get()).isEqualTo(AlertType.SCHEMA_DRIFT);
        assertThat(capturedSource.get()).isEqualTo("demo-api");
        assertThat(capturedPage.get()).isEqualTo(PageRequest.of(0, 50));
    }

    private static QualityAlertEntity alert(AlertType type, String source) {
        QualityAlertEntity entity = new QualityAlertEntity();
        entity.setAlertType(type);
        entity.setSeverity(Severity.WARN);
        entity.setSource(source);
        entity.setEventType("market_tick");
        entity.setFieldPath("payload.ask");
        entity.setMessage("test");
        entity.setEvidenceJson(JsonNodeFactory.instance.objectNode().put("ok", true));
        entity.setCreatedAt(Instant.parse("2026-05-25T00:00:00Z"));
        return entity;
    }

    private static Object unsupported(String methodName) {
        throw new UnsupportedOperationException("Unexpected repository method call: " + methodName);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "Proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> method.invoke(proxy, args);
                        };
                    }
                    return invocation.invoke(method, args == null ? new Object[0] : args);
                }
        );
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
