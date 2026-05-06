package com.mod98.alpaca.spx.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Thread pools منفصلة:
 *  - ibkrEventExecutor: لمعالجة OrderStatus/Execution events بدون حجز thread الـ ibkr-reader.
 *  - telegramExecutor: لمعالجة رسائل تيليجرام (OCR + OpenAI) بعيداً عن TDLib callback thread.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "ibkrEventExecutor")
    public Executor ibkrEventExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("ibkr-evt-");
        ex.setRejectedExecutionHandler((r, executor) ->
                log.error("IBKR event executor REJECTED a task — queue full!"));
        ex.initialize();
        return ex;
    }

    @Bean(name = "telegramExecutor")
    public Executor telegramExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("tg-msg-");
        ex.initialize();
        return ex;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Async error in {}.{}: ", method.getDeclaringClass().getSimpleName(),
                        method.getName(), ex);
    }
}
