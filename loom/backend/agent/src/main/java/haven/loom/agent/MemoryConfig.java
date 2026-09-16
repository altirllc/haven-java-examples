package haven.loom.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Postgres-backed memory: recent history plus semantic recall.
 *
 * The window is the short-term half (the JDBC repository persists it into the
 * app's own schema). The long-term half is the pgvector store, which Spring AI
 * autoconfigures against POSTGRES_VECTORS_URL.
 *
 * Pick an embedding model and keep it: the vector dimension is baked into the
 * index on first use, so switching providers later means re-indexing.
 */
@Configuration
public class MemoryConfig {

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(AgentDefinition.LAST_MESSAGES)
                .build();
    }
}
