package io.descoped.dc.samples.ske.freg;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.Specification;
import io.descoped.dc.api.node.builder.SpecificationBuilder;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.application.ssl.SecretManagerSSLResource;
import io.descoped.dc.core.executor.Worker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static io.descoped.dc.api.Builders.addContent;
import static io.descoped.dc.api.Builders.context;
import static io.descoped.dc.api.Builders.eval;
import static io.descoped.dc.api.Builders.execute;
import static io.descoped.dc.api.Builders.get;
import static io.descoped.dc.api.Builders.jqpath;
import static io.descoped.dc.api.Builders.nextPage;
import static io.descoped.dc.api.Builders.paginate;
import static io.descoped.dc.api.Builders.parallel;
import static io.descoped.dc.api.Builders.publish;
import static io.descoped.dc.api.Builders.security;
import static io.descoped.dc.api.Builders.sequence;
import static io.descoped.dc.api.Builders.status;
import static io.descoped.dc.api.Builders.statusCode;
import static io.descoped.dc.api.Builders.whenVariableIsNull;

public class FregKomplettUttrekkTest {

    static final SpecificationBuilder specificationBuilder = Specification.start("SKE-FREG-UTTREKK-BATCH", "Collect FREG Bulk Uttrekk", "create-job")
            .configure(context()
                            .topic("freg-uttrekk-komplett")
                            .variable("ProduksjonURL", "https://folkeregisteret.api.skatteetaten.no/folkeregisteret/offentlig-med-hjemmel")
                            .variable("fromFeedSequence", "0")
//                    .variable("jobId", "c5d3af2f-4ac7-4506-af67-61e71c5f0b8f")
                            .variable("nextBatch", "0")
            )
            .configure(security()
                    .sslBundleName("ske-prod-certs")
            )
            .function(get("create-job")
                    .url("${ProduksjonURL}/api/v1/uttrekk/komplett?feedsekvensnr=${fromFeedSequence}")
                    .validate(status().success(200))
                    .pipe(execute("loop")
                            .requiredInput("jobId")
                            .inputVariable("jobId", jqpath(".jobbId"))
                    )
            )
            .function(paginate("loop")
                    .variable("fromBatch", "${nextBatch}")
                    .iterate(execute("batch-list")
                            .requiredInput("jobId")
                            .requiredInput("fromBatch")
                    )
                    .prefetchThreshold(100_000)
                    .until(whenVariableIsNull("nextBatch"))
            )
            .function(get("batch-list")
                    .url("${ProduksjonURL}/api/v1/uttrekk/${jobId}/batch/${fromBatch}")
                    .retryWhile(statusCode().is(404, TimeUnit.SECONDS, 15))
                    .validate(status().success(200))
                    .pipe(sequence(jqpath(".dokumentidentifikator[]"))
                            .expected(jqpath("."))
                    )
                    .pipe(nextPage().output("nextBatch", eval("${cast.toLong(fromBatch) + 1}")))
                    .pipe(parallel(jqpath(".dokumentidentifikator[]"))
                            .variable("position", jqpath("."))
                            .pipe(addContent("${position}", "entry"))
                            .pipe(execute("person-document")
                                    .inputVariable("personDocumentId", jqpath("."))
                            )
                            .pipe(publish("${position}"))
                    )
                    .returnVariables("nextBatch")
            )
            .function(get("person-document")
                    .url("${ProduksjonURL}/api/v1/personer/arkiv/${personDocumentId}?part=person-basis&part=identitetsgrunnlag-utvidet&part=relasjon-utvidet&part=utlendingsmyndighetenesIdentifikasjonsnummer&part=innflytting&part=utflytting&part=foedselINorge&part=opphold&part=forholdTilSametingetsValgmanntall")
                    .header("Accept", "application/xml")
                    .validate(status().success(200))
                    .pipe(addContent("${position}", "person"))
            );

