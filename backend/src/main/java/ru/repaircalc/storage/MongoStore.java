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
  private final int transactionMaxAttempts;

  public MongoStore(
      MongoTemplate mongo,
      MongoDatabaseFactory factory,
      JsonMapper json,
      @org.springframework.beans.factory.annotation.Value("${MONGO_TRANSACTION_MAX_ATTEMPTS:3}")
          int transactionMaxAttempts) {
    if (transactionMaxAttempts < 1 || transactionMaxAttempts > 10)
      throw new IllegalArgumentException("MONGO_TRANSACTION_MAX_ATTEMPTS must be 1..10");
    this.mongo = mongo;
    this.json = json;
    this.transactionMaxAttempts = transactionMaxAttempts;
    this.transactions = new TransactionTemplate(new MongoTransactionManager(factory));
  }

  public Document one(String collection, Query query) {
    return mongo.findOne(query, Document.class, collection);
  }

  public Document id(String collection, Object id) {
    return one(collection, query("_id", id.toString()));
  }

  public List<Document> find(String collection, Query query) {
    return mongo.find(query, Document.class, collection);
  }

  public Document insert(String collection, Document value) {
    if (collection.startsWith("admin_")) normalizeDates(value);
    return mongo.insert(value, collection);
  }

  public long update(String collection, Query query, Update update) {
    if (collection.startsWith("admin_")
        && update.getUpdateObject().get("$set") instanceof Map<?, ?> fields) {
      for (String key : List.of("createdAt", "updatedAt", "finishedAt", "created_at")) {
        if (fields.get(key) instanceof String text)
          update.set(key, java.util.Date.from(java.time.Instant.parse(text)));
      }
    }
    return mongo.updateFirst(query, update, collection).getModifiedCount();
  }

  public static Query query(String key, Object value) {
    return Query.query(Criteria.where(key).is(value));
  }

  /** Retry the complete transaction only on Mongo's explicit transient-transaction label. */
  public <T> T transaction(Supplier<T> action) {
    for (int attempt = 0; ; attempt++) {
      try {
        return transactions.execute(status -> action.get());
      } catch (RuntimeException failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof com.mongodb.MongoException))
          cause = cause.getCause();
        if (attempt + 1 >= transactionMaxAttempts
            || !(cause instanceof com.mongodb.MongoException mongoFailure)
            || !mongoFailure.hasErrorLabel("TransientTransactionError")) throw failure;
      }
    }
  }

  public Object value(Object object) {
    return bson(json.valueToTree(object));
  }

  public Document document(Object object) {
    return (Document) value(object);
  }

  public JsonNode tree(Document document) {
    var content = new Document(document);
    content.remove("_id");
    content.remove("_class");
    return json.valueToTree(plain(content));
  }

  public <T> T read(Document document, Class<T> type) {
    return json.treeToValue(tree(document), type);
  }

  private Object bson(JsonNode node) {
    if (node.isNull()) return null;
    if (node.isObject()) {
      var document = new Document();
      for (String key : node.propertyNames()) document.put(key, bson(node.get(key)));
      return document;
    }
    if (node.isArray()) {
      var list = new ArrayList<>();
      for (var item : node) list.add(bson(item));
      return list;
    }
    if (node.isBoolean()) return node.asBoolean();
    if (node.isIntegralNumber())
      return node.canConvertToLong() ? node.longValue() : decimal(node.decimalValue());
    if (node.isNumber()) return decimal(node.decimalValue());
    return node.asText();
  }

  private Object plain(Object value) {
    if (value instanceof java.util.Date date) return date.toInstant().toString();
    if (value instanceof Decimal128 number) return number.bigDecimalValue();
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, item) -> result.put(key.toString(), plain(item)));
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(this::plain).toList();
    return value;
  }

  private static void normalizeDates(Document document) {
    for (String key : List.of("createdAt", "updatedAt", "finishedAt", "created_at"))
      if (document.get(key) instanceof String text)
        document.put(key, java.util.Date.from(java.time.Instant.parse(text)));
  }

  public static java.time.Instant instant(Document document, String key) {
    Object value = document.get(key);
    return value == null
        ? null
        : value instanceof java.util.Date date
            ? date.toInstant()
            : java.time.Instant.parse(value.toString());
  }

  public static Decimal128 decimal(BigDecimal value) {
    var encoded = new Decimal128(value);
    if (encoded.bigDecimalValue().compareTo(value) != 0)
      throw new IllegalArgumentException("Decimal128 precision exceeded");
    return encoded;
  }

  public static String sha(String text) {
    return sha(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
