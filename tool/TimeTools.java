package com.ws16289.daxi.tool;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class TimeTools {

    @McpTool (name="getCurrentTime", description = "获取服务器当前时间,当被询问时间，现在几点，几点了等问题时调用")
    public Map<String, Object> getCurrentTime() {
        log.debug("获取当前时间 Map<String, Object> getCurrentTime()");
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        Map<String, Object> result = new HashMap<>();
        result.put("datetime", now.format(formatter));
        result.put("timestamp", System.currentTimeMillis());
        result.put("timezone", java.time.ZoneId.systemDefault().toString());
        result.put("day_of_week", now.getDayOfWeek().toString());

        return result;
    }

    @McpTool(name="getTimeByCity", description = "获取指定城市时间时调用")
    public Map<String, Object> getTimeByName(@McpToolParam(description = "城市名") String cityName,
                                             @McpToolParam(description = "时区") String zoneId) {
        log.debug(String.format("获取%s当前时间 Map<String, Object> getCurrentTime(String cityName)", cityName));
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime now = ZonedDateTime.now(zone);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        Map<String, Object> result = new HashMap<>();
        result.put("city", cityName);
        result.put("zone", zone);
        result.put("datetime", now.format(formatter));
        result.put("timestamp", System.currentTimeMillis());
        result.put("timezone", java.time.ZoneId.systemDefault().toString());
        result.put("day_of_week", now.getDayOfWeek().toString());

        return result;
    }

    /**
     * 获取时间戳
     */
    @McpTool (name = "getTimestamp", description = "获取当前Unix时间戳")
    public long getTimestamp() {
        return System.currentTimeMillis();
    }
}
