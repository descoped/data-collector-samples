package io.descoped.dc.samples.ske.freg;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.secrets.api.SecretManagerClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class GoogleSecretManagerIntegrationTest {

    static final Logger LOG = LoggerFactory.getLogger(GoogleSecretManagerIntegrationTest.class);

    @Disabled
    @Test
    public void loadSecrets() {
        DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
                .propertiesResource("application-override.properties")
                .build();

        Map<String, String> providerConfiguration = Map.of(
                "secrets.provider", "google-secret-manager",
                "secrets.projectId", "descoped-team",
                "secrets.serviceAccountKeyPath", getServiceAccountFile(configuration)
        );

        try (SecretManagerClient client = SecretManagerClient.create(providerConfiguration)) {
            assertNotNull(client.readBytes("ssb-prod-p12-certificate"));
            assertNotNull(client.readBytes("ssb-prod-p12-passphrase"));
            assertNotNull(client.readBytes("rawdata-prod-freg-encryption-key"));
            assertNotNull(client.readBytes("rawdata-prod-freg-encryption-salt"));
        }
    }

    static String getServiceAccountFile(DynamicConfiguration configuration) {
        String path = configuration.evaluateToString("gcp.service-account.file");
        if (path == null || !Files.isReadable(Paths.get(path))) {
            throw new RuntimeException("Missing 'application-override.properties'-file with required property 'gcp.service-account.file'");
        }
        return path;
    }
}
