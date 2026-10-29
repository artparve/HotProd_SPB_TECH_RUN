package ru.repaircalc.rules;

import java.util.UUID;

/** Detached immutable published data; never contains a JPA entity. */
public record RuleSetSnapshot(UUID id, UUID catalogReleaseId, String version, String fingerprint, RuleSetBundle bundle) { }
