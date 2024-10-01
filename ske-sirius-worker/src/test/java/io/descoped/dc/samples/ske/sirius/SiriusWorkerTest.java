package io.descoped.dc.samples.ske.sirius;

import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.Specification;
import io.descoped.dc.api.node.builder.SpecificationBuilder;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.core.executor.Worker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static io.descoped.dc.api.Builders.addContent;
import static io.descoped.dc.api.Builders.bodyContains;
import static io.descoped.dc.api.Builders.context;
import static io.descoped.dc.api.Builders.eval;
import static io.descoped.dc.api.Builders.execute;
import static io.descoped.dc.api.Builders.get;
import static io.descoped.dc.api.Builders.nextPage;
import static io.descoped.dc.api.Builders.paginate;
import static io.descoped.dc.api.Builders.parallel;
import static io.descoped.dc.api.Builders.publish;
import static io.descoped.dc.api.Builders.security;
import static io.descoped.dc.api.Builders.sequence;
import static io.descoped.dc.api.Builders.status;
import static io.descoped.dc.api.Builders.whenVariableIsNull;
import static io.descoped.dc.api.Builders.xpath;

// https://skatteetaten.github.io/datasamarbeid-api-dokumentasjon/reference_skattemelding
public class SiriusWorkerTest {

    static final SpecificationBuilder specificationBuilder = Specification.start("SKE-SIRIUS", "Collect Sirius", "loop")
            .configure(context()
                            .topic("sirius-person-utkast")
                            .header("accept", "application/xml")
                            .variable("baseURL", "https://api-at.sits.no")
//                    .variable("baseURL", "https://api.skatteetaten.no")
                            .variable("rettighetspakke", "ssb")
                            .variable("hentAntallMeldingerOmGangen", "100")
//                    .variable("hendelse", "utkast")
                            .variable("hendelse", "fastsatt")
                            .variable("nextSequence", "${cast.toLong(contentStream.lastOrInitialPosition(0)) + 1}")
            )
            .configure(security()
                            .sslBundleName("ssb-test-certs")
//                    .sslBundleName("ssb-p12-certs")
            )
            .function(paginate("loop")
                    .variable("fromSequence", "${nextSequence}")
                    .addPageContent("fromSequence")
                    .iterate(execute("parts"))
                    .prefetchThreshold(150)
                    .until(whenVariableIsNull("nextSequence"))
            )
            .function(get("parts")
                    .url("${baseURL}/api/formueinntekt/skattemelding/${hendelse}/hendelser/?fraSekvensnummer=${fromSequence}&antall=${hentAntallMeldingerOmGangen}")
                    .validate(status().success(200).fail(400).fail(404).fail(500))
                    .pipe(sequence(xpath("/hendelser/hendelse"))
                            .expected(xpath("/hendelse/sekvensnummer"))
                    )
                    .pipe(nextPage()
                            .output("nextSequence",
                                    eval(xpath("/hendelser/hendelse[last()]/sekvensnummer"), "lastSequence", "${cast.toLong(lastSequence) + 1}")
                            )
                    )
                    .pipe(parallel(xpath("/hendelser/hendelse"))
                            .variable("position", xpath("/hendelse/sekvensnummer"))
                            .pipe(addContent("${position}", "entry"))
                            .pipe(execute("utkast-melding")
                                    .inputVariable("utkastIdentifikator", xpath("/hendelse/identifikator"))
                                    .inputVariable("gjelderPeriode", xpath("/hendelse/gjelderPeriode"))
                                    .inputVariable("registreringstidspunkt", xpath("/hendelse/registreringstidspunkt"))
                            )
                            .pipe(publish("${position}"))
                    )
                    .returnVariables("nextSequence")
            )
            .function(get("utkast-melding")
                    .url("${baseURL}/api/formueinntekt/skattemelding/${hendelse}/${rettighetspakke}/${gjelderPeriode}/${utkastIdentifikator}?gjelderPaaTidspunkt=${registreringstidspunkt}")
                    .validate(status()
                            .success(200)
                            .success(404, bodyContains(xpath("/feil/kode"), "SM-001"))
                            .success(404, bodyContains(xpath("/feil/kode"), "SM-002"))
                            .success(410, bodyContains(xpath("/feil/kode"), "SM-013"))
                            .fail(400)
                            .fail(404)
                            .fail(500)
                    )
                    .pipe(addContent("${position}", "skattemelding"))
            );

    @Disabled
    @Test
    public void thatWorkerCollectSiriusData() throws InterruptedException {
//        Path scanDirectory = CommonUtils.currentPath().resolve("certs");
        Path scanDirectory = Paths.get("/Volumes/SSB BusinessSSL/certs");

        Worker.newBuilder()
                .configuration(new StoreBasedDynamicConfiguration.Builder()
                        .values("content.stream.connector", "rawdata")
//                        .values("content.stream.connector", "discarding")
                        .values("rawdata.client.provider", "memory")
                        .values("data.collector.worker.threads", "20")
                        .values("local-temp-folder", "target/_tmp_avro_")
                        .values("avro-file.max.seconds", "86400")
                        .values("avro-file.max.bytes", "67108864")
                        .values("avro-file.sync.interval", "524288")
                        .values("gcs.bucket-name", "")
                        .values("gcs.listing.min-interval-seconds", "30")
                        .values("gcs.service-account.key-file", "")
                        .values("postgres.driver.host", "localhost")
                        .values("postgres.driver.port", "5432")
                        .values("postgres.driver.user", "rdc")
                        .values("postgres.driver.password", "rdc")
                        .values("postgres.driver.database", "rdc")
                        .values("rawdata.postgres.consumer.prefetch-size", "100")
                        .values("rawdata.postgres.consumer.prefetch-poll-interval-when-empty", "1000")
                        .environment("DC_")
                        .build()
                        .asMap())
                .buildCertificateFactory(scanDirectory)
                //.stopAtNumberOfIterations(5)
                .printConfiguration()
                //.printExecutionPlan()
                .specification(specificationBuilder)
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

        if (true) {
            Files.writeString(targetPath.resolve("specs").resolve("ske-sirius-person-utkast-spec.json"), specificationBuilder.serialize());
            Files.writeString(targetPath.resolve("specs").resolve("ske-sirius-person-fastsatt-spec.json"), specificationBuilder.serialize().replace("utkast", "fastsatt"));
        } else {
            Files.writeString(targetPath.resolve("specs").resolve("ske-sirius-person-utkast-spec.yml"), specificationBuilder.serializeAsYaml());
            Files.writeString(targetPath.resolve("specs").resolve("ske-sirius-person-fastsatt-spec.yml"), specificationBuilder.serializeAsYaml().replace("utkast", "fastsatt"));
        }
    }
}
