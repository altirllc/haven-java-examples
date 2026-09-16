package haven.loom;

import haven.loom.api.Storage;
import haven.loom.api.mcp.CaseMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.event.EventListener;

/**
 * Two listeners, one process — REST on 3000, MCP-only on 3001.
 *
 * This split is a security requirement, not a preference. The tenant edge leaves
 * /mcp UNGATED and routes it to a dedicated `loom-mcp` service, so that
 * listener must expose nothing else: an ungated request to /mcp/loom/api/items
 * would otherwise reach the REST API after Traefik strips the prefix. A second
 * Tomcat connector does NOT achieve this — requests leak across the security
 * chain (spring-boot#50355). Separate child contexts do: each scans only its own
 * controllers, so the REST routes do not exist on 3001 at all.
 *
 * Shared beans — datasource, JPA, the items service, the agent, Temporal — live
 * in the parent. The children's autoconfiguration backs off from re-creating
 * them because @ConditionalOnMissingBean searches the context hierarchy.
 */
public class ApiApplication {

    public static void main(String[] args) {
        // The parent is the constructor argument. Do NOT use .parent(...) here:
        // it returns the ORIGINAL builder, not the parent, so the chain silently
        // builds a three-level hierarchy with an empty middle and the shared
        // beans detached — which looks like it works until nothing is wired.
        new SpringApplicationBuilder(SharedContext.class)
                .web(WebApplicationType.NONE)
                // The MCP server machinery belongs to the MCP child alone.
                .properties("spring.ai.mcp.server.enabled=false")
                .child(RestContext.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=${PORT:3000}", "spring.ai.mcp.server.enabled=false")
                .sibling(McpContext.class)
                .web(WebApplicationType.SERVLET)
                // exclude, not an empty include: these are DEFAULT properties, and
                // application.yaml's include list would override an include set here.
                // The exclude keeps actuator off the MCP listener entirely.
                .properties("server.port=${MCP_PORT:3001}", "spring.ai.mcp.server.enabled=true",
                        "management.endpoints.web.exposure.exclude=*")
                .run(args);
    }

    /** Everything both listeners need. No controllers, no web server. */
    @SpringBootApplication
    @ComponentScan(basePackages = {"haven.loom.persistence", "haven.loom.orchestration", "haven.loom.agent"})
    @ConfigurationPropertiesScan({"haven.loom.persistence", "haven.loom.orchestration", "haven.loom.agent"})
    public static class SharedContext {}

    /** The REST listener: haven.loom.api EXCEPT the MCP surface. */
    @SpringBootApplication
    @ComponentScan(
            basePackages = "haven.loom.api",
            excludeFilters = @Filter(type = FilterType.REGEX, pattern = "haven\\.loom\\.api\\.mcp\\..*"))
    @ConfigurationPropertiesScan("haven.loom.api")
    public static class RestContext {

        private final Storage storage;

        RestContext(Storage storage) {
            this.storage = storage;
        }

        @EventListener(ApplicationReadyEvent.class)
        void ensureBucket() {
            storage.ensureBucket();
        }
    }

    /**
     * The MCP listener: the MCP surface ONLY.
     *
     * The COMPONENT scan is what isolates — it never sees a REST controller. The
     * properties scan is wider only to reach HavenProperties (the tenant id the
     * token's org is checked against); config records are not endpoints.
     */
    @SpringBootApplication
    @ComponentScan(basePackages = "haven.loom.api.mcp")
    @ConfigurationPropertiesScan({"haven.loom.api.mcp", "haven.loom.api"})
    public static class McpContext {

        // The explicit bean is the hand-off: the MCP server autoconfiguration
        // converts ToolCallbackProvider beans into tool specifications. The
        // @McpTool annotation scanner is NOT used — it collects beans via a
        // BeanPostProcessor, so whether tools register depends on bean
        // creation order. This path is ordered by the bean graph.
        @Bean
        ToolCallbackProvider itemTools(CaseMcpTools tools) {
            return MethodToolCallbackProvider.builder().toolObjects(tools).build();
        }
    }
}
