package ru.repaircalc.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ProductView(
        UUID id,
        String name,
        String category,
        String kind,
        String price,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal packageSize,
        String unit,
        String saleUnit,
        List<String> works,
        @JsonInclude(JsonInclude.Include.ALWAYS) Boolean wet,
        @JsonInclude(JsonInclude.Include.ALWAYS) Boolean highLoad,
        @JsonInclude(JsonInclude.Include.ALWAYS) Boolean unheated,
        String description,
        String source,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal consumption,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal rollWidth,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal rollLength,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal rapport,
        @JsonInclude(JsonInclude.Include.NON_NULL) String materialFamily) {

    public ProductView(UUID id,String name,String category,String kind,String price,BigDecimal packageSize,String unit,String saleUnit,List<String> works,Boolean wet,Boolean highLoad,Boolean unheated,String description,String source,BigDecimal consumption,BigDecimal rollWidth,BigDecimal rollLength,BigDecimal rapport) {
        this(id,name,category,kind,price,packageSize,unit,saleUnit,works,wet,highLoad,unheated,description,source,consumption,rollWidth,rollLength,rapport,null);
    }
    public ProductView {
        works = List.copyOf(works);
    }
}
