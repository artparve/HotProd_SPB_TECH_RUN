package ru.repaircalc.materials;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

/** Operator-reviewed evidence, bound to immutable published product revisions. */
public record KnowledgeBundle(
    @NotNull UUID catalogReleaseId,
    @NotNull UUID ruleSetId,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid Source> sources,
    @NotEmpty @Size(max = 500) List<@NotNull @Valid Chunk> chunks,
    @NotEmpty @Size(max = 500) List<@NotNull @Valid Claim> claims,
    @NotEmpty @Size(max = 30) List<@NotNull @Valid Requirement> requirements,
    @NotNull @Size(max = 500) List<@NotNull @Valid Combination> combinations) {
  public record Requirement(
      @NotNull @Pattern(regexp = "floor|paint|wallpaper|electrical") String work,
      @NotBlank @Size(max = 50) String kind,
      @NotEmpty @Size(max = 8) List<@NotNull @Pattern(regexp = "humidity|load|heated|substrate|waterContact|floorHeating|covering|baseState") String> conditions) {}
  public record Combination(@NotNull UUID first, @NotNull UUID second,
      @NotNull @Pattern(regexp = "pass|fail|unknown") String status,
      @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 80) String> chunkIds) {}
  public record Source(
      @NotBlank @Size(max = 80) String id,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 200) String publisher,
      @NotBlank @Size(max = 1000) @Pattern(regexp = "https://[^\\s]+") String url,
      @NotBlank @Size(max = 100) String version,
      @NotNull java.time.LocalDate retrievedOn,
      @NotNull java.time.LocalDate validUntil,
      @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256,
      @NotNull @Pattern(regexp = "approved") String rightsStatus,
      @NotNull @Pattern(regexp = "confirmed") String reviewStatus) {}
  public record Chunk(
      @NotBlank @Size(max = 80) String id,
      @NotNull UUID productId,
      @NotBlank @Size(max = 100) String productVersionId,
      @NotBlank @Size(max = 80) String sourceId,
      @NotBlank @Size(max = 200) String section,
      @NotBlank @Size(max = 1500) String text) {}
  public record Claim(
      @NotNull UUID productId,
      @NotNull @Pattern(regexp = "work|wet|highLoad|unheated|substrate|waterContact|floorHeating|covering|baseState") String property,
      @NotBlank @Size(max = 100) String value,
      @NotNull @Pattern(regexp = "confirmed|unknown|conflict") String status,
      @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 80) String> chunkIds) {}
}
