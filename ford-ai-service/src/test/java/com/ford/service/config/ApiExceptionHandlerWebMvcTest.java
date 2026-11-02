package com.ford.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiExceptionHandlerWebMvcTest.MethodProbeController.class)
@Import({ApiExceptionHandler.class, ApiExceptionHandlerWebMvcTest.MethodProbeController.class})
@AutoConfigureMockMvc(addFilters = false)
class ApiExceptionHandlerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void exposesThePathAndAllowedMethodsForA405Response() throws Exception {
        mockMvc.perform(post("/method-probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.title").value("HTTP method not supported"))
                .andExpect(jsonPath("$.path").value("/method-probe"))
                .andExpect(jsonPath("$.method").value("POST"));
    }

    @RestController
    @RequestMapping("/method-probe")
    static class MethodProbeController {
        @GetMapping
        String get() {
            return "ok";
        }
    }
}
