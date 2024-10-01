package io.descoped.dc.samples.moveit.test;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.Specification;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.node.builder.SpecificationBuilder;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.core.executor.Worker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.descoped.dc.api.Builders.addContent;
import static io.descoped.dc.api.Builders.bodyPublisher;
import static io.descoped.dc.api.Builders.context;
import static io.descoped.dc.api.Builders.eval;
import static io.descoped.dc.api.Builders.execute;
import static io.descoped.dc.api.Builders.get;
import static io.descoped.dc.api.Builders.jqpath;
import static io.descoped.dc.api.Builders.nextPage;
import static io.descoped.dc.api.Builders.paginate;
import static io.descoped.dc.api.Builders.parallel;
import static io.descoped.dc.api.Builders.post;
import static io.descoped.dc.api.Builders.publish;
import static io.descoped.dc.api.Builders.sequence;
import static io.descoped.dc.api.Builders.status;
import static io.descoped.dc.api.Builders.whenExpressionIsTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test cases demonstrates a generic way to collect files from MoveIt Automation Server.
 * <p>
 * The MoveIt REST API resources is a handle oriented API. There are some limitations to be aware of:
 * <p>
 * - A Folder is identified by a reference-id and the API doesn't provide any specific method that refers to a folder.
 * Henceforth, you need to list all folders from root and filter for folder-name to obtain the reference-id (function: find-root-folder)
 * <p>
 * - A Folder contains Files that must be sorted ascending and by updateTime in order to have a predictable forward cursor
 * <p>
 * - There is no API that allows pagination from a specific file, thus we have to maintain a page-position.
 * This may cause re-fetching of already downloaded files when the collection resumes. (function: loop and page)
 * The preferred (unsupported) strategy would be to specify a file as a starting point and let pagination continue from a given position.
 * <p>
 * MoveIt REST API: https://docs.ipswitch.com/MOVEit/Transfer2018/API/rest/
 * <p>
 * Pre-requisite:
 * <p>
 * Copy 'src/test/resources/application-sample.properties' to 'src/test/resources/application-ignore.properties' and
 * configure your username and password.
 */
public class MoveItDownloadTest {

    static final Logger LOG = LoggerFactory.getLogger(MoveItDownloadTest.class);

    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .propertiesResource("application-ignore.properties")
            .values("content.stream.connector", "rawdata")
            .values("rawdata.client.provider", "memory")
            .values("data.collector.worker.threads", "20")
            .values("data.collector.http.version", Client.Version.HTTP_1_1.name())
            .values("data.collector.http.followRedirects", Client.Redirect.ALWAYS.name())
            .build();

    SpecificationBuilder createSpecification(String serverURL) {
        return Specification.start("MOVEIT-TEST", "MoveIt Test", "authorize")
                // global configuration
                .configure(context()
                        .topic("moveit-test")
                        .variable("baseURL", serverURL)
                        .variable("rootFolder", "/Home/moveitapi")
                        .variable("pageSize", "5")
                        .variable("nextPage", "${cast.toLong(contentStream.lastOrInitialPagePosition(1))}") // resume page position
                )
                // authenticate and get access token
                .function(post("authorize")
                        .url("${baseURL}/api/v1/token")
                        .data(bodyPublisher()
                                .plainText("grant_type=password&username=${ENV.'moveIt.server.username'}&password=${ENV.'moveIt.server.password'}")
                        )
                        .validate(status().success(200))
                        .pipe(execute("find-root-folder")
                                .inputVariable("accessToken", jqpath(".access_token"))
                        )
                )
                // resolve root folderId
                .function(get("find-root-folder")
                        .header("Authorization", "Bearer ${accessToken}")
                        .url("${baseURL}/api/v1/folders")
                        .validate(status().success(200))
                        .pipe(execute("loop")
                                .requiredInput("accessToken")
                                .inputVariable("folderId", jqpath(".items[] | select(.path == \"${rootFolder}\") | .id"))
                        )
                )
                // pagination loop
                .function(paginate("loop")
                        .variable("fromPage", "${nextPage}") // page position cursor
                        .addPageContent("fromPage") // persist page position
                        .iterate(execute("page")
                                .requiredInput("accessToken")
                                .requiredInput("folderId")
                        )
                        .prefetchThreshold(8)
                        .until(whenExpressionIsTrue("${nextPage > totalPages}")) // completion condition
                )
                // get page
                .function(get("page")
                        .header("Authorization", "Bearer ${accessToken}")
                        .url("${baseURL}/api/v1/folders/${folderId}/files?page=${nextPage}&perPage=${pageSize}&sortDirection=asc&sortField=uploadStamp")
                        .validate(status().success(200))
                        .pipe(sequence(jqpath(".items[]"))
                                .expected(jqpath(".id"))
                        )
                        .pipe(nextPage()
                                .output("nextPage",
                                        eval(jqpath(".paging.page"), "lastPage", "${cast.toLong(lastPage) + 1}") // evaluate next page position
                                )
                                .output("totalPages", jqpath(".paging.totalPages"))
                        )
                        .pipe(parallel(jqpath(".items[]"))
                                .variable("position", jqpath(".id"))
                                .pipe(addContent("${position}", "entry"))
                                .pipe(execute("download-file")
                                        .requiredInput("folderId")
                                        .inputVariable("name", jqpath(".name"))
                                        .inputVariable("path", jqpath(".path"))
                                        .inputVariable("uploadStamp", jqpath(".uploadStamp"))
                                )
                                .pipe(publish("${position}")) // publish buffered data to rawdata storage
                        )
                        .returnVariables("nextPage", "totalPages") // return next page position cursor
                )
                // download file
                .function(get("download-file")
                        .header("Authorization", "Bearer ${accessToken}")
                        .url("${baseURL}/api/v1/folders/${folderId}/files/${position}/download")
                        .validate(status().success(200))
                        .pipe(addContent("${position}", "file"))
                );
    }

    @Disabled
    @Test
    void consumeMoveItFiles() {
        Worker.newBuilder()
                .configuration(configuration.asMap())
                .specification(createSpecification(configuration.evaluateToString("moveIt.server.url")))
                .build()
                .run();
    }

    @Disabled
    @Test
    void validateDeserializer() {
        SpecificationBuilder actualSpecificationBuilder = createSpecification(configuration.evaluateToString("moveIt.server.url"));
        String serialized = actualSpecificationBuilder.serialize();
        SpecificationBuilder expectedSpecificationBuilder = Specification.deserialize(serialized);
        assertEquals(actualSpecificationBuilder, expectedSpecificationBuilder);
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

        Files.writeString(targetPath.resolve("specs").resolve("moveit-test-spec.json"), createSpecification(configuration.evaluateToString("moveIt.server.url")).serialize());
    }

}
