package ru.repaircalc.admin;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class AdminJson {
    private final int maxBytes;
    private final JsonMapper json;

    public AdminJson(JsonMapper json, @org.springframework.beans.factory.annotation.Value("${ADMIN_BODY_maxBytes:262144}") int maxBytes) {
        if (maxBytes < 1024 || maxBytes > 1048576) throw new IllegalArgumentException("Invalid admin body limit");
        this.maxBytes = maxBytes;
        this.json = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    }

    public JsonNode body(HttpServletRequest request) throws IOException {
        var bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new AdminException(413, "body_too_large", "Размер запроса превышает 256 КиБ.");
        try {
            var node = json.readTree(bytes);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return node;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw new AdminException(400, "invalid_json", "Ожидается JSON-объект без повторяющихся ключей.");
        }
    }
}
