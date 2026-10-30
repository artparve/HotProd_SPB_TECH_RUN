package ru.repaircalc.storage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** All persistence uses BSON documents; Java owns writes and transaction boundaries. */
@Component
public class MongoStore {
    public final MongoTemplate mongo;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public MongoStore(MongoTemplate mongo, MongoDatabaseFactory factory, JsonMapper json) {
        this.mongo = mongo;
        this.json = json;
        this.transactions = new TransactionTemplate(new MongoTransactionManager(factory));
    }

    public Document one(String collection, Query query) { return mongo.findOne(query, Document.class, collection); }
    public Document id(String collection, Object id) { return one(collection, query("_id", id.toString())); }
    public List<Document> find(String collection, Query query) { return mongo.find(query, Document.class, collection); }
    public Document insert(String collection, Document value) { return mongo.insert(value, collection); }
    public long update(String collection, Query query, Update update) { return mongo.updateFirst(query, update, collection).getModifiedCount(); }
    public static Query query(String key, Object value) { return Query.query(Criteria.where(key).is(value)); }

    /** Retry the complete transaction only on Mongo's explicit transient-transaction label. */
    public <T> T transaction(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try { return transactions.execute(status -> action.get()); }
            catch (RuntimeException failure) {
                Throwable cause = failure;
                while (cause != null && !(cause instanceof com.mongodb.MongoException)) cause = cause.getCause();
                if (attempt >= 2 || !(cause instanceof com.mongodb.MongoException mongoFailure)
                        || !mongoFailure.hasErrorLabel("TransientTransactionError")) throw failure;
            }
        }
    }

    public Document document(Object object) { return (Document) bson(json.valueToTree(object)); }
    public JsonNode tree(Document document) { return json.valueToTree(plain(document)); }
    public <T> T read(Document document, Class<T> type) { return json.treeToValue(tree(document), type); }

    private Object bson(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isObject()) {
            var document = new Document();
            for (String key : node.propertyNames()) document.put(key, bson(node.get(key)));
            return document;
        }
        if (node.isArray()) { var list = new ArrayList<>(); for (var item : node) list.add(bson(item)); return list; }
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isNumber()) return decimal(node.decimalValue());
        return node.asText();
    }

    private Object plain(Object value) {
        if (value instanceof Decimal128 number) return number.bigDecimalValue();
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String,Object>();
            map.forEach((key, item) -> { if (!key.equals("_id") && !key.equals("_class")) result.put(key.toString(), plain(item)); });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::plain).toList();
        return value;
    }

    public static Decimal128 decimal(BigDecimal value) {
        var encoded = new Decimal128(value);
        if (encoded.bigDecimalValue().compareTo(value) != 0) throw new IllegalArgumentException("Decimal128 precision exceeded");
        return encoded;
    }
    public static String sha(String text) {
        return sha(text.getBytes(StandardCharsets.UTF_8));
    }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
