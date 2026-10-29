package ru.repaircalc.admin;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/products/{id}/classifications")
public class AdminClassificationController {
    private final AdminClassificationService jobs;
    private final AdminAuth auth;
    public AdminClassificationController(AdminClassificationService jobs, AdminAuth auth) { this.jobs = jobs; this.auth = auth; }
    @GetMapping public List<AdminClassificationService.Job> list(@PathVariable UUID id) { return jobs.list(id); }
    @PostMapping public ResponseEntity<AdminClassificationService.Job> enqueue(@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String match, HttpServletRequest request, HttpServletResponse response) {
        return ResponseEntity.accepted().body(jobs.enqueue(id, match, auth.requireActor(request), response.getHeader("X-Request-ID")));
    }
}
