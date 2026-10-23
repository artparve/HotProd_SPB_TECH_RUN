package ru.repaircalc.common.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CommonWebMvcTest.TestController.class)
@Import({CommonWebMvcTest.TestController.class, ApiExceptionHandler.class, RequestIdFilter.class})
@ExtendWith(OutputCaptureExtension.class)
class CommonWebMvcTest {
    private static final String PATH = "/test/common-web";
    private static final String SECRET = "jdbc:postgresql://private-host/secret?password=do-not-expose";

    @Autowired
    private MockMvc mvc;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesServerRequestIdAndMakesItAvailableDuringRequest() throws Exception {
        MvcResult result = mvc.perform(get(PATH + "/request-id")
                        .header(RequestIdFilter.HEADER_NAME, "client-supplied-id"))
                .andExpect(status().isOk())
                .andReturn();

        String requestId = assertRequestId(result);
        assertThat(result.getResponse().getContentAsString()).contains(requestId);
        assertThat(requestId).isNotEqualTo("client-supplied-id");
    }

    @Test
    void generatesDifferentIdsAndKeepsUnrelatedMdcEntries() throws Exception {
        MDC.put("otherContext", "preserved");
        String first = assertRequestId(mvc.perform(get(PATH + "/request-id")).andReturn());
        String second = assertRequestId(mvc.perform(get(PATH + "/request-id")).andReturn());

        assertThat(second).isNotEqualTo(first);
        assertThat(MDC.get("otherContext")).isEqualTo("preserved");
    }

    @Test
    void returnsSafe400ForMalformedJson() throws Exception {
        MvcResult result = mvc.perform(post(PATH + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_json"))
                .andExpect(jsonPath("$.message").value("Request body must contain valid JSON."))
                .andExpect(jsonPath("$.fields").doesNotExist())
                .andExpect(content().string(not(containsString(SECRET))))
                .andReturn();

        assertRequestId(result);
    }

    @Test
    void returns422WithStringFieldPathsForInvalidDto() throws Exception {
        MvcResult result = mvc.perform(post(PATH + "/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields[0]").value("name"))
                .andExpect(jsonPath("$.fields.length()").value(1))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andReturn();

        assertRequestId(result);
    }

    @Test
    void returns422ForHandlerMethodConstraintsUsingExternalParameterName() throws Exception {
        MvcResult result = mvc.perform(get(PATH + "/quantity").param("count", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields[0]").value("count"))
                .andReturn();

        assertRequestId(result);
    }

    @Test
    void returnsSafe500ForInvalidControllerReturnValue() throws Exception {
        MvcResult result = mvc.perform(get(PATH + "/invalid-result"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.message").value("An internal server error occurred."))
                .andExpect(jsonPath("$.fields").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andReturn();

        assertRequestId(result);
    }

    @Test
    void returnsPublicDomainRejectionAs422() throws Exception {
        MvcResult result = mvc.perform(get(PATH + "/domain"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("needs_clarification"))
                .andExpect(jsonPath("$.message").value("Consumption norm is not confirmed."))
                .andExpect(jsonPath("$.fields[0]").value("consumption"))
                .andReturn();

        assertRequestId(result);
    }

    @Test
    void returnsSafe500AndCorrelatesLogWithoutLeakingExceptionMessage(CapturedOutput output) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            MvcResult result = mvc.perform(get(PATH + "/unexpected"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("internal_error"))
                    .andExpect(jsonPath("$.message").value("An internal server error occurred."))
                    .andExpect(jsonPath("$.fields").doesNotExist())
                    .andExpect(content().string(not(containsString(SECRET))))
                    .andExpect(jsonPath("$.exception").doesNotExist())
                    .andExpect(jsonPath("$.stackTrace").doesNotExist())
                    .andReturn();

            String requestId = assertRequestId(result);
            assertThat(output.getAll()).contains("requestId=" + requestId);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getMDCPropertyMap()).containsEntry(RequestIdFilter.MDC_KEY, requestId);
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void logsCompletionWithRequestIdMethodAndStatusWithoutRequestData(CapturedOutput output) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            MvcResult result = mvc.perform(get(PATH + "/request-id").param("secret", SECRET))
                    .andExpect(status().isOk())
                    .andReturn();

            String requestId = assertRequestId(result);
            assertThat(output.getAll()).contains("requestId=" + requestId);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(event.getMDCPropertyMap()).containsEntry(RequestIdFilter.MDC_KEY, requestId);
                assertThat(event.getFormattedMessage()).contains("method=GET", "status=200")
                        .doesNotContain(PATH, SECRET);
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void preservesNotFoundStatus() throws Exception {
        MvcResult result = mvc.perform(get(PATH + "/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andReturn();

        assertRequestId(result);
    }

    @Test
    void preservesMethodNotAllowedStatusAndAllowHeader() throws Exception {
        MvcResult result = mvc.perform(get(PATH + "/validate"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("method_not_allowed"))
                .andReturn();

        assertRequestId(result);
    }

    @Test
    void preservesUnsupportedMediaTypeStatus() throws Exception {
        MvcResult result = mvc.perform(post(PATH + "/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_media_type"))
                .andReturn();

        assertRequestId(result);
    }

    @Test
    void removesRequestIdEvenWhenFilterChainThrows() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(IOException.class, () -> new RequestIdFilter().doFilter(
                new MockHttpServletRequest(), response, (request, servletResponse) -> {
                    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNotBlank();
                    throw new IOException("Failed downstream");
                }));

        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER_NAME))).isNotNull();
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private static String assertRequestId(MvcResult result) {
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(requestId).isNotBlank();
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        return requestId;
    }

    @RestController
    @RequestMapping(PATH)
    public static class TestController {
        @GetMapping("/request-id")
        RequestIdResponse requestId() {
            return new RequestIdResponse(MDC.get(RequestIdFilter.MDC_KEY));
        }

        @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
        ValidatedRequest validate(@Valid @RequestBody ValidatedRequest request) {
            return request;
        }

        @GetMapping("/quantity")
        int quantity(@RequestParam("count") @Min(1) int quantity) {
            return quantity;
        }

        @GetMapping("/invalid-result")
        @Min(1)
        int invalidResult() {
            return 0;
        }

        @GetMapping("/domain")
        void domain() {
            throw new DomainException("needs_clarification", "Consumption norm is not confirmed.",
                    List.of("consumption"));
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException(SECRET);
        }
    }

    record ValidatedRequest(@NotBlank String name) {
    }

    record RequestIdResponse(String requestId) {
    }
}
