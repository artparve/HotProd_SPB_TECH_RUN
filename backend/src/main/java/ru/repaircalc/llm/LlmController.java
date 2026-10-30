package ru.repaircalc.llm;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import ru.repaircalc.calculation.*;
import ru.repaircalc.calculation.domain.CalculationInput;
import ru.repaircalc.calculation.web.ProductCalculationController.Input;
import ru.repaircalc.common.web.ApiStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
public class LlmController {
    private final LlmClient client;private final ProductCalculationService calculator;private final JsonMapper json;
    public LlmController(LlmClient client,ProductCalculationService calculator,JsonMapper json){this.client=client;this.calculator=calculator;this.json=json;}
    @PostMapping("/materials/calculate") public JsonNode calculate(@RequestBody @Valid MaterialRequest request){return baseline(request);}
    @PostMapping("/materials/estimate") public JsonNode explain(@RequestBody @Valid MaterialRequest request){
        var result=(tools.jackson.databind.node.ObjectNode)baseline(request);
        try{
            var response=client.call("explain",Map.of("request",request,"calculation",result));
            String explanation=response.path("explanation").asText("");
            if(!response.path("explanationStatus").asText().equals("unverified_model_text") || explanation.isBlank() || response.path("model").asText().isBlank())throw new LlmClient.Failure(502,"invalid_explanation");
            result.put("explanation",explanation).put("explanationStatus","unverified_model_text").put("model",response.path("model").asText());
            result.putArray("toolCalls").add("calculate_material_cost");
        }catch(LlmClient.Failure failure){
            result.putNull("explanation").put("explanationStatus","unavailable");result.putArray("toolCalls");
            result.set("explanationError",json.valueToTree(Map.of("code",failure.code,"message","ИИ-пояснение недоступно. Показан проверенный расчёт Java.")));
        }
        return result;
    }
    private JsonNode baseline(MaterialRequest request){
        if(Boolean.TRUE.equals(request.useCorrection()))throw new ApiStatusException(422,"correction_unavailable","Подтверждённый коэффициент корректировки не настроен.");
        var i=request.input();var estimate=calculator.calculate(request.productId(),new CalculationInput(i.area(),i.perimeter(),i.height(),i.reserve(),i.layers(),i.quantity()));
        return json.valueToTree(Map.of("status","ok","estimate",estimate,"baseline",estimate,"correction",Map.of("applied",false,"version","not-used","factor","1"),"model","not-used"));
    }
    @PostMapping("/plans/extract") public JsonNode plan(@RequestBody @Valid PlanRequest request){
        try{return client.call("plans/extract",request);}
        catch(LlmClient.Failure failure){throw new ApiStatusException(failure.status>=500?503:failure.status,failure.code,"Распознавание недоступно. Параметры комнаты сохранены; используйте ручной ввод.");}
    }
    public record MaterialRequest(@NotBlank String productId,@NotNull @Valid Input input,@Size(min=1,max=8000)String prompt,Boolean useCorrection){}
    public record PlanRequest(@NotBlank @Size(max=11184812)String imageBase64){}
}
