package ru.repaircalc.calculation.web;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import ru.repaircalc.calculation.*;
import ru.repaircalc.common.web.ServiceUnavailableException;
@RestController
@RequestMapping("/api/v1")
public class ProjectCalculationController {
    private final ProjectCalculationService service;
    public ProjectCalculationController(ProjectCalculationService service){this.service=service;}
    @PostMapping("/estimate") public ProductEstimate estimate(@Valid @RequestBody EstimateRequest request){return service.estimate(request.project(),request.selections());}
    @PostMapping("/recommendations") public ProjectCalculationService.Recommendations recommendations(@Valid @RequestBody RecommendationRequest request){
        try{return service.recommend(request.room());}
        catch(ServiceUnavailableException|org.springframework.dao.DataAccessException|com.mongodb.MongoException e){return new ProjectCalculationService.Recommendations(List.of(),List.of("Каталог временно недоступен. Повторите подбор позже."),"unavailable");}
    }
    public record EstimateRequest(@NotNull @Valid ProjectInput project,Map<String,String> selections){}
    public record RecommendationRequest(@NotNull @Valid ProjectInput.Room room){}
}
