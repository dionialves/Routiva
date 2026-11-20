package br.com.scriptping;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StaticImageConfiguration implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Image URLs carry a version; leave API responses and other static resources unchanged.
        var cache = CacheControl.maxAge(Duration.ofDays(30)).cachePrivate();
        registry.addResourceHandler("/map-assets/**")
            .addResourceLocations("classpath:/static/map-assets/").setCacheControl(cache);
        registry.addResourceHandler("/traceone-brand.webp")
            .addResourceLocations("classpath:/static/").setCacheControl(cache);
    }
}
