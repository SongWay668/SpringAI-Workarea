package com.ws16289.daxi.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * MCP 安全配置
 * 基于 Token 的认证机制
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
public class McpSecurityConfig {

    /**
     * MCP 安全属性配置
     */
    @Bean
    @ConfigurationProperties(prefix = "mcp.security")
    public McpSecurityProperties mcpSecurityProperties() {
        return new McpSecurityProperties();
    }

    /**
     * 安全 Web 过滤器链
     */
    @Bean
    @Order(1)
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            McpSecurityProperties properties) {

        // 如果安全未启用，则放行所有请求
        if (!properties.getEnabled()) {
            log.info("MCP 安全认证未启用");
            http.csrf(csrf -> csrf.disable())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll());
        } else {
            http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .authorizeExchange(exchanges -> exchanges
                    // 健康检查和信息端点无需认证
                    .pathMatchers("/mcp/health", "/mcp/info").permitAll()
                    // Actuator 端点无需认证
                    .pathMatchers("/actuator/**").permitAll()
                    // 其他所有 MCP 端点需要认证
                    .pathMatchers("/mcp/**").authenticated()
                );
        }

        return http.build();
    }

    /**
     * MCP Token 认证过滤器
     * 验证请求头中的 Authorization: Bearer {token}
     */
    @Bean
    @Order(2)
    public WebFilter mcpTokenAuthenticationFilter(McpSecurityProperties properties) {
        return new McpTokenAuthFilter(properties);
    }

    /**
     * MCP 安全属性类
     */
    @Data
    public static class McpSecurityProperties {
        private Boolean enabled = true;
        private String token = "mcp-secure-token-change-me-in-production";
    }

    /**
     * MCP Token 认证过滤器实现
     */
    public static class McpTokenAuthFilter implements WebFilter {

        private static final String AUTHORIZATION_HEADER = "Authorization";
        private static final String BEARER_PREFIX = "Bearer ";
        private final McpSecurityProperties properties;

        public McpTokenAuthFilter(McpSecurityProperties properties) {
            this.properties = properties;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            String path = exchange.getRequest().getPath().value();

            // 如果安全未启用，直接放行
            if (!properties.getEnabled()) {
                return chain.filter(exchange);
            }

            // 跳过无需认证的路径
            if (isPublicPath(path)) {
                return chain.filter(exchange);
            }

            // 验证 Token
            String authHeader = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);

            if (isValidToken(authHeader)) {
                log.debug("Token 验证通过: path={}", path);
                return chain.filter(exchange);
            } else {
                log.warn("Token 验证失败: path={}, authHeader={}", path,
                    authHeader != null ? "***" : "null");
                return unauthorizedResponse(exchange);
            }
        }

        /**
         * 判断是否为公开路径
         */
        private boolean isPublicPath(String path) {
            return path.equals("/mcp/health") ||
                   path.equals("/mcp/info") ||
                   path.startsWith("/actuator/");
        }

        /**
         * 验证 Token 是否有效
         */
        private boolean isValidToken(String authHeader) {
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                return false;
            }

            String token = authHeader.substring(BEARER_PREFIX.length());
            return properties.getToken().equals(token);
        }

        /**
         * 返回未授权响应
         */
        private Mono<Void> unauthorizedResponse(ServerWebExchange exchange) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");

            return exchange.getResponse().writeWith(Mono.empty());
        }
    }
}
