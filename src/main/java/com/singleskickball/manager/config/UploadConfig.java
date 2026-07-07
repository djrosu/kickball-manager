package com.singleskickball.manager.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * File upload and static-file serving configuration.
 *
 * Walk-up song MP3 files are stored outside of the application JAR so they can
 * survive redeploys. On Railway this directory should be backed by the app
 * volume mounted at /app/uploads.
 *
 * Browser URL:
 *   https://app.singlessportssocial.com/uploads/walkup-songs/player-1-song.mp3
 *
 * Server file path:
 *   /app/uploads/walkup-songs/player-1-song.mp3
 */
@Configuration
public class UploadConfig implements WebMvcConfigurer {

    /**
     * Root upload directory.
     *
     * Railway production should use:
     *   APP_UPLOADS_ROOT_PATH=/app/uploads
     *
     * The default is also /app/uploads because that is what we are using for
     * the Railway volume. If you want to test uploads locally on Windows, set
     * APP_UPLOADS_ROOT_PATH=uploads in your IntelliJ run configuration.
     */
    private final String uploadsRootPath;

    public UploadConfig(@Value("${app.uploads.root-path:${APP_UPLOADS_ROOT_PATH:/app/uploads}}") String uploadsRootPath) {
        this.uploadsRootPath = uploadsRootPath;
    }

    /**
     * Serves files from the upload directory under /uploads/**.
     *
     * Example:
     *   /uploads/walkup-songs/diesel.mp3
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(uploadsRootPath).toAbsolutePath().normalize().toUri().toString();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }

    /**
     * Raises the multipart limit so short MP3 clips can be uploaded through the
     * manager page without editing application.yml.
     */
    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(25));
        factory.setMaxRequestSize(DataSize.ofMegabytes(25));
        return factory.createMultipartConfig();
    }
}
