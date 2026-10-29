package ru.repaircalc.catalog.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_revision", schema = "repaircalc")
public class ProductRevisionEntity {
    @Id
    private UUID id;
    @Column(name = "release_id", nullable = false)
    private UUID releaseId;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(name = "cod_good", columnDefinition = "text")
    private String codGood;
    @Column(nullable = false, columnDefinition = "text")
    private String name;
    @Column(nullable = false, columnDefinition = "text")
    private String category;
    @Column(nullable = false, length = 32)
    private String kind;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;
    @Column(name = "package_size", columnDefinition = "numeric")
    private BigDecimal packageSize;
    @Column(nullable = false, columnDefinition = "text")
    private String unit;
    @Column(name = "sale_unit", nullable = false, columnDefinition = "text")
    private String saleUnit;
    private Boolean wet;
    @Column(name = "high_load")
    private Boolean highLoad;
    private Boolean unheated;
    @Column(nullable = false, columnDefinition = "text")
    private String description;
    @Column(nullable = false, columnDefinition = "text")
    private String source;
    @Column(columnDefinition = "numeric")
    private BigDecimal consumption;
    @Column(name = "roll_width", columnDefinition = "numeric")
    private BigDecimal rollWidth;
    @Column(name = "roll_length", columnDefinition = "numeric")
    private BigDecimal rollLength;
    @Column(columnDefinition = "numeric")
    private BigDecimal rapport;

    protected ProductRevisionEntity() {
    }

    public UUID getId() { return id; }
    public UUID getReleaseId() { return releaseId; }
    public UUID getProductId() { return productId; }
    public String getCodGood() { return codGood; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getKind() { return kind; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getPackageSize() { return packageSize; }
    public String getUnit() { return unit; }
    public String getSaleUnit() { return saleUnit; }
    public Boolean getWet() { return wet; }
    public Boolean getHighLoad() { return highLoad; }
    public Boolean getUnheated() { return unheated; }
    public String getDescription() { return description; }
    public String getSource() { return source; }
    public BigDecimal getConsumption() { return consumption; }
    public BigDecimal getRollWidth() { return rollWidth; }
    public BigDecimal getRollLength() { return rollLength; }
    public BigDecimal getRapport() { return rapport; }
}
