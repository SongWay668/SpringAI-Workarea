package com.ws16289.daxi.repository;

import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class RedisChatMemory implements ChatMemory {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${chat.memory.prefix:db0-customerchat:}")
    private String memoryPrefix;

    @Override
    public void add(@NonNull String conversationId, List<Message> messages) {
        String key = memoryPrefix + conversationId;
        for(Message message:messages)
            redisTemplate.opsForList().rightPush(key, formatMessage(message));
        // 设置60分钟过期时间
        redisTemplate.expire(key, 60, java.util.concurrent.TimeUnit.MINUTES);
    }

    @Override
    public List<Message> get(@NonNull String conversationId) {
        List<String> list = redisTemplate.opsForList().range(memoryPrefix + conversationId,0,-1);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();

            for(String msg:list) {
                messages.add(parseMessage(msg));
        }
        return messages;
    }

    @Override
    public void clear(@NonNull String conversationId) {
        redisTemplate.delete(memoryPrefix + conversationId);
    }

    // 简单格式化：type|content
    private String formatMessage(Message msg) {
        String content;
        try {
            // 尝试不同的获取内容的方式
            if (msg instanceof UserMessage) {
                content = ((UserMessage) msg).getText();
            } else if (msg instanceof AssistantMessage) {
                content = ((AssistantMessage) msg).getText();
            } else if (msg instanceof SystemMessage) {
                content = ((SystemMessage) msg).getText();
            } else {
                content = msg.toString();
            }
        } catch (Exception e) {
            content = "";
        }
        return msg.getMessageType().name() + "|" + content;
    }

    // 简单解析：type|content
    private Message parseMessage(String json) {
        String[] parts = json.split("\\|", 2);
        String type = parts[0];
        String content = parts.length > 1 ? parts[1] : "";

        return switch (MessageType.valueOf(type)) {
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }
}
