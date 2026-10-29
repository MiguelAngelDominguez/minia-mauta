package pe.utec.minia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniaApplication.class, args);
    }
}
