package no.ssb.dc.samples.enhetsregisteret;

import com.fasterxml.jackson.databind.JsonNode;
import no.ssb.config.DynamicConfiguration;
import no.ssb.config.StoreBasedDynamicConfiguration;
import no.ssb.dc.api.Builders;
import no.ssb.dc.api.Specification;
import no.ssb.dc.api.handler.QueryFeature;
import no.ssb.dc.api.http.Client;
import no.ssb.dc.api.http.Request;
import no.ssb.dc.api.http.Response;
import no.ssb.dc.api.node.JqPath;
import no.ssb.dc.api.node.builder.JqPathBuilder;
import no.ssb.dc.api.node.builder.SpecificationBuilder;
import no.ssb.dc.api.util.CommonUtils;
import no.ssb.dc.api.util.JsonParser;
import no.ssb.dc.core.executor.Worker;
import no.ssb.dc.core.handler.Queries;
import no.ssb.rawdata.api.RawdataClient;
import no.ssb.rawdata.api.RawdataClientInitializer;
import no.ssb.rawdata.api.RawdataConsumer;
import no.ssb.rawdata.api.RawdataMessage;
import no.ssb.service.provider.api.ProviderConfigurator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static no.ssb.dc.api.Builders.*;

/*
 * /https://data.brreg.no/enhetsregisteret/api/docs/index.html
 * https://www.brreg.no/produkter-og-tjenester/apne-data/beskrivelse-av-tjenesten-data-fra-enhetsregisteret/
 * https://data.brreg.no/enhetsregisteret/api/docs/index.html
 */
public class EnhetsregisteretUpdatesWorkerTest {

    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .values("content.stream.connector", "rawdata")
            .values("rawdata.client.provider", "filesystem")
            .values("data.collector.worker.threads", "20")
            .values("rawdata.topic", "enhetsregister-update")
            .values("local-temp-folder", "target/avro/temp")
            .values("filesystem.storage-folder", "target/avro/rawdata-store")
            .values("avro-file.max.seconds", "60")
            .values("avro-file.max.bytes", Long.toString(512 * 1024 * 1024)) // 512 MiB
            .values("avro-file.sync.interval", Long.toString(200))
            .values("listing.min-interval-seconds", "0")
            .environment("DC_")
            .build();

    static final SpecificationBuilder specificationBuilder = Specification.start("ENHETSREGISTERET-UPDATE", "Collect Enhetsregiseret Updates", "find-first-position")
            .configure(context()
                    .topic("enhetsregister-update")
                    .header("accept", "application/json")
                    .variable("baseURL", "https://data.brreg.no/enhetsregisteret/api")
                    //.variable("offsetDate", "2020-10-05T00:00:00.000Z")
                    .variable("offsetDate", "2021-03-11T00:00:00.000Z")
                    .variable("page", "0")
                    .variable("pageSize", "20")
            )
            .function(get("find-first-position")
                    .url("${baseURL}/oppdateringer/enheter?dato=${offsetDate}&page=0&size=1")
                    .validate(status().success(200))
                    .pipe(console())
                    .pipe(execute("loop-pages-until-done")
                            .inputVariable("fromStartPosition", eval("${cast.toLong(contentStream.lastOrInitialPosition(0)) + 1}"))
                            .inputVariable(
                                    "nextPosition",
                                    eval(jqpath("._embedded.oppdaterteEnheter[0]?.oppdateringsid"),
                                            "updateId", "${fromStartPosition == 1L ? (cast.toLong(updateId)) : fromStartPosition}")
                            )
                    )
            )
            // https://data.brreg.no/enhetsregisteret/api/oppdateringer/enheter?dato=2020-10-05T00:00:00.000Z
            .function(paginate("loop-pages-until-done")
                    .variable("fromPosition", "${nextPosition}")
                    .addPageContent("fromPosition")
                    .iterate(execute("fetch-page")
                            .requiredInput("nextPosition")
                    )
                    .prefetchThreshold(30)
                    .until(whenVariableIsNull("nextPosition"))
            )
            .function(get("fetch-page")
                    .url("${baseURL}/oppdateringer/enheter?oppdateringsid=${nextPosition}&page=${page}&&size=${pageSize}")
                    .validate(status().success(200))
                    .pipe(sequence(jqpath("._embedded.oppdaterteEnheter[]?"))
                            .expected(jqpath(".oppdateringsid"))
                    )
                    // last document response: {"_links":{"self":{"href":"https://data.brreg.no/enhetsregisteret/api/oppdateringer/enheter?oppdateringsid=10713464&page=0&size=20"}},"page":{"size":20,"totalElements":0,"totalPages":0,"number":0}}
                    .pipe(nextPage().output("nextPosition", eval(jqpath("._embedded | .oppdaterteEnheter[-1]? | .oppdateringsid"), "lastUpdateId", "${cast.toLong(lastUpdateId) + 1L}"))
                    )
                    .pipe(parallel(jqpath("._embedded.oppdaterteEnheter[]?"))
                            .variable("position", jqpath(".oppdateringsid"))
                            .pipe(addContent("${position}", "enhet"))
                            .pipe(publish("${position}"))
                    )
                    .returnVariables("nextPosition")
            );

