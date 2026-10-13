package com.textharbor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ExtractionProperties.class)
public class TextHarborApplication {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--extraction-worker")) {
            ExtractionEngine.runWorker(java.nio.file.Path.of(args[1]));
            return;
        }
        SpringApplication.run(TextHarborApplication.class, args);
    }
}
