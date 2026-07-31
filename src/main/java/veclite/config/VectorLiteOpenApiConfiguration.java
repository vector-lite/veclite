package veclite.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(GroupedOpenApi.class)
@ConditionalOnProperty(name = "veclite.web.enabled", havingValue = "true")
public class VectorLiteOpenApiConfiguration {

    @Bean
    public GroupedOpenApi vectorLiteGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("veclite")
                .displayName("VecLite")
                .pathsToMatch("/veclite/**")
                .build();
    }

    @Bean
    public OpenAPI vectorLiteOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("VectorLite API")
                        .description("Local in-memory vector search engine create stores, upsert documents, search by text or vector, and manage persistence.")
                        .version("1.0.0"));
    }
}
