package com.ws16289.daxi.config.ai;

import com.ws16289.daxi.service.impl.ai.DocumentCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;

/**
 * 定时清理配置
 * 定期执行文档清理任务
 */
@Slf4j
//@Configuration
@EnableScheduling
//@Component
public class ScheduledCleanupConfig {

    @Autowired
    private DocumentCleanupService documentCleanupService;

    /**
     * 每天凌晨 2 点执行清理任务
     * 清理 90 天前的文档
     */
    @Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨 2 点
    public void dailyCleanup() {
        log.info("开始执行每日定时清理任务");

        try {
            LocalDate cutoffDate = LocalDate.now().minusDays(90);
            int[] result = documentCleanupService.cleanupByDate(cutoffDate);

            log.info("每日定时清理任务完成，标记 {} 个文档，删除 {} 个文档", result[0], result[1]);
        } catch (Exception e) {
            log.error("每日定时清理任务执行失败", e);
        }
    }

    /**
     * 每周日凌晨 3 点执行深度清理
     * 清理所有过期文档
     */
    @Scheduled(cron = "0 0 3 ? * SUN")  // 每周日凌晨 3 点
    public void weeklyCleanup() {
        log.info("开始执行每周深度清理任务");

        try {
            LocalDate cutoffDate = LocalDate.now().minusDays(180);  // 6 个月前
            int[] result = documentCleanupService.cleanupByDate(cutoffDate);

            log.info("每周深度清理任务完成，标记 {} 个文档，删除 {} 个文档", result[0], result[1]);
        } catch (Exception e) {
            log.error("每周深度清理任务执行失败", e);
        }
    }

    /**
     * 每月 1 号凌晨 1 点执行统计报告
     */
    @Scheduled(cron = "0 0 1 1 * ?")  // 每月 1 号凌晨 1 点
    public void monthlyStats() {
        log.info("开始生成月度统计报告");

        try {
            DocumentCleanupService.DocumentStats stats =
                documentCleanupService.getDocumentStats();

            log.info("月度统计: {}", stats);

            // 可以在这里添加邮件通知或其他统计上报逻辑
        } catch (Exception e) {
            log.error("月度统计任务执行失败", e);
        }
    }

    /**
     * 也可以使用固定间隔执行
     * 例如：每 6 小时执行一次过期文档清理
     */
    // @Scheduled(fixedRate = 6 * 60 * 60 * 1000)  // 每 6 小时（毫秒）
    public void periodicExpiredCleanup() {
        // log.info("执行定期过期文档清理");
    }
}
