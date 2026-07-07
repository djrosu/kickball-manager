package com.singleskickball.manager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around Spring's JavaMailSender. Keeping email logic isolated makes
 * it easier to swap SMTP for SES, SendGrid, etc. when the app moves to AWS.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailService(JavaMailSender mailSender,
                        @Value("${app.mail.from:no-reply@localhost}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void sendPasswordResetEmail(String toAddress, String resetUrl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toAddress);
        message.setSubject("Reset your Kickball Manager password");
        message.setText("A password reset was requested for your Kickball Manager account.\n\n"
            + "Use this link to choose a new password:\n"
            + resetUrl
            + "\n\nThis link expires in 60 minutes. If you did not request this, you can ignore this email.");

        try {
            mailSender.send(message);
        } catch (MailException ex) {
            // For local development, logging the URL keeps you moving even before SMTP is configured.
            log.warn("Password reset email could not be sent to {}. Reset URL: {}", toAddress, resetUrl, ex);
            throw ex;
        }
    }
}
