package ru.repaircalc.admin;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class AdminWebConfiguration {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> adminAccessFilter(AdminAuth auth, JsonMapper json) {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                try {
                    if (!auth.enabled()) throw new AdminException(503, "admin_disabled", "Административный доступ не настроен.");
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    boolean authEndpoint = (path.equals("/api/v1/admin/session") && request.getMethod().equals("GET"))
                            || (path.equals("/api/v1/admin/login") && request.getMethod().equals("POST"));
                    if (!authEndpoint && auth.actor(request) == null) throw new AdminException(401, "authentication_required", "Войдите в административную панель.");
                    if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD") && !request.getMethod().equals("OPTIONS")) auth.csrf(request);
                } catch (AdminException exception) {
                    response.setStatus(exception.status());
                    response.setContentType("application/json;charset=UTF-8");
                    if (exception.status() == 429) response.setHeader("Retry-After", "60");
                    response.getWriter().write(json.writeValueAsString(AdminErrorHandler.error(exception, response)));
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.addUrlPatterns("/api/v1/admin", "/api/v1/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
