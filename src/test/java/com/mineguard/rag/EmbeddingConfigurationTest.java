package com.mineguard.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mineguard.config.EmbeddingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class EmbeddingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddingConfiguration.class).withBean(ObjectMapper.class, ObjectMapper::new);
    @Test void explicitHashingAndSemanticProvidersAreDistinct() {
        runner.withPropertyValues("mineguard.embedding.provider=hashing").run(context ->
                assertThat(context.getBean(EmbeddingClient.class)).isInstanceOf(HashingEmbeddingClient.class));
        runner.withPropertyValues("mineguard.embedding.provider=openai-compatible", "mineguard.embedding.base-url=http://127.0.0.1:18082/v1",
                "mineguard.embedding.model=BAAI/bge-small-zh-v1.5", "mineguard.embedding.dimensions=512",
                "mineguard.embedding.timeout-seconds=10", "mineguard.embedding.max-requests=100").run(context -> {
            assertThat(context.getBean(EmbeddingClient.class)).isInstanceOf(OpenAiCompatibleEmbeddingClient.class);
            assertThat(context.getBean(EmbeddingClient.class).dimensions()).isEqualTo(512);
        });
    }
    @Test void unknownProviderFailsInsteadOfFallingBack() {
        runner.withPropertyValues("mineguard.embedding.provider=typo").run(context -> assertThat(context).hasFailed());
    }
}
