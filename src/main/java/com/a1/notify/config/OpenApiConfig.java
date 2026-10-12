package com.a1.notify.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String ERROR_SCHEMA = "Error";

    @Bean
    public OpenAPI notifyOpenApi() {
        Schema<Object> error = new Schema<>()
                .type("object")
                .description("Stable error body. 'code' is machine readable and safe to branch on; 'message' is for humans; "
                        + "'correlationId' identifies the request in the engine logs. Provider payloads, stack traces and "
                        + "secrets are never returned.")
                .addProperty("code", new StringSchema().example("TEMPLATE_NOT_FOUND"))
                .addProperty("message", new StringSchema().example("No active template 'welcome' for channel EMAIL"))
                .addProperty("correlationId", new StringSchema().example("7f5d415f-c0d4-4a4c-b2f1-94eea7768fde"));

        // Swagger UI needs a concrete (or relative) server URL: a variable in the scheme position breaks "Try it out".
        // First entry is relative, so the docs page always calls the host and port it was loaded from.
        ServerVariables variables = new ServerVariables()
                .addServerVariable("scheme", new ServerVariable()._enum(List.of("http", "https"))._default("http")
                        .description("http for local development, https in production"))
                .addServerVariable("host", new ServerVariable()._default("localhost:8082")
                        .description("Host and port of the engine"));

        return new OpenAPI()
                .info(new Info().title("Notification Engine v2").version("1.0.0")
                        .description("""
                                Generic multi-project notification service. A project sends one request; the engine validates it, \
                                applies the user's preferences, renders a versioned template, stores it, and delivers it through \
                                email (Amazon SES), SMS (Amazon SNS), in-app inbox or push.

                                **Authentication.** Every route except health and API docs needs an `X-Api-Key` header. A project key \
                                identifies the project; all data is isolated per project. `/admin/**` needs the platform admin key.

                                **Delivery is asynchronous.** `POST /notifications` returns 202. Poll `GET /notifications/{id}` for \
                                the status. `SENT` means the provider accepted the message, not that the recipient received it.

                                **Idempotency.** Send a stable `idempotencyKey` per business event; a replay returns the original \
                                notification and never sends twice.""")
                        .license(new License().name("Proprietary - internal use").identifier("LicenseRef-Proprietary"))
                        .contact(new Contact().name("Notification engine owner").email("replace-me@example.com")))
                .servers(List.of(
                        new Server().url("/api/v1").description("This server (same origin as this page)"),
                        new Server().url("{scheme}://{host}/api/v1").description("Another host (for clients and generators)")
                                .variables(variables)))
                .tags(List.of(
                        new Tag().name("Notifications").description("Create, track and retry notifications (scopes SEND, READ, RETRY)"),
                        new Tag().name("OTP").description("One-time codes with expiry, attempt limits and resend cooldown (scope OTP)"),
                        new Tag().name("Templates").description("Per-project versioned templates (scope TEMPLATES)"),
                        new Tag().name("Preferences").description("User consent per channel and category (scope PREFERENCES)"),
                        new Tag().name("Inbox").description("In-app notifications and push device tokens (scope INBOX)"),
                        new Tag().name("Admin").description("Platform administration: projects, API keys, dead-letter queue (admin key)")))
                .components(new Components()
                        .addSchemas(ERROR_SCHEMA, error)
                        .addSecuritySchemes("apiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Api-Key")
                                .description("Project API key (ntf_...) issued by the platform admin, or the admin key for /admin routes.")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }

    /** Adds the shared error responses to every operation so each route documents them consistently. */
    @Bean
    public OperationCustomizer standardErrorResponses() {
        record Err(String description, String code, String message) {
        }
        Map<String, Err> errors = new java.util.LinkedHashMap<>();
        errors.put("400", new Err("Invalid request (validation, unknown template, missing variable, invalid recipient). Do not retry unchanged.",
                "TEMPLATE_VARIABLE_MISSING", "Missing template variable: name"));
        errors.put("401", new Err("Missing, unknown or revoked X-Api-Key. Do not retry; fix the key.",
                "UNAUTHENTICATED", "A valid X-Api-Key is required"));
        errors.put("403", new Err("The key is valid but lacks the required scope, or a project key was used on /admin.",
                "FORBIDDEN", "Insufficient permissions"));
        errors.put("404", new Err("Not found, or it belongs to another project (the two are deliberately indistinguishable).",
                "NOT_FOUND", "Notification not found"));
        errors.put("409", new Err("Conflict (for example NOT_RETRYABLE, DUPLICATE_REQUEST). See the error code.",
                "NOT_RETRYABLE", "Only retryable FAILED notifications can be retried"));
        errors.put("429", new Err("Rate limit, daily quota or OTP limit reached. Honour the Retry-After header, then retry.",
                "RATE_LIMITED", "Project rate limit exceeded"));
        return (Operation operation, org.springframework.web.method.HandlerMethod handlerMethod) -> {
            errors.forEach((status, err) -> {
                if (operation.getResponses() == null || !operation.getResponses().containsKey(status)) {
                    Map<String, Object> example = new java.util.LinkedHashMap<>();
                    example.put("code", err.code());
                    example.put("message", err.message());
                    example.put("correlationId", "7f5d415f-c0d4-4a4c-b2f1-94eea7768fde");
                    operation.getResponses().addApiResponse(status, new ApiResponse().description(err.description())
                            .content(new Content().addMediaType("application/json",
                                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA)).example(example))));
                }
            });
            return operation;
        };
    }
}
