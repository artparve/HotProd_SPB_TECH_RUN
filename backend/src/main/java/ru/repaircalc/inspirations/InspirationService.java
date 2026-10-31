package ru.repaircalc.inspirations;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
import ru.repaircalc.calculation.*;
import ru.repaircalc.common.web.ApiStatusException;
import ru.repaircalc.llm.LlmClient;
import ru.repaircalc.storage.MongoStore;
import tools.jackson.databind.json.JsonMapper;

@Service
public class InspirationService {
  private final MongoStore store;
  private final LlmClient llm;
  private final CalculationSnapshotService snapshots;
  private final ProjectCalculationService calculations;
  private final JsonMapper json;
  private final Path root;
  private final int maxBytes, maxPixels, thumbnailSize, pageLimit;

  public InspirationService(
      MongoStore store,
      LlmClient llm,
      CalculationSnapshotService snapshots,
      ProjectCalculationService calculations,
      JsonMapper json,
      @Value("${INSPIRATIONS_DIR:../data/inspirations}") String root,
      @Value("${IMAGE_MAX_BYTES:8388608}") int maxBytes,
      @Value("${IMAGE_MAX_PIXELS:12000000}") int maxPixels,
      @Value("${INSPIRATION_THUMBNAIL_SIZE:480}") int thumbnailSize,
      @Value("${INSPIRATION_PAGE_LIMIT:24}") int pageLimit) {
    if (maxBytes < 1
        || maxBytes > 32 * 1024 * 1024
        || maxPixels < 1
        || thumbnailSize < 32
        || thumbnailSize > 2048
        || pageLimit < 1
        || pageLimit > 24) throw new IllegalArgumentException("Invalid inspiration limits");
    this.store = store;
    this.llm = llm;
    this.snapshots = snapshots;
    this.calculations = calculations;
    this.json = json;
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.maxBytes = maxBytes;
    this.maxPixels = maxPixels;
    this.thumbnailSize = thumbnailSize;
    this.pageLimit = pageLimit;
  }

