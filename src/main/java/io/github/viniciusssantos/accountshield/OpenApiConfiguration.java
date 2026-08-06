package io.github.viniciusssantos.accountshield;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    // Sourced from BuildProperties (pom.xml's project.version via the build-info Maven plugin
    // goal, bound to generate-resources) rather than a hardcoded literal (issue #151 / F-23):
    // the contract's own version had drifted to "0.1.0" while the POMs, git tag, and release
    // were all "1.0.0", with nothing keeping them in sync.
    @Bean
    OpenAPI accountShieldOpenApi(BuildProperties buildProperties) {
        return new OpenAPI()
                .info(new Info()
                        .title("AccountShield Orchestrator API")
                        .description("Adaptive account-protection decision and orchestration platform with "
                                + "explainable risk policies, step-up challenges, secure recovery, "
                                + "abuse detection, replay, and security simulation.")
                        .version(buildProperties.getVersion())
                        .contact(new Contact()
                                .name("AccountShield")
                                .url("https://github.com/vinicius-ssantos/accountshield-orchestrator"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
