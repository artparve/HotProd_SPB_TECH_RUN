package ru.repaircalc.admin;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminWorkflowController {
    private final AdminReviewService reviews;
    private final AdminPublicationService publications;
    private final AdminAuth auth;
    private final AdminJson json;
    public AdminWorkflowController(AdminReviewService reviews,AdminPublicationService publications,AdminAuth auth,AdminJson json){this.reviews=reviews;this.publications=publications;this.auth=auth;this.json=json;}
    @GetMapping("/review-options") public JsonNode options(){return reviews.options();}
    @GetMapping("/products/{id}/reviews") public List<JsonNode> reviews(@PathVariable UUID id){return reviews.list(id);}
    @PostMapping("/products/{id}/reviews") public JsonNode review(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false)String match,HttpServletRequest req,HttpServletResponse res)throws IOException{
        return reviews.review(id,match,json.body(req),auth.requireActor(req),res.getHeader("X-Request-ID"));
    }
    @GetMapping("/products/{id}/readiness") public AdminPublicationService.Readiness readiness(@PathVariable UUID id){return publications.readiness(id);}
    @PostMapping("/products/{id}/publish") public JsonNode publish(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false)String match,HttpServletRequest req,HttpServletResponse res){return publications.publish(id,match,auth.requireActor(req),res.getHeader("X-Request-ID"));}
}
