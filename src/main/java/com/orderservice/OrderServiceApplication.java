package com.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
        System.out.println("""
                ╔══════════════════════════════════════════════════╗
                ║      Order Management Service Running on :8080      ║
                ║   Circuit Breaker  ✓  Idempotency ✓  Rate Limit ✓  ║
                ╚══════════════════════════════════════════════════╝
                """);
    }
}