    @Disabled
    @Test
    public void fregUttrekkBatch() {
        Worker.newBuilder()
                .configuration(new StoreBasedDynamicConfiguration.Builder()
                        .values("data.collector.worker.threads", "20")
                        .values("content.stream.connector", "rawdata")
                        .values("rawdata.client.provider", "filesystem")
                        .values("filesystem.storage-folder", "target/avro/rawdata-store")
                        .values("local-temp-folder", "target/avro/temp")
                        .values("avro-file.max.seconds", "60")
                        .values("avro-file.max.bytes", "67108864")
                        .values("avro-file.sync.interval", "20")
                        .values("listing.min-interval-seconds", "0")
                        .values("gcs.bucket-name", "")
                        .values("gcs.listing.min-interval-seconds", "30")
                        .values("gcs.service-account.key-file", "")
                        .environment("DC_")
                        .build()
                        .asMap())
                .buildCertificateFactory(Paths.get("/Volumes/SSB BusinessSSL/certs"))
                .printConfiguration()
                .specification(specificationBuilder)
                .stopAtNumberOfIterations(25000)
                .build()
                .run();
    }

    @Disabled
    @Test
    public void fregUttrekkBatchWithSecretManager() {
        DynamicConfiguration securityConfiguration = new StoreBasedDynamicConfiguration.Builder()
                .propertiesResource("application-override.properties")
                .build();

        DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
                .values("data.collector.worker.threads", "20")
                .values("content.stream.connector", "rawdata")
                .values("rawdata.client.provider", "filesystem")
                .values("filesystem.storage-folder", "target/avro/rawdata-store")
                .values("local-temp-folder", "target/avro/temp")
                .values("avro-file.max.seconds", "60")
                .values("avro-file.max.bytes", "67108864")
                .values("avro-file.sync.interval", "20")
                .values("listing.min-interval-seconds", "0")
                .values("gcs.bucket-name", "")
                .values("gcs.listing.min-interval-seconds", "30")
                .values("gcs.service-account.key-file", "")
                .values("data.collector.sslBundle.provider", "google-secret-manager")
                .values("data.collector.sslBundle.gcp.projectId", "descoped-team")
                .values("data.collector.sslBundle.gcp.serviceAccountKeyPath", GoogleSecretManagerIntegrationTest.getServiceAccountFile(securityConfiguration))
                .values("data.collector.sslBundle.type", "p12")
                .values("data.collector.sslBundle.name", "ske-prod-certs")
                .values("data.collector.sslBundle.archiveCertificate", "ssb-prod-p12-certificate")
                .values("data.collector.sslBundle.passphrase", "ssb-prod-p12-passphrase")
                .values("rawdata.encryption.provider", "google-secret-manager")
                .values("rawdata.encryption.gcp.serviceAccountKeyPath", GoogleSecretManagerIntegrationTest.getServiceAccountFile(securityConfiguration))
                .values("rawdata.encryption.gcp.projectId", "descoped-team")
                .values("rawdata.encryption.key", "rawdata-prod-freg-encryption-key")
                .values("rawdata.encryption.salt", "rawdata-prod-freg-encryption-salt")
                .environment("DC_")
                .build();

        Worker.newBuilder()
                .configuration(configuration.asMap())
                .useBusinessSSLResourceSupplier(() -> new SecretManagerSSLResource(configuration))
                .printConfiguration()
                .specification(specificationBuilder)
                .stopAtNumberOfIterations(25000)
                .build()
                .run();
    }

    @Disabled
    @Test
    public void writeTargetConsumerSpec() throws IOException {
        Path currentPath = CommonUtils.currentPath().getParent().getParent();
        Path targetPath = currentPath.resolve("data-collection-consumer-specifications");

        boolean targetProjectExists = targetPath.toFile().exists();
        if (!targetProjectExists) {
            throw new RuntimeException(String.format("Couldn't locate '%s' under currentPath: %s%n", targetPath.toFile().getName(), currentPath.toAbsolutePath().toString()));
        }

        Files.writeString(targetPath.resolve("specs").resolve("ske-freg-bulk-uttrekk-spec.json"), specificationBuilder.serialize());
    }
}
