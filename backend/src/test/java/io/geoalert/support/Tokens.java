package io.geoalert.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Test helper: signs in through the real login endpoint and returns the bearer token. */
public final class Tokens {

    private Tokens() {}

    public static String login(MockMvc mvc, ObjectMapper mapper, String username)
            throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        java.util.Map.of(
                                                                "username",
                                                                username,
                                                                "password",
                                                                PostgresIntegrationTest
                                                                        .TEST_PASSWORD))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode json = mapper.readTree(body);
        return json.get("accessToken").asText();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