    @Disabled
    @Test
    public void thatWorkerCollectEnhetsregisteret() throws InterruptedException {
        Worker.newBuilder()
                .configuration(configuration.asMap())
                .stopAtNumberOfIterations(5)
                .printConfiguration()
                .specification(specificationBuilder)
                .build()
                .run();
    }

    @Disabled
    @Test
    void consumeLocalRawdataStore() {
        try (RawdataClient client = ProviderConfigurator.configure(configuration.asMap(), configuration.evaluateToString("rawdata.client.provider"), RawdataClientInitializer.class)) {
            try (RawdataConsumer consumer = client.consumer(configuration.evaluateToString("rawdata.topic"))) {
                RawdataMessage message;
                while ((message = consumer.receive(1, TimeUnit.SECONDS)) != null) {
                    System.out.printf("position: %s%n--> %s%n", message.position(), new String(message.get("enhet")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Disabled
    @Test
    void thatGetFetchOnePage() {
        Worker.newBuilder()
                .configuration(Map.of(
                        "content.stream.connector", "rawdata",
                        "rawdata.client.provider", "memory")
                )
                .specification(Builders.get("onePage")
                        .url("https://data.brreg.no/enhetsregisteret/api/enheter/?page=1&size=20")
                        .validate(status().success(200))
                        .pipe(console())
                )
                .printConfiguration()
                .build()
                .run();
    }

    @Disabled
    @Test
    void thatEndpointReturnsSomeStuff() {
        // fetch page document
        Request request = Request.newRequestBuilder()
                .GET()
                .url("https://data.brreg.no/enhetsregisteret/api/enheter")
                .build();
        Response response = Client.newClient().send(request);
        JsonParser jsonParser = JsonParser.createJsonParser();
        JsonNode rootNode = jsonParser.fromJson(new String(response.body()), JsonNode.class);
        //System.out.printf("%s%n", jsonParser.toPrettyJSON(rootNode));

        // get page number
        {
            JqPath jqPathPageNumber = new JqPathBuilder(".page.number").build();
            QueryFeature queryPageNumber = Queries.from(jqPathPageNumber);
            String pageNumber = queryPageNumber.evaluateStringLiteral(rootNode);
            System.out.printf("page-number: %s%n", pageNumber);
        }

        // fetch page containing an array of enheter (units)
        // split the array, so we can construct all Jq-queries in order to tell the data collector how to do that
        {
            JqPath jqPathEnhetArray = new JqPathBuilder("._embedded.enheter[]").build();
            QueryFeature queryEnhetEntryArray = Queries.from(jqPathEnhetArray);
            List<?> enhetList = queryEnhetEntryArray.evaluateList(rootNode);
            System.out.printf("entries: %s%n", enhetList.size());
        }
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

        Files.writeString(targetPath.resolve("specs").resolve("enhetsregisteret-updates-spec.json"), specificationBuilder.serialize());
    }


}