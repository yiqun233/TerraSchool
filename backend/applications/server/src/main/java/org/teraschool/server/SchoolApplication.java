package org.teraschool.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "org.teraschool")
@EnableScheduling
public class SchoolApplication {
  public static void main(String[] args) {
    SpringApplication.run(SchoolApplication.class, args);
  }
}
