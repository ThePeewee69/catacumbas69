package com.catacumbas69.catasonarr.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Paths match without regard to case, as Sonarr's (ASP.NET routing ignores case; orch.md decision 117): Seerr asks
 * for {@code /api/v3/qualityProfile}, which Sonarr answers and a case-sensitive Spring would answer with 404.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        configurer.setPatternParser(parser);
    }
}
