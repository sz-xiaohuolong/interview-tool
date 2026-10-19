package cn.interview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class WebConfig extends OncePerRequestFilter {
    private final ObjectMapper json;

    public WebConfig(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        boolean allowedHost = Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(request.getServerName());
        String origin = request.getHeader("Origin");
        boolean write = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        if (!allowedHost || (write && (origin != null && !allowed(origin, request) || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))))) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(), Map.of("code", "ORIGIN_REJECTED", "message", "仅允许从本机应用页面访问此接口"));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String origin, HttpServletRequest request) {
        try {
            URI u = URI.create(origin);
            int port = u.getPort() == -1 ? 80 : u.getPort();
            return "http".equals(u.getScheme()) && Set.of("localhost", "127.0.0.1").contains(u.getHost()) && (Set.of(5173, 4173, 8080).contains(port) || port == request.getServerPort());
        } catch (Exception e) {
            return false;
        }
    }
}
