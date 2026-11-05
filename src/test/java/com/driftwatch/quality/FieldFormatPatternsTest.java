package com.driftwatch.quality;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldFormatPatternsTest {

    @Test
    void parseEmptyAndBlankSpec() {
        assertThat(FieldFormatPatterns.parse(null)).isEmpty();
        assertThat(FieldFormatPatterns.parse("")).isEmpty();
        assertThat(FieldFormatPatterns.parse("   ")).isEmpty();
    }

    @Test
    void parseLegacyCommaSeparatedPatterns() {
        Map<String, Pattern> patterns = FieldFormatPatterns.parse("email=^[\\w.-]+@[\\w.-]+$, code=^[A-Z0-9]+$");
        assertThat(patterns).containsOnlyKeys("email", "code");
        assertThat(patterns.get("email").matcher("test@example.com").matches()).isTrue();
        assertThat(patterns.get("code").matcher("ABC12").matches()).isTrue();
    }

    @Test
    void parseLegacyIgnoresExtraCommasAndWhitespace() {
        Map<String, Pattern> patterns = FieldFormatPatterns.parse(" , email=^[a-z]+$ , , code=^[0-9]+$ , ");
        assertThat(patterns).containsOnlyKeys("email", "code");
    }

    @Test
    void parseLegacyRejectsMalformedEntries() {
        assertThatThrownBy(() -> FieldFormatPatterns.parse("email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must look like field=regex");

        assertThatThrownBy(() -> FieldFormatPatterns.parse("=regex"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must look like field=regex");

        assertThatThrownBy(() -> FieldFormatPatterns.parse("email="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must look like field=regex");

        assertThatThrownBy(() -> FieldFormatPatterns.parse("email=[invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void parseJsonObjectSupportingCommasInRegex() {
        String json = "{\"code\": \"^[A-Z]{2,5}$\", \"sku\": \"^SKU-[0-9]{3,6}$\"}";
        Map<String, Pattern> patterns = FieldFormatPatterns.parse(json);

        assertThat(patterns).containsOnlyKeys("code", "sku");
        assertThat(patterns.get("code").matcher("AB").matches()).isTrue();
        assertThat(patterns.get("code").matcher("ABCDE").matches()).isTrue();
        assertThat(patterns.get("code").matcher("A").matches()).isFalse();
        assertThat(patterns.get("code").matcher("ABCDEF").matches()).isFalse();

        assertThat(patterns.get("sku").matcher("SKU-1234").matches()).isTrue();
        assertThat(patterns.get("sku").matcher("SKU-12").matches()).isFalse();
    }

    @Test
    void parseJsonObjectRejectsMalformedJson() {
        assertThatThrownBy(() -> FieldFormatPatterns.parse("{\"code\": \"^[A-Z]+$\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void parseJsonObjectRejectsNonObject() {
        assertThatThrownBy(() -> FieldFormatPatterns.parse("[\"code=^[A-Z]+$\"]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    void parseJsonObjectRejectsNonStringValues() {
        assertThatThrownBy(() -> FieldFormatPatterns.parse("{\"code\": 123}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a string");
    }

    @Test
    void parseJsonObjectRejectsEmptyFieldOrRegex() {
        assertThatThrownBy(() -> FieldFormatPatterns.parse("{\"\": \"^[A-Z]+$\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");

        assertThatThrownBy(() -> FieldFormatPatterns.parse("{\"code\": \"   \"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void parseJsonObjectRejectsInvalidRegexSyntax() {
        assertThatThrownBy(() -> FieldFormatPatterns.parse("{\"code\": \"(unclosed\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }
}
