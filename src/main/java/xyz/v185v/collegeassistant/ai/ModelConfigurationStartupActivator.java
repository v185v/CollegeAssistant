package xyz.v185v.collegeassistant.ai;

import java.util.Arrays;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.ai.config.ModelSecretCipher;

/** 启动时加载持久化候选项，绝不跨越 Embedding 向量空间。 */
@Component
@ConditionalOnBean(DataSource.class)
public class ModelConfigurationStartupActivator implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ModelConfigurationStartupActivator.class);

    private final ModelConfigurationRepository repository;
    private final ModelSecretCipher cipher;
    private final ModelRuntimeFactory runtimeFactory;
    private final ModelRegistry registry;

    public ModelConfigurationStartupActivator(
            ModelConfigurationRepository repository,
            ModelSecretCipher cipher,
            ModelRuntimeFactory runtimeFactory,
            ModelRegistry registry
    ) {
        this.repository = repository;
        this.cipher = cipher;
        this.runtimeFactory = runtimeFactory;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        activate();
    }

    public void activate() {
        loadActiveChat(repository.findActive(ModelKind.CHAT));
        loadPendingChat(repository.findPending(ModelKind.CHAT));
        loadActiveEmbedding(repository.findActive(ModelKind.EMBEDDING));
        loadPendingEmbedding(repository.findPending(ModelKind.EMBEDDING));
    }

    private void loadActiveChat(Optional<ModelConfiguration> active) {
        if (active.isEmpty() || active.orElseThrow().isBootstrap()) {
            registry.fallbackChat();
            return;
        }
        ModelConfiguration configuration = active.orElseThrow();
        try (ModelRuntimeSpec spec = runtimeSpec(configuration)) {
            runtimeFactory.probe(spec);
            registry.activateChat(runtimeFactory.buildChat(configuration.id(), spec));
        } catch (RuntimeException failure) {
            registry.fallbackChat();
            warnUnavailable(configuration, failure);
        }
    }

    private void loadPendingChat(Optional<ModelConfiguration> pending) {
        if (pending.isEmpty()) {
            return;
        }
        ModelConfiguration configuration = pending.orElseThrow();
        try (ModelRuntimeSpec spec = runtimeSpec(configuration)) {
            runtimeFactory.probe(spec);
            ModelRegistry.ChatContext candidate =
                    runtimeFactory.buildChat(configuration.id(), spec);
            repository.activateChat(configuration.id());
            registry.activateChat(candidate);
        } catch (RuntimeException failure) {
            repository.markFailed(
                    configuration.id(), "Chat model initialization failed");
            warnUnavailable(configuration, failure);
        }
    }

    private void loadActiveEmbedding(Optional<ModelConfiguration> active) {
        if (active.isEmpty()) {
            registry.clearEmbedding();
            return;
        }
        ModelConfiguration configuration = active.orElseThrow();
        if (configuration.isBootstrap()) {
            registry.activateEmbedding(new EffectiveEmbeddingContext(
                    configuration.id(), registry.bootstrapEmbedding()));
            return;
        }
        try (ModelRuntimeSpec spec = runtimeSpec(configuration)) {
            runtimeFactory.probe(spec);
            registry.activateEmbedding(
                    runtimeFactory.buildEmbedding(configuration.id(), spec));
        } catch (RuntimeException failure) {
            registry.clearEmbedding();
            warnUnavailable(configuration, failure);
        }
    }

    private void loadPendingEmbedding(Optional<ModelConfiguration> pending) {
        registry.clearEmbeddingCandidate();
        if (pending.isEmpty()) {
            return;
        }
        ModelConfiguration configuration = pending.orElseThrow();
        try (ModelRuntimeSpec spec = runtimeSpec(configuration)) {
            runtimeFactory.probe(spec);
            registry.loadEmbeddingCandidate(
                    runtimeFactory.buildEmbedding(configuration.id(), spec));
        } catch (RuntimeException failure) {
            repository.markEmbeddingFailedAndScheduleCleanup(
                    configuration.id(), "Embedding model initialization failed");
            warnUnavailable(configuration, failure);
        }
    }

    private ModelRuntimeSpec runtimeSpec(ModelConfiguration configuration) {
        if (configuration.isBootstrap()) {
            throw new IllegalArgumentException("Bootstrap configurations use environment clients");
        }
        cipher.requireAvailable();
        char[] secret = configuration.hasPersistedSecret()
                ? cipher.decrypt(configuration.encryptedSecret(), configuration.aad())
                : new char[0];
        try {
            return new ModelRuntimeSpec(
                    configuration.kind(), configuration.provider(), configuration.model(),
                    configuration.baseUrl(), configuration.temperature(),
                    configuration.dimension(), secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void warnUnavailable(
            ModelConfiguration configuration,
            RuntimeException failure
    ) {
        LOGGER.warn(
                "Persisted model configuration is unavailable: id={} kind={} causeType={}",
                configuration.id(), configuration.kind(), failure.getClass().getName());
    }
}
