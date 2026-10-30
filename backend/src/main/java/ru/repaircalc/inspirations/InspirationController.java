package ru.repaircalc.inspirations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ru.repaircalc.calculation.ProjectInput;
import ru.repaircalc.common.web.*;
import ru.repaircalc.llm.LlmClient;

@RestController
@RequestMapping("/api/v1/room-inspirations")
public class InspirationController {
  private final InspirationService service;

  public InspirationController(InspirationService service) {
    this.service = service;
  }

  @GetMapping
  public InspirationService.Feed feed(
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "${INSPIRATION_PAGE_LIMIT:24}") int limit) {
    try {
      return service.feed(cursor, limit);
    } catch (org.springframework.dao.DataAccessException | com.mongodb.MongoException e) {
      return new InspirationService.Feed(
          List.of(),
          null,
          "unavailable",
          List.of("Подборка временно недоступна. Используйте ручной расчёт."));
    }
  }

  @GetMapping("/{id}/{variant:image|thumbnail}")
  public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String variant) {
    var bytes = service.image(id, variant.equals("thumbnail"));
    var type =
        bytes.length > 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
            ? MediaType.IMAGE_JPEG
            : MediaType.IMAGE_PNG;
    return ResponseEntity.ok()
        .contentType(type)
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noCache())
        .body(bytes);
  }

  @PostMapping("/{id}/analyze")
  public InspirationService.Analysis analyze(
      @PathVariable String id, @Valid @RequestBody Request request) {
    try {
      return service.analyze(id, request.project());
    } catch (LlmClient.Failure
        | ServiceUnavailableException
        | org.springframework.dao.DataAccessException
        | com.mongodb.MongoException e) {
      return InspirationService.empty(
          id,
          request.project(),
          "unavailable",
          "Подбор недоступен. Комната сохранена; продолжите расчёт вручную.");
    } catch (ApiStatusException e) {
      if (e.status < 500) throw e;
      return InspirationService.empty(id, request.project(), "unavailable", e.getMessage());
    }
  }

  public record Request(@NotNull @Valid ProjectInput project) {}
}
