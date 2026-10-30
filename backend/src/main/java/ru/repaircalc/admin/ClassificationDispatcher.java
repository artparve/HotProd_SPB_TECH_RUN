package ru.repaircalc.admin;

import static ru.repaircalc.storage.MongoStore.query;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.repaircalc.llm.LlmClient;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.JsonNode;

@Component
public class ClassificationDispatcher {
  private final MongoStore store;
  private final LlmClient llm;
  private final int attempts;
  private final long lease, heartbeat, retry;
  private final boolean enabled;
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "classification-heartbeat");
            t.setDaemon(true);
            return t;
          });

  public ClassificationDispatcher(
      MongoStore store,
      LlmClient llm,
      @Value("${CLASSIFICATION_MAX_ATTEMPTS:3}") int attempts,
      @Value("${CLASSIFICATION_LEASE_MS:90000}") long lease,
      @Value("${CLASSIFICATION_HEARTBEAT_MS:20000}") long heartbeat,
      @Value("${CLASSIFICATION_RETRY_MS:15000}") long retry,
      @Value("${CLASSIFICATION_ENABLED:true}") boolean enabled) {
    if (attempts < 1 || attempts > 20 || heartbeat < 100 || lease < heartbeat * 2 || retry < 100)
      throw new IllegalArgumentException("Invalid classification queue settings");
    this.store = store;
    this.llm = llm;
    this.attempts = attempts;
    this.lease = lease;
    this.heartbeat = heartbeat;
    this.retry = retry;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${CLASSIFICATION_POLL_MS:2000}")
  public void run() {
    if (!enabled || !llm.configured()) return;
    try {
      var job = claim();
      if (job == null) return;
      var beat =
          timer.scheduleAtFixedRate(
              () -> {
                try {
                  renew(job);
                } catch (RuntimeException ignored) {
                  /* Lease fencing prevents stale completion. */
                }
              },
              heartbeat,
              heartbeat,
              TimeUnit.MILLISECONDS);
      try {
        var input = store.tree(job.get("input", Document.class));
        if (!MongoStore.sha(input.toString()).equals(job.getString("inputHash")))
          throw new LlmClient.Failure(502, "input_hash_mismatch");
        var result = llm.call("classify", input);
        if (!AdminReviewService.TAXONOMY.equals(result.path("taxonomy_version").asText())
            || !result.path("classification").isObject())
          throw new LlmClient.Failure(502, "invalid_classification");
        complete(job, result, null);
      } catch (LlmClient.Failure failure) {
        complete(job, null, failure.code);
      } finally {
        beat.cancel(false);
      }
    } catch (org.springframework.dao.DataAccessException | com.mongodb.MongoException failure) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Classification storage unavailable");
    }
  }

  public Document claim() {
    long now = System.currentTimeMillis();
    store.mongo.updateMulti(
        Query.query(
            Criteria.where("status")
                .is("running")
                .and("leaseUntil")
                .lt(now)
                .and("attempts")
                .gte(attempts)),
        new Update()
            .set("status", "failed")
            .set("errorCode", "worker_lost")
            .set("finishedAt", java.util.Date.from(Instant.now()))
            .unset("leaseToken"),
        "admin_classification_job");
    var eligible =
        new Criteria()
            .andOperator(
                Criteria.where("attempts").lt(attempts),
                new Criteria()
                    .orOperator(
                        Criteria.where("status").is("queued").and("availableAt").lte(now),
                        Criteria.where("status").is("running").and("leaseUntil").lt(now)));
    return store.mongo.findAndModify(
        Query.query(eligible).with(Sort.by("createdAt", "id")),
        new Update()
            .set("status", "running")
            .inc("attempts", 1)
            .set("leaseToken", UUID.randomUUID().toString())
            .set("leaseUntil", now + lease)
            .set("errorCode", null),
        FindAndModifyOptions.options().returnNew(true),
        Document.class,
        "admin_classification_job");
  }

  private Query fence(Document job) {
    return query("_id", job.getString("_id"))
        .addCriteria(Criteria.where("status").is("running"))
        .addCriteria(Criteria.where("leaseToken").is(job.getString("leaseToken")))
        .addCriteria(Criteria.where("leaseUntil").gt(System.currentTimeMillis()));
  }

  public void renew(Document job) {
    store.update(
        "admin_classification_job",
        fence(job),
        new Update().set("leaseUntil", System.currentTimeMillis() + lease));
  }

  public void complete(Document job, JsonNode result, String error) {
    var product = store.id("admin_product", job.getString("productId"));
    boolean stale =
        product == null || !job.getString("versionId").equals(product.getString("versionId"));
    boolean busy = "model_busy".equals(error),
        terminal =
            stale
                || result != null
                || (!busy && ((Number) job.get("attempts")).intValue() >= attempts);
    var update =
        new Update()
            .set(
                "status",
                stale ? "failed" : result != null ? "succeeded" : terminal ? "failed" : "queued")
            .set("result", stale || result == null ? null : store.document(result))
            .set("errorCode", stale ? "stale_version" : error)
            .set("finishedAt", terminal ? Instant.now().toString() : null)
            .set(
                "availableAt",
                System.currentTimeMillis() + retry * ((Number) job.get("attempts")).intValue())
            .unset("leaseToken")
            .unset("leaseUntil");
    if (busy && !stale) update.inc("attempts", -1);
    store.update("admin_classification_job", fence(job), update);
  }

  @PreDestroy
  public void stop() {
    timer.shutdownNow();
  }
}
