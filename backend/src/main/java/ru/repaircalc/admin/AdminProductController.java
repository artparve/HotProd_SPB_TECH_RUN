package ru.repaircalc.admin;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/products")
public class AdminProductController {
    private final AdminProductService products;
    private final AdminAuth auth;
    private final AdminJson json;
    public AdminProductController(AdminProductService products, AdminAuth auth, AdminJson json) { this.products = products; this.auth = auth; this.json = json; }

    @GetMapping
    public AdminProductService.Page<AdminProductService.Product> list(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String category, @RequestParam(defaultValue = "-updated") String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return products.list(q, status, category, sort, page, size);
    }
    @GetMapping("/{id}")
    public ResponseEntity<AdminProductService.Product> get(@PathVariable UUID id) { return response(products.get(id)); }
    @PostMapping(consumes = "application/json")
    public ResponseEntity<AdminProductService.Product> create(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var product = products.create(json.body(request), auth.requireActor(request), response.getHeader("X-Request-ID"));
        return ResponseEntity.created(URI.create("/api/v1/admin/products/" + product.id())).eTag(AdminProductService.etag(product)).body(product);
    }
    @PutMapping(value = "/{id}", consumes = "application/json")
    public ResponseEntity<AdminProductService.Product> update(@PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String match, HttpServletRequest request, HttpServletResponse response) throws IOException {
        AdminProductService.requireMatch(match);
        return response(products.update(id, match, json.body(request), auth.requireActor(request), response.getHeader("X-Request-ID")));
    }
    @GetMapping("/{id}/history")
    public AdminProductService.Page<AdminProductService.Version> history(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return products.history(id, page, size); }
    private static ResponseEntity<AdminProductService.Product> response(AdminProductService.Product product) {
        return ResponseEntity.ok().eTag(AdminProductService.etag(product)).body(product);
    }
}
