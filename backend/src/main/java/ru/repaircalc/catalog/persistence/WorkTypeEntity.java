package ru.repaircalc.catalog.persistence;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "work_type", schema = "repaircalc")
public class WorkTypeEntity {
    @EmbeddedId
    private WorkTypeId id;

    @Column(name = "meaning", nullable = false, columnDefinition = "text")
    private String meaning;

    protected WorkTypeEntity() {
    }

    public WorkTypeEntity(WorkTypeId id, String meaning) {
        this.id = Objects.requireNonNull(id, "id");
        this.meaning = Objects.requireNonNull(meaning, "meaning");
    }

    public WorkTypeId getId() {
        return id;
    }

    public String getMeaning() {
        return meaning;
    }
}
