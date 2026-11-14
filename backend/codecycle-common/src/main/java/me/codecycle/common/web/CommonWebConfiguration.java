package me.codecycle.common.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonWebConfiguration {
    @Bean
    CorrelationIdFilter requestIdFilter() {
        return new CorrelationIdFilter();
    }
}
