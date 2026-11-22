package com.example.geoalert.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ProblemExceptionHandlerTest.ThrowingController.class)
@Import({ProblemExceptionHandler.class, ProblemExceptionHandlerTest.ThrowingController.class})
class ProblemExceptionHandlerTest {

    @RestController
    @RequestMapping("/test")
    static class ThrowingController {

        @GetMapping("/api-error")
        String apiError() {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "The alert is resolved.");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail: select * from users");
        }

        @PostMapping(value = "/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
        String jsonOnly() {
            return "ok";
        }
    }

    @Autowired private MockMvc mvc;

    @Test
    void anApiExceptionBecomesProblemJsonWithItsCode() throws Exception {
        mvc.perform(get("/test/api-error"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("The alert is resolved."));
    }

    @Test
    void anUnexpectedErrorHidesItsDetails() throws Exception {
        String body =
                mvc.perform(get("/test/boom"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContain("secret").doesNotContain("select").doesNotContain("users");
        assertThat(body).doesNotContain("Exception").doesNotContain("at com.example");
    }

    @Test
    void anUnknownPathIsNotFoundWithACode() throws Exception {
        mvc.perform(get("/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anUnsupportedMediaTypeGetsItsCode() throws Exception {
        mvc.perform(post("/test/json-only").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
