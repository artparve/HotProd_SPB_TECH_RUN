package ru.repaircalc.llm;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LlmClientTest {
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
