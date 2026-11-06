package ru.repaircalc.llm;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LlmClientTest {
  @Test
  void enforcesTheBodyLimitWithoutDiscardingAValidBoundaryResponse() throws Exception {
    for (int length : new int[] {1024, 1025}) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/internal/v1/explain",
          exchange -> {
            byte[] bytes = ("{}" + " ".repeat(length - 2)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      try {
        var client =
            new LlmClient(JsonMapper.builder().build(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "a".repeat(32), 1, 2, 1024);
        if (length == 1024) assertThat(client.call("explain", Map.of()).isObject()).isTrue();
        else
          assertThatThrownBy(() -> client.call("explain", Map.of()))
              .isInstanceOfSatisfying(LlmClient.Failure.class, error -> {
                assertThat(error.status).isEqualTo(502);
                assertThat(error.code).isEqualTo("invalid_model_response");
              });
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void responseBodyMustFinishBeforeTheRequestDeadline() throws Exception {
    var release = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/v1/explain",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var output = exchange.getResponseBody()) {
            output.write(' ');
            output.flush();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            output.write("{}".getBytes(StandardCharsets.UTF_8));
          }
        });
    server.start();
    try {
      var client = new LlmClient(JsonMapper.builder().build(),
          "http://127.0.0.1:" + server.getAddress().getPort(), "a".repeat(32), 1, 1, 1024);
      assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
          assertThatThrownBy(() -> client.call("explain", Map.of()))
              .isInstanceOfSatisfying(LlmClient.Failure.class,
                  error -> assertThat(error.status).isEqualTo(503)));
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @Test
  void malformedAndEmptyDependencyErrorsRemainControlledFailures() throws Exception {
    for (String body : new String[] {"null", "", "<html>down</html>", "{\"code\":\"offline\"}"}) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/internal/v1/explain",
          exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      try {
        var client =
            new LlmClient(
                JsonMapper.builder().build(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "a".repeat(32),
                1,
                2,
                1024);
        assertThatThrownBy(() -> client.call("explain", Map.of()))
            .isInstanceOf(LlmClient.Failure.class);
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void absentServiceTokenDoesNotAttemptInference() {
    var client = new LlmClient(JsonMapper.builder().build(), "http://127.0.0.1:1", "", 1, 1, 1024);
    assertThat(client.configured()).isFalse();
    assertThatThrownBy(() -> client.call("classify", Map.of()))
        .isInstanceOf(LlmClient.Failure.class)
        .hasMessage("llm_unavailable");
  }
}
