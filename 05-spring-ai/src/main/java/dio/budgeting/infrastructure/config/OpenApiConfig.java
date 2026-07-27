package dio.budgeting.infrastructure.config;

import dio.budgeting.infrastructure.http.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Budgeting API",
                description = "API de controle financeiro com Spring Boot e Spring AI",
                version = "0.0.1-SNAPSHOT"
        )
)
public class OpenApiConfig {

    /**
     * Documents {@value CorrelationIdFilter#HEADER} globally (request header,
     * optional, on every operation) instead of repeating an {@code @Parameter}
     * on each endpoint - TASK-011. Only documents the header's existence and
     * shape, never internals of the metrics/MDC it feeds.
     */
    @Bean
    public OpenApiCustomizer correlationIdHeaderCustomizer() {
        return openApi -> openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    var header = new Parameter()
                            .in(ParameterIn.HEADER.toString())
                            .name(CorrelationIdFilter.HEADER)
                            .required(false)
                            .description("Identificador de correlação opaco, opcional. Se enviado e seguro "
                                    + "(até 64 caracteres, apenas letras/números/'._-'), é reutilizado; caso "
                                    + "contrário um novo é gerado. Sempre devolvido no mesmo header da resposta.")
                            .schema(new Schema<String>().type("string"));
                    operation.addParametersItem(header);
                    if (operation.getResponses() != null) {
                        operation.getResponses().values().forEach(response -> response.addHeaderObject(
                                CorrelationIdFilter.HEADER,
                                new Header().description("Identificador de correlação desta requisição.")
                                        .schema(new Schema<String>().type("string"))));
                    }
                }));
    }
}
