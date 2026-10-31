package org.teraschool.verification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** R3-CORE-001 隔离验证入口；不提供正式招生、收费或排课功能。 */
@SpringBootApplication
public class ProbeApplication {
    public static void main(String[] args) { SpringApplication.run(ProbeApplication.class, args); }
}
