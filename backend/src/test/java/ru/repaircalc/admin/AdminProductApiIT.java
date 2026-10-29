package ru.repaircalc.admin;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "repaircalc.admin.username=catalog-admin",
        "repaircalc.admin.password-hash=pbkdf2-sha256$600000$cmVwYWlyY2FsYy10ZXN0LXNhbHQtMjAyNg==$gjyWxpevd0JL54Z/gOniKJcBX3zkrSyCsUk8Bhd5uqY=",
        "server.servlet.session.cookie.secure=false"})
@ActiveProfiles({"test", "postgres-test"})
@Import(AdminProductApiIT.PostgresConfiguration.class)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class AdminProductApiIT {
    @Value("${local.server.port}") int port;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    @org.junit.jupiter.api.Order(1)
    void sessionCsrfCrudPreconditionsHistoryAndConcurrentUpdates() throws Exception {
        try (var browser = browser()) {
            var denied = send(browser, "GET", "/products", null, null, null);
            assertThat(denied.statusCode()).isEqualTo(401);
            assertThat(tree(denied).path("requestId").asText()).isEqualTo(denied.headers().firstValue("X-Request-ID").orElseThrow());
            var anonymous = send(browser, "GET", "/session", null, null, null);
            assertThat(anonymous.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/admin");
            var token = tree(anonymous).path("csrfToken").asText();
            var credentials = "{\"username\":\"catalog-admin\",\"password\":\"test-only-password\"}";
            assertThat(send(browser, "POST", "/login", credentials, null, null).statusCode()).isEqualTo(403);
            var login = send(browser, "POST", "/login", credentials, token, null);
            assertThat(login.statusCode()).isEqualTo(200);
            var csrf = tree(login).path("csrfToken").asText();
            assertThat(csrf).isNotEqualTo(token);
            var input = draft();
            assertThat(send(browser, "POST", "/products", input, token, null).statusCode()).isEqualTo(403);
            var created = send(browser, "POST", "/products", input, csrf, null);
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(created.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
            String id = tree(created).path("id").asText();
            String etag = created.headers().firstValue("ETag").orElseThrow();
            assertThat(created.headers().firstValue("Location").orElseThrow()).endsWith(id);
            assertThat(tree(created).path("snapshot").path("wet").path("state").asText()).isEqualTo("unknown");
            assertThat(tree(created).path("version").asInt()).isEqualTo(1);
            assertThat(send(browser, "POST", "/products", input, csrf, null).statusCode()).isEqualTo(409);
            assertThat(send(browser, "PUT", "/products/" + id, input, csrf, null).statusCode()).isEqualTo(428);
            assertThat(send(browser, "PUT", "/products/" + id, input, csrf, "\"old\"").statusCode()).isEqualTo(412);
            var updated = input.replace("Draft product", "Updated product");
            var one = CompletableFuture.supplyAsync(() -> unchecked(browser, "PUT", "/products/" + id, updated, csrf, etag));
            var two = CompletableFuture.supplyAsync(() -> unchecked(browser, "PUT", "/products/" + id, updated, csrf, etag));
            assertThat(java.util.List.of(one.get().statusCode(), two.get().statusCode())).containsExactlyInAnyOrder(200, 412);
            var history = send(browser, "GET", "/products/" + id + "/history", null, null, null);
            assertThat(tree(history).path("total").asInt()).isEqualTo(2);
            assertThat(tree(history).path("items").get(1).path("snapshot").path("name").asText()).isEqualTo("Draft product");
            assertThat(jdbc.queryForObject("select count(*) from repaircalc.admin_audit_event where product_id = ?", Integer.class, UUID.fromString(id))).isEqualTo(2);
            assertThat(send(browser, "GET", "/products?q=" + id, null, null, null).statusCode()).isEqualTo(200);
            var listed = send(browser, "GET", "/products?q=Updated&status=draft&size=1", null, null, null);
            assertThat(tree(listed).path("total").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(send(browser, "GET", "/products?size=101", null, null, null).statusCode()).isEqualTo(422);
            assertThat(send(browser, "GET", "/products/not-a-uuid", null, null, null).statusCode()).isEqualTo(400);
            assertThat(send(browser, "POST", "/logout", "{}", csrf, null).statusCode()).isEqualTo(204);
            assertThat(send(browser, "GET", "/products/" + id, null, null, null).statusCode()).isEqualTo(401);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void classificationAndWorkflowRoutesRequireSessionCsrfAndCurrentVersion() throws Exception {
        try (var browser = browser()) {
            String csrf = login(browser);
            var created = send(browser, "POST", "/products", draft(), csrf, null);
            var id = tree(created).path("id").asText();
            var etag = created.headers().firstValue("ETag").orElseThrow();
            var path = "/products/" + id;
            assertThat(send(browser,"POST",path+"/classifications",null,null,etag).statusCode()).isEqualTo(403);
            assertThat(send(browser,"POST",path+"/classifications",null,csrf,null).statusCode()).isEqualTo(428);
            assertThat(send(browser,"POST",path+"/classifications",null,csrf,"\"old\"").statusCode()).isEqualTo(412);
            var queued = send(browser,"POST",path+"/classifications",null,csrf,etag);
            assertThat(queued.statusCode()).isEqualTo(202);
            assertThat(tree(queued).path("status").asText()).isEqualTo("queued");
            assertThat(send(browser,"GET",path+"/classifications",null,null,null).statusCode()).isEqualTo(200);
            assertThat(send(browser,"GET","/review-options",null,null,null).statusCode()).isEqualTo(200);
            assertThat(send(browser,"POST",path+"/reviews","{}",csrf,etag).statusCode()).isEqualTo(422);
            var ready = send(browser,"GET",path+"/readiness",null,null,null);
            assertThat(ready.statusCode()).isEqualTo(200);
            assertThat(tree(ready).path("ready").asBoolean()).isFalse();
            assertThat(send(browser,"POST",path+"/publish",null,csrf,etag).statusCode()).isEqualTo(422);
            send(browser,"POST","/logout",null,csrf,null);
            assertThat(send(browser,"GET",path+"/classifications",null,null,null).statusCode()).isEqualTo(401);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void rejectsInvalidDraftsWithoutWritingAndRollsBackAuditFailure() throws Exception {
        try (var browser = browser()) {
            String csrf = login(browser);
            for (var invalid : new String[]{draft().replace("\"state\":\"unknown\"", "\"state\":\"unknown\",\"value\":false"),
                    draft().replace("\"wet\"", "\"invented\""),
                    draft().replace("\"state\":\"unknown\"", "\"state\":\"confirmed\""),
                    draft().replace("\"wet\":{\"state\":\"unknown\"}", "\"price\":{\"state\":\"confirmed\",\"value\":\"0\",\"source\":\"label\"}")}) {
                assertThat(send(browser, "POST", "/products", invalid, csrf, null).statusCode()).isEqualTo(422);
            }
            assertThat(send(browser, "POST", "/products", "{\"name\":\"a\",\"name\":\"b\"}", csrf, null).statusCode()).isEqualTo(400);
            assertThat(send(browser, "POST", "/products", "{\"name\":\"" + "x".repeat(262144) + "\"}", csrf, null).statusCode()).isEqualTo(413);
            var input = draft();
            var created = send(browser, "POST", "/products", input, csrf, null);
            var id = UUID.fromString(tree(created).path("id").asText());
            String etag = created.headers().firstValue("ETag").orElseThrow();
            // An audit failure must roll back the new snapshot and pointer as well.
            jdbc.execute("create function repaircalc.fail_test_audit() returns trigger language plpgsql as $$ begin raise exception 'test audit failure'; end; $$");
            jdbc.execute("create trigger fail_test_audit before insert on repaircalc.admin_audit_event for each row execute function repaircalc.fail_test_audit()");
            try {
                assertThat(send(browser, "PUT", "/products/" + id, input.replace("Draft product", "Audit rollback"), csrf, etag).statusCode()).isEqualTo(503);
            } finally {
                jdbc.execute("drop trigger fail_test_audit on repaircalc.admin_audit_event");
                jdbc.execute("drop function repaircalc.fail_test_audit()");
            }
            assertThat(jdbc.queryForObject("select count(*) from repaircalc.admin_product_version where product_id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(send(browser, "GET", "/products/" + id, null, null, null).headers().firstValue("ETag").orElseThrow()).isEqualTo(etag);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void publicRoutesStayAnonymousAndLoginHasRateLimit() throws Exception {
        try (var browser = browser()) {
            // Public catalog has no prepared release here; authentication must not intercept it.
            var publicRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/products")).GET().build();
            assertThat(browser.send(publicRequest, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
            var csrf = tree(send(browser, "GET", "/session", null, null, null)).path("csrfToken").asText();
            for (int i = 0; i < 5; i++) assertThat(send(browser, "POST", "/login", "{\"username\":\"catalog-admin\",\"password\":\"wrong\"}", csrf, null).statusCode()).isEqualTo(401);
            var blocked = send(browser, "POST", "/login", "{\"username\":\"catalog-admin\",\"password\":\"wrong\"}", csrf, null);
            assertThat(blocked.statusCode()).isEqualTo(429);
            assertThat(blocked.headers().firstValue("Retry-After").orElseThrow()).isEqualTo("60");
        }
    }

    private String draft() { return "{\"sourceNamespace\":\"api-test\",\"externalId\":\"" + UUID.randomUUID() + "\",\"name\":\"Draft product\",\"wet\":{\"state\":\"unknown\"}}"; }
    private HttpClient browser() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(java.time.Duration.ofSeconds(5)).build(); }
    private String login(HttpClient browser) throws Exception {
        var token = tree(send(browser, "GET", "/session", null, null, null)).path("csrfToken").asText();
        var result = send(browser, "POST", "/login", "{\"username\":\"catalog-admin\",\"password\":\"test-only-password\"}", token, null);
        assertThat(result.statusCode()).isEqualTo(200);
        return tree(result).path("csrfToken").asText();
    }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private HttpResponse<String> unchecked(HttpClient browser, String method, String path, String body, String csrf, String match) {
        try { return send(browser, method, path, body, csrf, match); } catch (Exception exception) { throw new RuntimeException(exception); }
    }
    private HttpResponse<String> send(HttpClient browser, String method, String path, String body, String csrf, String match) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/admin" + path)).timeout(java.time.Duration.ofSeconds(15));
        if (body != null) builder.header("Content-Type", "application/json");
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        if (match != null) builder.header("If-Match", match);
        return browser.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean @ServiceConnection PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17.11").withDatabaseName("repaircalc_admin_api")
                    .withUsername("repaircalc_test").withPassword("isolated-test-password");
        }
    }
}
