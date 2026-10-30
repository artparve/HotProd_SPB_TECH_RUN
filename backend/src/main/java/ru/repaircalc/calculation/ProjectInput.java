package ru.repaircalc.calculation;

import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import ru.repaircalc.calculation.domain.CalculationException;

public record ProjectInput(
        @NotNull @Pattern(regexp="apartment|house|studio") String object,
        @NotNull @Pattern(regexp="cosmetic|capital|designer") String renovation,
        @NotNull @Size(min=1,max=20) List<@NotNull @Valid Room> rooms) {
    public void validate() {
        var ids=new HashSet<String>();
        for(var room:rooms){room.validate();if(!ids.add(room.id()))throw CalculationException.needsClarification("Duplicate room IDs.","rooms");}
    }
    public record Room(
            @NotBlank @Size(max=100) @Pattern(regexp="[^:]+") String id,
            @NotBlank @Size(max=60) String name,
            @NotNull @Pattern(regexp="living|bathroom|kitchen|children|balcony") String type,
            @NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("100") BigDecimal length,
            @NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("100") BigDecimal width,
            @NotNull @DecimalMin("1") @DecimalMax("10") BigDecimal height,
            @NotNull @DecimalMin("0") @DecimalMax("2000") BigDecimal openings,
            @NotNull @Pattern(regexp="normal|high") String humidity,
            @NotNull @Pattern(regexp="normal|high") String load,
            @NotNull Boolean heated,
            @NotEmpty List<@NotNull @Pattern(regexp="floor|paint|wallpaper|electrical") String> works,
            @NotNull @DecimalMin("0") @DecimalMax("30") BigDecimal reserve,
            @NotNull @Min(1) @Max(4) Integer layers,
            @NotNull @Min(0) @Max(200) Integer sockets,
            @NotNull @DecimalMin("0") @DecimalMax("5000") BigDecimal cableLength) {
        public BigDecimal floor(){return length.multiply(width);}
        public BigDecimal perimeter(){return length.add(width).multiply(BigDecimal.valueOf(2));}
        public BigDecimal walls(){return perimeter().multiply(height).subtract(openings);}
        public void validate(){
            if(walls().signum()<=0 || new HashSet<>(works).size()!=works.size() || (works.contains("paint")&&works.contains("wallpaper")))
                throw CalculationException.needsClarification("Check wall openings and selected works.","room");
        }
    }
}
