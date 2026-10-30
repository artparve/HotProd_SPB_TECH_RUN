package ru.repaircalc.admin;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuthController {
    private final AdminAuth auth;
    private final AdminJson json;
    public AdminAuthController(AdminAuth auth, AdminJson json) { this.auth = auth; this.json = json; }
    @GetMapping("/session") public AdminAuth.Session session(HttpServletRequest request) { return auth.session(request); }
    @PostMapping(value = "/login", consumes = "application/json") public AdminAuth.Session login(HttpServletRequest request) throws IOException { return auth.login(request, json.body(request)); }
    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request) { auth.logout(request); return ResponseEntity.noContent().build(); }
}
