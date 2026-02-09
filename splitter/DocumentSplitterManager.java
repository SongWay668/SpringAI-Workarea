package com.ws16289.daxi.util.splitter;

import com.ws16289.daxi.util.splitter.facotry.SplitterType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分割器管理器
 * 动态管理和选择不同的文档分割器
 */
@Slf4j
@Component
public class DocumentSplitterManager {

    @Autowired
    private List<IDocumentSplitter> documentSplitters;

    private final Map<String, IDocumentSplitter> splitterCache = new HashMap<>();

    /**
     * 根据枚举类型获取分割器
     * 支持的名称: COMMON, CONTRACT, LONG, SHORT, PAPER, SPRING
     *
     * @param splitterType 分割器类型枚举
     * @return 分割器实例
     */
    public IDocumentSplitter getSplitter(SplitterType splitterType) {
        String splitterName = splitterType.name();

        // 优先从缓存获取
        if (splitterCache.containsKey(splitterName)) {
            return splitterCache.get(splitterName);
        }

        // 根据类名匹配
        for (IDocumentSplitter splitter : documentSplitters) {
            String className = splitter.getClass().getSimpleName();
            // 支持两种命名格式: CommonDocumentSplitter, Common
            if (className.equalsIgnoreCase(splitterName + "DocumentSplitter") ||
                className.equalsIgnoreCase(splitterName)) {
                splitterCache.put(splitterName, splitter);
                log.info("找到分割器: {} -> {}", splitterName, className);
                return splitter;
            }
        }

        throw new IllegalArgumentException("不支持的分割器类型: " + splitterName);
    }

    // 旧代码：字符串版本（已注释）
    // public IDocumentSplitter getSplitter(String splitterName) {
    //     // 优先从缓存获取
    //     if (splitterCache.containsKey(splitterName)) {
    //         return splitterCache.get(splitterName);
    //     }
    //
    //     // 根据类名匹配
    //     for (IDocumentSplitter splitter : documentSplitters) {
    //         String className = splitter.getClass().getSimpleName();
    //         // 支持多种命名格式: CommonDocumentSplitter, common, COMMON
    //         if (className.equalsIgnoreCase(splitterName + "DocumentSplitter") ||
    //             className.equalsIgnoreCase(splitterName)) {
    //             splitterCache.put(splitterName, splitter);
    //             log.info("找到分割器: {} -> {}", splitterName, className);
    //             return splitter;
    //         }
    //     }
    //
    //     throw new IllegalArgumentException("不支持的分割器类型: " + splitterName);
    // }

    /**
     * 获取所有可用的分割器名称列表
     *
     * @return 分割器名称列表
     */
    public List<String> getAvailableSplitters() {
        return documentSplitters.stream()
                .map(splitter -> splitter.getClass().getSimpleName()
                        .replace("DocumentSplitter", ""))
                .toList();
    }
}
