package me.codecycle.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "me.codecycle")
@EnableFeignClients
public class McpApplication {
    public static void main(String[] args) { SpringApplication.run(McpApplication.class, args); }
}
