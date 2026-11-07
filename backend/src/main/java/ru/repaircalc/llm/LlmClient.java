package ru.repaircalc.llm;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LlmClient {
  private final URI base;
  private final String token;
  private final Duration timeout;
  private final HttpClient client;
  private final JsonMapper json;
  private final int maxBytes;

  public LlmClient(
      JsonMapper json,
      @Value("${LLM_SERVICE_URL:http://127.0.0.1:8010}") String url,
      @Value("${INTERNAL_SERVICE_TOKEN:}") String token,
      @Value("${LLM_CONNECT_TIMEOUT_SECONDS:3}") int connect,
      @Value("${LLM_REQUEST_TIMEOUT_SECONDS:1260}") int timeout,
      @Value("${LLM_RESPONSE_MAX_BYTES:1048576}") int maxBytes) {
    this.base = URI.create(url.endsWith("/") ? url : url + "/");
    if (!java.util.Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || connect < 1
        || timeout < 1
        || timeout > 3600
        || maxBytes < 1024) throw new IllegalArgumentException("Invalid LLM transport settings");
    this.token = token;
    this.timeout = Duration.ofSeconds(timeout);
    this.maxBytes = maxBytes;
    this.json = json;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connect))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public boolean configured() {
    return token.length() >= 32;
  }

  public JsonNode call(String operation, Object body) {
    return call(operation, body, timeout);
  }

  public JsonNode call(String operation, Object body, Duration deadline) {
    if (deadline.isNegative() || deadline.isZero() || deadline.compareTo(timeout) > 0) deadline = timeout;
    if (!configured()) throw new Failure(503, "llm_unavailable");
    var request =
        HttpRequest.newBuilder(base.resolve("internal/v1/" + operation))
            .timeout(deadline)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    var bytes = new ByteArrayOutputStream();
    var pending =
        client.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofByteArrayConsumer(
                chunk ->
                    chunk.ifPresent(
                        part -> {
                          if (part.length > maxBytes - bytes.size())
                            throw new Failure(502, "invalid_model_response");
                          bytes.writeBytes(part);
                        })));
    try {
      // A streaming InputStream completes at headers, so its reads escape HttpRequest.timeout.
      // Wait for the bounded body consumer under one deadline covering headers and body.
      var response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
      JsonNode result = json.readTree(bytes.toByteArray());
      if (response.statusCode() != 200) {
        String code =
            response.statusCode() == 429
                ? "model_busy"
                : result != null && result.isObject()
                    ? result.path("code").asText("llm_unavailable")
                    : "llm_unavailable";
        throw new Failure(
            response.statusCode(), code.matches("[a-z0-9_]{1,80}") ? code : "llm_unavailable");
      }
      if (result == null || !result.isObject()) throw new Failure(502, "invalid_model_response");
      return result;
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new Failure(503, "llm_unavailable");
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new Failure(503, "llm_unavailable");
    } catch (ExecutionException e) {
      for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause())
        if (cause instanceof Failure failure) throw failure;
      throw new Failure(503, "llm_unavailable");
    } catch (tools.jackson.core.JacksonException e) {
      throw new Failure(503, "llm_unavailable");
    }
  }

  public static class Failure extends RuntimeException {
    public final int status;
    public final String code;

    public Failure(int status, String code) {
      super(code);
      this.status = status;
      this.code = code;
    }
  }
}
