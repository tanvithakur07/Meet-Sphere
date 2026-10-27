// backend/src/main/java/com/meetsphere/MeetSphereApplication.java
package com.meetsphere;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MeetSphere — Entry Point
 *
 * @SpringBootApplication enables:
 *   - @Configuration  : marks this as a config source
 *   - @EnableAutoConfiguration : auto-configures Spring beans
 *   - @ComponentScan  : scans com.meetsphere for all Spring components
 */
@SpringBootApplication
public class MeetSphereApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeetSphereApplication.class, args);
        System.out.println("""
            ╔══════════════════════════════════════════╗
            ║  🎥  MeetSphere Backend is Running!      ║
            ║  http://localhost:8080                   ║
            ╚══════════════════════════════════════════╝
            """);
    }
}
