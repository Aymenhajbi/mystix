package ma.mystix.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The artefact store of this process (ADR-0017): the database by default, S3 when {@code mystix.storage.mode=s3}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfig {

    @Bean
    ArtifactStore artifactStore(StorageProperties properties) {
        if (properties.s3()) {
            S3ArtifactStore store = new S3ArtifactStore(properties);
            store.checkBucket();
            return store;
        }
        if (!"database".equals(properties.mode())) {
            throw new IllegalStateException("mystix.storage.mode must be database or s3, not " + properties.mode());
        }
        return new ArtifactStore() {
            @Override
            public boolean external() {
                return false;
            }

            @Override
            public StoredObject put(String key, byte[] content, String mediaType, String sha256) {
                throw new UnsupportedOperationException("database mode: artefacts stay in their table");
            }

            @Override
            public byte[] get(String key, String versionId, String sha256) {
                throw new UnsupportedOperationException("database mode: artefacts stay in their table");
            }
        };
    }
}
