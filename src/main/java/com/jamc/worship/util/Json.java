package com.jamc.worship.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared JSON mapper. Unknown properties are ignored so older versions can read newer data. */
public final class Json {

    private static final Logger log = LoggerFactory.getLogger(Json.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    /** Parses {@code json}, returning null (and logging) for blank or malformed input instead of failing. */
    public static <T> T readOrNull(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Ignoring malformed {} JSON: {}", type.getSimpleName(), e.getOriginalMessage());
            return null;
        }
    }
}
