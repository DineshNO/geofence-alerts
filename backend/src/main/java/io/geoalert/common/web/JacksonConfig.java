package io.geoalert.common.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Every time in the API is UTC, ISO-8601, with milliseconds and a Z. */
@Configuration
class JacksonConfig {

    static final DateTimeFormatter API_INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Bean
    Module apiInstantModule() {
        SimpleModule module = new SimpleModule("api-instant");
        module.addSerializer(
                Instant.class,
                new JsonSerializer<>() {
                    @Override
                    public void serialize(Instant value, JsonGenerator gen, SerializerProvider p)
                            throws IOException {
                        gen.writeString(API_INSTANT.format(value));
                    }
                });
        return module;
    }
}
