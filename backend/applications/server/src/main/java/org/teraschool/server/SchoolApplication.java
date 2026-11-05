package org.teraschool.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.teraschool")
public class SchoolApplication {
  public static void main(String[] args) {
    SpringApplication.run(SchoolApplication.class, args);
  }
}
