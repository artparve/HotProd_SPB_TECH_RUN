package ru.repaircalc.storage;

import static org.assertj.core.api.Assertions.*;
import static ru.repaircalc.storage.MongoStore.query;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mongodb.MongoDBContainer;
import ru.repaircalc.admin.*;
import ru.repaircalc.calculation.*;
import ru.repaircalc.calculation.domain.*;
import ru.repaircalc.catalog.*;
import ru.repaircalc.ingestion.*;
import ru.repaircalc.rules.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class MongoWorkflowIT {
  @Container
  static final MongoDBContainer mongo = new MongoDBContainer(mongoImage()).withReplicaSet();

  private static String mongoImage() {
    String tag = System.getenv("MONGO_IMAGE_TAG");
    if (tag == null || tag.isBlank()) {
      var defaults = new Properties();
      try (var reader = Files.newBufferedReader(Path.of("../env/.env.example"))) {
        defaults.load(reader);
        tag = defaults.getProperty("MONGO_IMAGE_TAG");
      } catch (java.io.IOException failure) {
        throw new IllegalStateException("Cannot read tracked Mongo image defaults", failure);
      }
    }
    if (tag == null || !tag.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}(@sha256:[0-9a-f]{64})?"))
      throw new IllegalArgumentException("Invalid MONGO_IMAGE_TAG");
    // Testcontainers parses name@digest correctly; name:tag@digest is ambiguous.
    int digest = tag.indexOf('@');
    return digest >= 0 ? "mongo" + tag.substring(digest) : "mongo:" + tag;
  }

  @DynamicPropertySource
  static void mongo(DynamicPropertyRegistry r) {
    r.add("spring.mongodb.uri", () -> mongo.getReplicaSetUrl("repaircalc_it"));
    r.add("MONGO_SERVER_SELECTION_TIMEOUT_MS", () -> 5000);
    r.add("MONGO_CONNECT_TIMEOUT_MS", () -> 5000);
    r.add("MONGO_READ_TIMEOUT_MS", () -> 10000);
  }

  @Autowired MongoStore store;
  @Autowired MongoSchema schema;
  @Autowired JsonMapper json;
  @Autowired CatalogImportService catalogs;
  @Autowired RuleSetImportService rules;
  @Autowired CatalogReadService read;
  @Autowired AdminProductService products;
  @Autowired AdminClassificationService jobs;
  @Autowired ClassificationDispatcher dispatcher;
  @Autowired AdminReviewService reviews;
  @Autowired AdminPublicationService publications;
  @Autowired CalculationSnapshotService snapshots;
  @Autowired ProjectCalculationService projects;
  @Autowired ProductCalculationService calculator;

  @BeforeEach
  void seed() {
    store.mongo.getDb().drop();
    schema.initialize();
    catalogs.importFiles(
        Path.of("../data/catalog-v1.json"), Path.of("../data/work-mappings-v1.json"));
    rules.importFile(Path.of("../data/rules-v1.json"));
  }

  @Test
  void exactDecimalsStableIdentityAndIdempotentSeed() {
    var pair = snapshots.resolve();
    assertThat(pair.catalog().products()).hasSize(12);
    assertThat(
            catalogs
                .importFiles(
                    Path.of("../data/catalog-v1.json"), Path.of("../data/work-mappings-v1.json"))
                .noOp())
        .isTrue();
    assertThat(rules.importFile(Path.of("../data/rules-v1.json")).noOp()).isTrue();
    assertThat(snapshots.resolve()).isEqualTo(pair);
    assertThat(store.find("product_revision", new Query()))
        .allMatch(d -> d.get("price") instanceof Decimal128);
    assertThat(MongoStore.decimal(new BigDecimal("12345678901234567.89")).bigDecimalValue())
        .isEqualByComparingTo("12345678901234567.89");
    assertThatThrownBy(
            () -> MongoStore.decimal(new BigDecimal("1.123456789012345678901234567890123456789")))
        .isInstanceOf(NumberFormatException.class);
    assertThat(
            ((Decimal128) store.value(json.readTree("1000000000000000000000001")))
                .bigDecimalValue())
        .isEqualByComparingTo("1000000000000000000000001");
    assertThat(
            store
                .tree(new Document("raw", new Document("_id", "external-source-id")))
                .path("raw")
                .path("_id")
                .asText())
        .isEqualTo("external-source-id");
  }

  @Test
  void seedIdentitySurvivesWindowsAndLinuxLineEndings(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    Path catalog = directory.resolve("catalog.json"),
        mappings = directory.resolve("mappings.json"),
        ruleFile = directory.resolve("rules.json");
    for (var ending : List.of("\n", "\r\n")) {
      Files.writeString(
          catalog,
          Files.readString(Path.of("../data/catalog-v1.json"))
              .replace("\r\n", "\n")
              .replace("\n", ending));
      Files.writeString(
          mappings,
          Files.readString(Path.of("../data/work-mappings-v1.json"))
              .replace("\r\n", "\n")
              .replace("\n", ending));
      Files.writeString(
          ruleFile,
          Files.readString(Path.of("../data/rules-v1.json"))
              .replace("\r\n", "\n")
              .replace("\n", ending));
      assertThat(catalogs.importFiles(catalog, mappings).noOp()).isTrue();
      assertThat(rules.importFile(ruleFile).noOp()).isTrue();
    }
  }

  @Test
  void invalidKitBasisCannotReplaceWorkingRules(@org.junit.jupiter.api.io.TempDir Path directory)
      throws Exception {
    var before = snapshots.resolve();
    var bundle = (ObjectNode) json.readTree(Files.readString(Path.of("../data/rules-v1.json")));
    bundle.put("version", "invalid-kit");
    ((ObjectNode) bundle.path("kits").get(0)).put("basis", "WALL");
    Path file = directory.resolve("invalid-rules.json");
    Files.writeString(file, bundle.toString());
    assertThatThrownBy(() -> rules.importFile(file)).isInstanceOf(RuleSetImportException.class);
    assertThat(snapshots.resolve()).isEqualTo(before);
  }

  @Test
  void bundledPhotosImportIdempotentlyAndServeTheirOriginalsAndPreviews() throws Exception {
    Path assets = Path.of("../data/inspirations");
    var service =
        new ru.repaircalc.inspirations.InspirationService(
            store,
            org.mockito.Mockito.mock(ru.repaircalc.llm.LlmClient.class),
            snapshots,
            projects,
            json,
            assets.toString(),
            8388608,
            12000000,
            480,
            24);
    var entries = json.readTree(Files.readString(assets.resolve("manifest.json")));
    assertThat(entries.size()).isEqualTo(20);
    assertThat(service.importManifest(assets.resolve("manifest.json"))).isEqualTo(entries.size());
    assertThat(service.importManifest(assets.resolve("manifest.json"))).isEqualTo(entries.size());
    assertThat(service.feed(null, 24).items()).hasSize(entries.size());
    assertThat(service.feed(null, 24).items().stream()
            .collect(java.util.stream.Collectors.groupingBy(
                ru.repaircalc.inspirations.InspirationService.Image::category,
                java.util.stream.Collectors.counting())))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("living", 5L, "bedroom", 5L, "kitchen", 5L, "bathroom", 5L));
    assertThat(service.feed(null, 24).items().stream()
            .map(item -> MongoStore.sha(service.image(item.id(), false))))
        .doesNotHaveDuplicates();
    var visited = new HashSet<String>();
    String cursor = null;
    do {
      var page = service.feed(cursor, 1);
      assertThat(page.items()).hasSize(1);
      assertThat(visited.add(page.items().getFirst().id())).isTrue();
      cursor = page.nextCursor();
    } while (cursor != null);
    assertThat(visited).hasSize(entries.size());
    for (var entry : entries) {
      String id = entry.path("id").asText();
      assertThat(service.image(id, false))
          .isEqualTo(Files.readAllBytes(assets.resolve(entry.path("file").asText())));
      var preview =
          javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(service.image(id, true)));
      assertThat(Math.max(preview.getWidth(), preview.getHeight())).isLessThanOrEqualTo(480);
      assertThat(
              service.feed(null, 24).items().stream()
                  .filter(item -> item.id().equals(id))
                  .findFirst()
                  .orElseThrow()
                  .sourceUrl())
          .isEqualTo(entry.path("sourceUrl").asText());
    }
  }

  @Test
  void imageImportIsAtomicAndFilesCannotEscapeThePreparedRoot(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    Path assets = Files.createDirectory(directory.resolve("assets"));
    var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
    javax.imageio.ImageIO.write(image, "png", assets.resolve("valid.png").toFile());
    javax.imageio.ImageIO.write(image, "png", directory.resolve("outside.png").toFile());
    Files.writeString(assets.resolve("broken.png"), "not an image");
    var service =
        new ru.repaircalc.inspirations.InspirationService(
            store,
            org.mockito.Mockito.mock(ru.repaircalc.llm.LlmClient.class),
            snapshots,
            projects,
            json,
            assets.toString(),
            8388608,
            12000000,
            480,
            24);
    Path manifest = assets.resolve("manifest.json");
    String valid =
        "{\"id\":\"prepared\",\"title\":\"Тест\",\"file\":\"valid.png\",\"sourceUrl\":\"https://www.pinterest.com/pin/000/\"}";
    Files.writeString(manifest, "[" + valid + "]");
    assertThat(service.importManifest(manifest)).isEqualTo(1);
    assertThat(service.image("prepared", true)).startsWith((byte) 0x89, (byte) 0x50);
    for (String source :
        List.of(
            "http://www.pexels.com/photo/123/",
            "https://www.pexels.com.evil.example/photo/123/",
            "https://user@www.pexels.com/photo/123/",
            "https://www.pexels.com:8443/photo/123/")) {
      Files.writeString(
          manifest, "[" + valid.replace("https://www.pinterest.com/pin/000/", source) + "]");
      assertThatThrownBy(() -> service.importManifest(manifest))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(service.feed(null, 24).items().getFirst().sourceUrl()).contains("pinterest.com");
    }
    Files.writeString(
        manifest,
        "["
            + valid
            + ","
            + valid.replace("prepared", "broken").replace("valid.png", "broken.png")
            + "]");
    assertThatThrownBy(() -> service.importManifest(manifest))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(service.feed(null, 24).items()).hasSize(1);
    Files.writeString(manifest, "[" + valid.replace("valid.png", "../outside.png") + "]");
    assertThatThrownBy(() -> service.importManifest(manifest)).isInstanceOf(RuntimeException.class);
    assertThat(service.feed(null, 24).items().getFirst().id()).isEqualTo("prepared");
    assertThatThrownBy(() -> service.image("missing", false))
        .isInstanceOf(ru.repaircalc.common.web.ApiStatusException.class);
    Files.write(assets.resolve("valid.png"), new byte[] {1, 2, 3});
    assertThatThrownBy(() -> service.image("prepared", false))
        .isInstanceOf(ru.repaircalc.common.web.ApiStatusException.class);
    Files.writeString(manifest, "[]");
    assertThat(service.importManifest(manifest)).isZero();
    assertThat(service.feed(null, 24).items()).isEmpty();
  }

  @Test
  void editsAreVersionedIdentityIsUniqueAndConcurrentWritersCannotLoseUpdates() throws Exception {
    var draft = draft();
    var p = products.create(draft, "test", "create");
    assertThatThrownBy(() -> products.create(draft, "test", "duplicate"))
        .isInstanceOf(AdminException.class);
    var gate = new CountDownLatch(1);
    var successes = new java.util.concurrent.atomic.AtomicInteger();
    Runnable edit =
        () -> {
          try {
            gate.await();
            products.update(
                p.id(),
                AdminProductService.etag(p),
                draft.deepCopy().put("name", UUID.randomUUID().toString()),
                "test",
                "edit");
            successes.incrementAndGet();
          } catch (AdminException e) {
            assertThat(e.status()).isEqualTo(412);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        };
    var a = CompletableFuture.runAsync(edit);
    var b = CompletableFuture.runAsync(edit);
    gate.countDown();
    a.get(15, TimeUnit.SECONDS);
    b.get(15, TimeUnit.SECONDS);
    assertThat(successes.get()).isEqualTo(1);
    assertThat(products.history(p.id(), 0, 100).total()).isEqualTo(2);
    assertThatThrownBy(
            () -> products.update(p.id(), AdminProductService.etag(p), draft, "test", "stale"))
        .isInstanceOf(AdminException.class);
  }

  @Test
  void leaseTokenFencesExpiredWorkersBusyDoesNotConsumeAnAttemptAndOldVersionsFail() {
    var p = products.create(draft(), "test", "create");
    var job = jobs.enqueue(p.id(), AdminProductService.etag(p), "test", "enqueue");
    assertThat(jobs.enqueue(p.id(), AdminProductService.etag(p), "test", "repeat").id())
        .isEqualTo(job.id());
    var first = dispatcher.claim();
    assertThat(dispatcher.claim()).isNull();
    store.update(
        "admin_classification_job",
        query("_id", job.id().toString()),
        new Update().set("leaseUntil", 0L));
    var second = dispatcher.claim();
    dispatcher.complete(first, model(), null);
    assertThat(jobs.list(p.id()).getFirst().status()).isEqualTo("running");
    dispatcher.complete(second, null, "model_busy");
    assertThat(jobs.list(p.id()).getFirst().attempts()).isEqualTo(1);
    store.update(
        "admin_classification_job",
        query("_id", job.id().toString()),
        new Update().set("availableAt", 0L));
    var third = dispatcher.claim();
    products.update(
        p.id(),
        AdminProductService.etag(p),
        draft().put("sourceNamespace", p.sourceNamespace()).put("externalId", p.externalId()),
        "test",
        "edit");
    dispatcher.complete(third, model(), null);
    assertThat(jobs.list(p.id()).getFirst().errorCode()).isEqualTo("stale_version");
  }

  @Test
  void exhaustedCrashBecomesTerminal() {
    var p = products.create(draft(), "test", "create");
    var job = jobs.enqueue(p.id(), AdminProductService.etag(p), "test", "enqueue");
    store.update(
        "admin_classification_job",
        query("_id", job.id().toString()),
        new Update().set("status", "running").set("attempts", 3).set("leaseUntil", 0L));
    assertThat(dispatcher.claim()).isNull();
    assertThat(jobs.list(p.id()).getFirst().errorCode()).isEqualTo("worker_lost");
    var retry = jobs.enqueue(p.id(), AdminProductService.etag(p), "test", "retry");
    assertThat(retry.id()).isEqualTo(job.id());
    assertThat(retry.attempts()).isZero();
    assertThat(dispatcher.claim()).isNotNull();
  }

  @Test
  void publicationRollbackRetainsPreviousPairAndSeedDoesNotUndoPublishedContent() {
    var before = snapshots.resolve();
    var p = reviewed();
    store.mongo.executeCommand(
        new Document("collMod", "admin_audit_event")
            .append("validator", new Document("action", new Document("$ne", "publish"))));
    try {
      assertThatThrownBy(
              () -> publications.publish(p.id(), AdminProductService.etag(p), "test", "fail"))
          .isInstanceOf(RuntimeException.class);
    } finally {
      store.mongo.executeCommand(
          new Document("collMod", "admin_audit_event").append("validator", new Document()));
    }
    assertThat(snapshots.resolve()).isEqualTo(before);
    assertThat(store.mongo.count(new Query(), "catalog_release")).isEqualTo(1);
    var publication = publications.publish(p.id(), AdminProductService.etag(p), "test", "publish");
    assertThat(publications.publish(p.id(), AdminProductService.etag(p), "test", "repeat"))
        .isEqualTo(publication);
    var after = snapshots.resolve();
    assertThat(after.catalog().products()).hasSize(13).containsAll(before.catalog().products());
    catalogs.importFiles(
        Path.of("../data/catalog-v1.json"), Path.of("../data/work-mappings-v1.json"));
    rules.importFile(Path.of("../data/rules-v1.json"));
    assertThat(snapshots.resolve()).isEqualTo(after);
    assertThat(read.snapshot(before.catalog().releaseId().toString(), null).products()).hasSize(12);
  }

  @Test
  void concurrentPublishersRetainBothProducts() throws Exception {
    var one = reviewed();
    var two = reviewed();
    var gate = new CountDownLatch(1);
    var a = CompletableFuture.supplyAsync(() -> publishAfter(gate, one));
    var b = CompletableFuture.supplyAsync(() -> publishAfter(gate, two));
    gate.countDown();
    a.get(20, TimeUnit.SECONDS);
    b.get(20, TimeUnit.SECONDS);
    assertThat(read.listProducts())
        .hasSize(14)
        .extracting(ProductView::id)
        .contains(one.id(), two.id());
  }

  @Test
  void tileKitGoldenArithmeticZeroElectricalAndPerRoomPackaging() {
    var room = room("a", List.of("floor", "paint", "electrical"));
    var project = new ProjectInput("apartment", "capital", List.of(room));
    var estimate =
        projects.estimate(project, Map.of("a:covering", "ac1f222a-f5e9-3b5d-a91e-c943b053e92e"));
    assertThat(estimate.lines())
        .extracting(l -> l.product().kind())
        .containsExactly("covering", "adhesive", "paint", "primer");
    assertThat(estimate.lines().getFirst().quantity()).isEqualTo(16); // ceil(20*1.1/1.44)
    assertThat(estimate.lines().get(1).quantity()).isEqualTo(4); // ceil(20*4*1.1/25)
    assertThat(estimate.labor()).isEqualTo("33295.00"); // 20*650 + (18*2.7-3.5)*450
    var two =
        projects.estimate(
            new ProjectInput(
                "house",
                "designer",
                List.of(room("a", List.of("floor")), room("b", List.of("floor")))),
            Map.of(
                "a:covering",
                "ac1f222a-f5e9-3b5d-a91e-c943b053e92e",
                "b:covering",
                "ac1f222a-f5e9-3b5d-a91e-c943b053e92e"));
    assertThat(
            two.lines().stream()
                .filter(l -> l.product().kind().equals("covering"))
                .mapToLong(ProductEstimate.Line::quantity)
                .sum())
        .isEqualTo(32);
    var laminate =
        projects.estimate(project, Map.of("a:covering", "47f9b29f-8972-3c84-9103-6b3281bd9de7"));
    assertThat(laminate.lines()).noneMatch(l -> l.product().kind().equals("adhesive"));
  }

  @Test
  void wallpaperRapportAndNonzeroElectricalHaveIndependentGoldenExamples() {
    var wallpaper =
        projects.estimate(
            new ProjectInput("apartment", "capital", List.of(room("a", List.of("wallpaper")))),
            Map.of());
    assertThat(wallpaper.lines())
        .extracting(l -> l.product().kind())
        .containsExactly("wallpaper", "primer");
    // ceil(18 * 1.10 / 1.06) = 19 strips; ceil(2.8 / .32) * .32 = 2.88 m;
    // floor(10.05 / 2.88) = 3 strips per roll, so ceil(19 / 3) = 7 rolls.
    assertThat(wallpaper.lines().getFirst().quantity()).isEqualTo(7);
    assertThat(wallpaper.labor()).isEqualTo("18040.00"); // (18 * 2.7 - 3.5) * 400
    var electricalRoom = (ObjectNode) json.valueToTree(room("b", List.of("electrical")));
    electricalRoom.put("sockets", 4).put("cableLength", 10);
    var electrical =
        projects.estimate(
            new ProjectInput(
                "house",
                "cosmetic",
                List.of(json.treeToValue(electricalRoom, ProjectInput.Room.class))),
            Map.of());
    assertThat(electrical.lines())
        .extracting(l -> l.product().kind())
        .containsExactly("socket", "cable");
    assertThat(electrical.labor()).isEqualTo("4600.00"); // 4 * 900 + 10 * 100
  }

  @Test
  void invalidSelectionsAndMissingRequiredProfilesDoNotProducePartialEstimate() {
    var p = new ProjectInput("apartment", "capital", List.of(room("a", List.of("floor"))));
    assertThatThrownBy(
            () -> projects.estimate(p, Map.of("unknown:paint", UUID.randomUUID().toString())))
        .isInstanceOf(ru.repaircalc.common.web.ApiStatusException.class);
    var pointer = store.id("publication_pointer", "active");
    store.mongo.remove(
        query("releaseId", pointer.getString("catalogReleaseId"))
            .addCriteria(Criteria.where("kind").is("adhesive")),
        "product_revision");
    assertThatThrownBy(
            () ->
                projects.estimate(p, Map.of("a:covering", "ac1f222a-f5e9-3b5d-a91e-c943b053e92e")))
        .isInstanceOf(CalculationException.class);
  }

  private JsonNode publishAfter(CountDownLatch gate, AdminProductService.Product p) {
    try {
      gate.await();
      return publications.publish(p.id(), AdminProductService.etag(p), "test", "parallel");
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private AdminProductService.Product reviewed() {
    var p = products.create(draft(), "test", "create");
    jobs.enqueue(p.id(), AdminProductService.etag(p), "test", "enqueue");
    var claim = dispatcher.claim();
    dispatcher.complete(claim, model(), null);
    var body =
        json.createObjectNode()
            .put("jobId", claim.getString("id"))
            .put("profileId", "paint-012-v1");
    body.set(
        "decisions",
        json.readTree(
            """
            [{"group":"work_types","label":"paint","action":"accept","reason":"Проверено"},
             {"group":"surface_locations","label":"wall","action":"accept","reason":"Проверено"}]
            """));
    reviews.review(p.id(), AdminProductService.etag(p), body, "test", "review");
    assertThat(publications.readiness(p.id()).ready()).isTrue();
    return p;
  }

  private ObjectNode draft() {
    var d =
        json.createObjectNode()
            .put("sourceNamespace", "workflow")
            .put("externalId", UUID.randomUUID().toString())
            .put("name", "Тестовая краска")
            .put("description", "Краска для стен");
    for (var entry :
        Map.of(
                "category",
                "Краски",
                "kind",
                "paint",
                "price",
                "1234.50",
                "currency",
                "RUB",
                "priceDate",
                "2026-10-30",
                "unit",
                "л",
                "saleUnit",
                "бан.",
                "consumptionUnit",
                "л")
            .entrySet())
      d.set(entry.getKey(), confirmed(json.getNodeFactory().stringNode(entry.getValue())));
    d.set("packageSize", confirmed(json.getNodeFactory().numberNode(new BigDecimal("2.5"))));
    d.set("consumption", confirmed(json.getNodeFactory().numberNode(new BigDecimal("0.12"))));
    d.set("sources", json.readTree("[{\"label\":\"Тестовая этикетка\"}]"));
    return d;
  }

  private ObjectNode confirmed(JsonNode value) {
    return json.createObjectNode()
        .put("state", "confirmed")
        .put("source", "Тестовая этикетка")
        .set("value", value);
  }

  private JsonNode model() {
    return json.readTree(
        """
{"taxonomy_version":"repair-product-taxonomy-v2","model":"test-transport","classification":{"scope":"in_scope",
"work_types":[{"label":"paint","evidence":[{"field":"description","quote":"стен"}]}],
"surface_locations":[{"label":"wall","evidence":[{"field":"description","quote":"стен"}]}],
"substrates":[],"properties":{},"needs_review":true,"review_reasons":[]}}
""");
  }

  public static ProjectInput.Room room(String id, List<String> works) {
    return new ProjectInput.Room(
        id,
        "Комната",
        "living",
        new BigDecimal("5"),
        new BigDecimal("4"),
        new BigDecimal("2.7"),
        new BigDecimal("3.5"),
        "normal",
        "normal",
        true,
        works,
        new BigDecimal("10"),
        2,
        0,
        BigDecimal.ZERO);
  }
}
