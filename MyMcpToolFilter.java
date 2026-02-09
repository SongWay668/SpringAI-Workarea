package com.ws16289.daxi.config.ai;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;

//@Configuration
public class MyMcpToolFilter implements McpToolFilter {
    @Override
    public boolean test(McpConnectionInfo mcpConnectionInfo, McpSchema.Tool tool) {
        String serverName = mcpConnectionInfo.initializeResult().serverInfo().name();
        String serverVersion = mcpConnectionInfo.initializeResult().serverInfo().version();
        String toolName = tool.name();

        // ========== 多环境隔离演示 ==========

        // 场景1: 生产环境 - 只允许稳定版本的工具，排除实验性工具
        if (isProductionEnvironment(serverName, serverVersion)) {
            // 排除所有包含 "experimental" 的工具
            if (tool.description() != null && tool.description().toLowerCase().contains("experimental")) {
                return false;
            }

            // 生产环境只允许时间相关工具
            return toolName.startsWith("get");
        }

        // 场景2: 开发环境 - 允许所有工具
        if (isDevelopmentEnvironment(serverName)) {
            return true;
        }

        // 场景3: 测试环境 - 只允许特定测试工具
        if (isTestEnvironment(serverName)) {
            return toolName.startsWith("test_");
        }

        // 场景4: 根据服务器名称过滤 - 只接受特定服务器的工具
        if (serverName.equals("streamable-mcp-server")) {
            return true;
        }

        // 默认情况：拒绝其他工具
        return false;
    }

    /**
     * 判断是否为生产环境
     */
    private boolean isProductionEnvironment(String serverName, String serverVersion) {
        // 生产环境：服务器名称包含 "prod" 或版本号以 "1." 开头（稳定版本）
        return serverName.toLowerCase().contains("prod") ||
               (serverVersion.startsWith("1.") && !serverVersion.contains("beta"));
    }

    /**
     * 判断是否为开发环境
     */
    private boolean isDevelopmentEnvironment(String serverName) {
        // 开发环境：服务器名称包含 "dev" 或 "develop"
        return serverName.toLowerCase().contains("dev");
    }

    /**
     * 判断是否为测试环境
     */
    private boolean isTestEnvironment(String serverName) {
        // 测试环境：服务器名称包含 "test"
        return serverName.toLowerCase().contains("test");
    }
}
