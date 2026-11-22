package com.example.geoalert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GeoAlertApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeoAlertApplication.class, args);
    }
}
