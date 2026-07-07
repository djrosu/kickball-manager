package com.singleskickball.manager.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Explicit JavaMailSender configuration.
 *
 * This avoids relying on Spring Boot's mail auto-configuration and reads the
 * same SMTP values from application.yml / environment variables.
 */
@Configuration
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender(
            @Value("${spring.mail.host:localhost}") String host,
            @Value("${spring.mail.port:1025}") int port,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${spring.mail.properties.mail.smtp.auth:false}") String smtpAuth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") String startTls
    ) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();

        sender.setHost(host);
        sender.setPort(port);

        if (!username.isBlank()) {
            sender.setUsername(username);
        }

        if (!password.isBlank()) {
            sender.setPassword(password);
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", smtpAuth);
        props.put("mail.smtp.starttls.enable", startTls);

        return sender;
    }
}