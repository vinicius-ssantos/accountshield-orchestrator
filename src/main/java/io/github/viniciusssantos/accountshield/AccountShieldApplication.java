package io.github.viniciusssantos.accountshield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;

@Modulith
@SpringBootApplication
public class AccountShieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountShieldApplication.class, args);
    }
}
