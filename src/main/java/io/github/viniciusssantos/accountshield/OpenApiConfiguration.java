package io.github.viniciusssantos.accountshield;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI accountShieldOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AccountShield Orchestrator API")
                        .description("Adaptive account-protection decision and orchestration platform with "
                                + "explainable risk policies, step-up challenges, secure recovery, "
                                + "abuse detection, replay, and security simulation.")
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("AccountShield")
                                .url("https://github.com/vinicius-ssantos/accountshield-orchestrator"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
