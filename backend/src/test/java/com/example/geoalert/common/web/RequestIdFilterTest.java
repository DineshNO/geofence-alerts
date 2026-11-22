package com.example.geoalert.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.geoalert.health.HealthController;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(RequestIdFilter.class)
class RequestIdFilterTest {

    @Autowired private MockMvc mvc;

    private String requestIdFor(String incoming) throws Exception {
        var request = get("/health");
        if (incoming != null) {
            request.header(RequestIdFilter.HEADER, incoming);
        }
        return mvc.perform(request).andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
    }

    @Test
    void echoesTheCallersRequestId() throws Exception {
        assertThat(requestIdFor("abc-123.DEF_4")).isEqualTo("abc-123.DEF_4");
    }

    @Test
    void createsARequestIdWhenNoneIsSent() throws Exception {
        String id = requestIdFor(null);
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void replacesAnUnsafeRequestId() throws Exception {
        // spaces and symbols could be used to forge or confuse log lines
        String id = requestIdFor("bad id; DROP");
        assertThat(id).isNotEqualTo("bad id; DROP");
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void replacesARequestIdThatIsTooLong() throws Exception {
        String tooLong = "a".repeat(65);
        String id = requestIdFor(tooLong);
        assertThat(id).isNotEqualTo(tooLong);
        assertThat(UUID.fromString(id)).isNotNull();
    }
}