  public int importManifest(Path manifest) throws IOException {
    if (Files.size(manifest) > maxBytes) throw new IllegalArgumentException("Manifest too large");
    var entries = json.readTree(Files.readString(manifest));
    if (!entries.isArray() || entries.size() > 10000)
      throw new IllegalArgumentException("Expected image manifest array");
    var docs = new ArrayList<Document>();
    var ids = new HashSet<String>();
    for (var entry : entries) {
      String id = entry.path("id").asText(),
          file = entry.path("file").asText(),
          title = entry.path("title").asText(),
          source = entry.path("sourceUrl").asText();
      String alt = entry.path("alt").asText(title);
      String category = entry.path("category").asText("other");
      if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
          || !ids.add(id)
          || !Set.of("living", "bedroom", "kitchen", "bathroom", "other").contains(category)
          || title.isBlank()
          || title.length() > 120
          || alt.isBlank()
          || alt.length() > 300
          || source.length() > 2048
          || entry.path("description").asText().length() > 1000)
        throw new IllegalArgumentException("Invalid image metadata");
      URI uri = URI.create(source);
      if (!"https".equals(uri.getScheme())
          || uri.getUserInfo() != null
          || uri.getPort() != -1
          || !Set.of("pinterest.com", "www.pinterest.com", "pin.it", "pexels.com", "www.pexels.com")
              .contains(uri.getHost()))
        throw new IllegalArgumentException("Expected HTTPS Pinterest or Pexels source URL");
      byte[] image = read(file);
      decode(image); // Validate the entire batch before replacing metadata.
      docs.add(
          new Document("_id", id)
              .append("id", id)
              .append("title", title)
              .append("category", category)
              .append("description", entry.path("description").asText(""))
              .append("alt", entry.path("alt").asText(title))
              .append("file", file)
              .append("sourceUrl", source)
              .append("sha256", MongoStore.sha(image)));
    }
    return store.transaction(
        () -> {
          store.mongo.remove(new Query(), "inspiration");
          for (var d : docs) store.insert("inspiration", d);
          return docs.size();
        });
  }

  public Feed feed(String cursor, int limit) {
    if (limit < 1
        || limit > pageLimit
        || cursor != null && !cursor.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
      throw new ApiStatusException(422, "invalid_page", "Неверный размер страницы или cursor.");
    var q = new Query().with(Sort.by("id")).limit(limit + 1);
    if (cursor != null) q.addCriteria(Criteria.where("id").gt(cursor));
    var records = store.find("inspiration", q);
    boolean more = records.size() > limit;
    var items = records.stream().limit(limit).map(this::view).toList();
    return new Feed(items, more ? items.getLast().id() : null, "ok", List.of());
  }

  private Image view(Document d) {
    String id = d.getString("id");
    return new Image(
        id,
        d.getString("title"),
        d.getString("description"),
        "/api/v1/room-inspirations/" + id + "/thumbnail",
        "/api/v1/room-inspirations/" + id + "/image",
        d.getString("alt"),
        d.getString("sourceUrl"),
        d.getString("category") == null ? "other" : d.getString("category"));
  }

  private Document find(String id) {
    var d = store.id("inspiration", id);
    if (d == null) throw new ApiStatusException(404, "image_not_found", "Изображение не найдено.");
    return d;
  }

  public byte[] image(String id, boolean thumbnail) {
    var d = find(id);
    byte[] bytes = read(d.getString("file"));
    if (!MongoStore.sha(bytes).equals(d.getString("sha256")))
      throw new ApiStatusException(
          503, "image_changed", "Файл изменился. Повторите импорт подборки.");
    final BufferedImage decoded;
    try {
      decoded = decode(bytes);
    } catch (IllegalArgumentException e) {
      throw unavailable();
    }
    if (!thumbnail) return bytes;
    double scale =
        Math.min(1.0, (double) thumbnailSize / Math.max(decoded.getWidth(), decoded.getHeight()));
    var preview =
        new BufferedImage(
            Math.max(1, (int) (decoded.getWidth() * scale)),
            Math.max(1, (int) (decoded.getHeight() * scale)),
            BufferedImage.TYPE_INT_RGB);
    var graphics = preview.createGraphics();
    try {
      graphics.setColor(java.awt.Color.WHITE);
      graphics.fillRect(0, 0, preview.getWidth(), preview.getHeight());
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(decoded, 0, 0, preview.getWidth(), preview.getHeight(), null);
    } finally {
      graphics.dispose();
    }
    try (var output = new ByteArrayOutputStream()) {
      ImageIO.write(preview, "png", output);
      return output.toByteArray();
    } catch (IOException e) {
      throw unavailable();
    }
  }

  private byte[] read(String file) {
    try {
      Path target = root.resolve(file).normalize().toRealPath(), base = root.toRealPath();
      if (!target.startsWith(base) || !Files.isRegularFile(target) || Files.size(target) > maxBytes)
        throw new IllegalArgumentException("Invalid image path or size");
      byte[] data = Files.readAllBytes(target);
      if (data.length == 0 || data.length > maxBytes)
        throw new IllegalArgumentException("Invalid image size");
      return data;
    } catch (IOException e) {
      throw unavailable();
    }
  }

  private BufferedImage decode(byte[] bytes) {
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IllegalArgumentException("Use PNG or JPEG images");
      var reader = readers.next();
      try {
        reader.setInput(input);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!Set.of("png", "jpeg", "jpg").contains(format)
            || (long) reader.getWidth(0) * reader.getHeight(0) > maxPixels)
          throw new IllegalArgumentException("Unsupported image or pixel limit exceeded");
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Corrupt image", e);
    }
  }

  public Analysis analyze(String id, ProjectInput project) {
    project.validate();
    if (project.rooms().size() != 1)
      throw new ApiStatusException(
          422, "invalid_project", "Для изображения требуется одна комната.");
    byte[] bytes = image(id, false);
    var result =
        llm.call(
            "inspirations/analyze",
            Map.of("imageBase64", Base64.getEncoder().encodeToString(bytes)));
    String family = result.path("floorFamily").asText(), wall = result.path("wallFinish").asText();
    if (!Set.of("tile", "laminate", "vinyl", "unknown").contains(family)
        || !Set.of("paint", "wallpaper", "unknown").contains(wall)
        || !result.path("warnings").isArray()
        || result.path("warnings").size() > 10)
      throw new LlmClient.Failure(502, "invalid_model_response");
    var warnings = new ArrayList<String>();
    for (var warning : result.path("warnings")) {
      if (!warning.isString() || warning.asText().length() > 500)
        throw new LlmClient.Failure(502, "invalid_model_response");
      warnings.add(warning.asText());
    }
    var original = project.rooms().getFirst();
    var works = new LinkedHashSet<>(original.works());
    if (!family.equals("unknown")) works.add("floor");
    if (!wall.equals("unknown")) {
      works.remove("paint");
      works.remove("wallpaper");
      works.add(wall);
    }
    var room =
        new ProjectInput.Room(
            original.id(),
            original.name(),
            original.type(),
            original.length(),
            original.width(),
            original.height(),
            original.openings(),
            original.humidity(),
            original.load(),
            original.heated(),
            List.copyOf(works),
            original.reserve(),
            original.layers(),
            original.sockets(),
            original.cableLength());
    var candidateProject = new ProjectInput(project.object(), project.renovation(), List.of(room));
    var snapshot = snapshots.resolve();
    var candidates = calculations.candidates(room, snapshot);
    var selections = new LinkedHashMap<String, String>();
    if (!family.equals("unknown")) {
      var match =
          candidates.stream()
              .filter(p -> p.kind().equals("covering") && family.equals(p.materialFamily()))
              .findFirst();
      if (match.isEmpty())
        return empty(id, project, "no_matches", "Нет совместимого покрытия распознанного типа.");
      selections.put(room.id() + ":covering", match.get().id().toString());
    }
    if (!wall.equals("unknown")) {
      var match = candidates.stream().filter(p -> p.kind().equals(wall)).findFirst();
      if (match.isEmpty())
        return empty(
            id, project, "no_matches", "Нет совместимого материала для распознанной отделки стен.");
      selections.put(room.id() + ":" + wall, match.get().id().toString());
    }
    if (selections.isEmpty())
      return empty(
          id, project, "no_matches", "Не удалось уверенно определить поддерживаемые материалы.");
    // Verify the complete kit against the same immutable pair before offering it to the UI.
    try {
      calculations.estimate(candidateProject, selections, snapshot);
    } catch (ru.repaircalc.calculation.domain.CalculationException e) {
      return empty(
          id,
          project,
          "no_matches",
          "В каталоге нет подтверждённого полного комплекта для распознанной отделки.");
    }
    warnings.add(
        "Подобран тип материала. Точное совпадение цвета, фактуры и товара по фотографии не"
            + " подтверждено.");
    warnings.add(ProjectCalculationService.EDUCATIONAL);
    return new Analysis("ok", id, candidateProject, selections, List.copyOf(warnings));
  }

  public static Analysis empty(String id, ProjectInput project, String status, String reason) {
    return new Analysis(status, id, project, Map.of(), List.of(reason));
  }

  private static ApiStatusException unavailable() {
    return new ApiStatusException(503, "image_unavailable", "Изображение временно недоступно.");
  }

  public record Image(
      String id,
      String title,
      String description,
      String thumbnailUrl,
      String imageUrl,
      String alt,
      String sourceUrl,
      String category) {}

  public record Feed(List<Image> items, String nextCursor, String status, List<String> warnings) {}

  public record Analysis(
      String status,
      String imageId,
      ProjectInput project,
      Map<String, String> selections,
      List<String> warnings) {}
}
