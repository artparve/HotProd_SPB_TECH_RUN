package ru.repaircalc.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ru.repaircalc.materials.*;

/** Uses the existing admin session and CSRF filter; publishing is an explicit human action. */
@RestController
@RequestMapping("/api/v1/admin/knowledge-releases")
public class AdminKnowledgeController {
  private final KnowledgeService knowledge;
  private final AdminAuth auth;
  public AdminKnowledgeController(KnowledgeService knowledge, AdminAuth auth) {
    this.knowledge = knowledge;
    this.auth = auth;
  }
  @PostMapping
  public KnowledgeService.Release publish(@Valid @RequestBody KnowledgeBundle bundle, HttpServletRequest request) {
    return knowledge.publish(bundle, auth.requireActor(request));
  }
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  @PostMapping("/{id}/revoke")
  public void revoke(@PathVariable String id, HttpServletRequest request) {
    knowledge.revoke(id, auth.requireActor(request));
  }
  @PostMapping("/{id}/activate")
  public KnowledgeService.Release activate(@PathVariable String id) { return knowledge.activate(id); }
}
