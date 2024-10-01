package io.descoped.dc.samples.moveit.test;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.Builders;
import io.descoped.dc.api.Specification;
import io.descoped.dc.api.context.ExecutionContext;
import io.descoped.dc.api.handler.QueryFeature;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.api.node.JqPath;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.core.executor.Worker;
import io.descoped.dc.core.handler.Queries;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.descoped.dc.api.Builders.addContent;
import static io.descoped.dc.api.Builders.bodyPublisher;
import static io.descoped.dc.api.Builders.context;
import static io.descoped.dc.api.Builders.delete;
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

/**
 * This is a file population test case
 * <p>
 * Pre-requisite:
 * <p>
 * Copy 'src/test/resources/application-sample.properties' to 'src/test/resources/application-ignore.properties' and
 * configure your username and password.
 */
public class MoveItDeleteTest {

    static final Logger LOG = LoggerFactory.getLogger(MoveItDeleteTest.class);

    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .propertiesResource("application-ignore.properties")
            .values("content.stream.connector", "rawdata")
            .values("rawdata.client.provider", "memory")
            .values("data.collector.worker.threads", "20")
            .values("data.collector.http.version", Client.Version.HTTP_1_1.name())
            .values("data.collector.http.followRedirects", Client.Redirect.ALWAYS.name())
            .build();

    @Disabled
    @Test
    void purgeAndUploadMoveItFiles() throws IOException {
        purgeMoveItFiles();
        uploadMoveItFiles();
    }

    void purgeMoveItFiles() {
        Worker.newBuilder()
                .configuration(configuration.asMap())
                .specification(Specification.start("MOVEIT-PURGE-TEST", "MoveIt PURGE Test", "authorize")
                        // global configuration
                        .configure(context()
                                .topic("moveit-test-delete")
                                .variable("baseURL", configuration.evaluateToString("moveIt.server.url"))
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
                                .until(whenExpressionIsTrue("${totalPages == 0}")) // completion condition
                        )
                        // get page
                        .function(get("page")
                                .header("Authorization", "Bearer ${accessToken}")
                                .url("${baseURL}/api/v1/folders/${folderId}/files?page=1&perPage=${pageSize}&sortDirection=desc&sortField=uploadStamp")
                                .validate(status().success(200))
                                .pipe(sequence(jqpath(".items[]"))
                                        .expected(jqpath(".id"))
                                )
                                .pipe(nextPage()
                                        .output("nextPage",
                                                eval(jqpath(".paging.page"), "lastPage", "${cast.toLong(lastPage) + 1}") // evaluate next page position
                                        )
                                        .output("page", eval(jqpath(".paging.page"), "testPage", "${empty testPage ? 1 : testPage}"))
                                        .output("totalItems", jqpath(".paging.totalItems"))
                                        .output("totalPages", jqpath(".paging.totalPages"))
                                )
                                .pipe(parallel(jqpath(".items[]"))
                                        .variable("position", jqpath(".id"))
                                        .pipe(addContent("${position}", "entry"))
                                        .pipe(execute("delete-file"))
                                        .pipe(publish("${position}")) // publish buffered data to rawdata storage
                                )
                                .returnVariables("nextPage", "page", "totalItems", "totalPages") // return next page position cursor
                        )
                        // download file
                        .function(delete("delete-file")
                                .header("Authorization", "Bearer ${accessToken}")
                                .url("${baseURL}/api/v1/files/${position}")
                                .validate(status().success(204, 404)) // 404 is a hack to suppress page tail errors. The iteration should be reversed.
                                .pipe(addContent("${position}", "receipt"))
                        ))
                .build()
                .run();
    }


    void uploadMoveItFiles() throws IOException {
        Map<String, String> configMap = new LinkedHashMap<>(configuration.asMap());
        String accessToken = authorizeAndGetAccessToken();
        configMap.put("accessToken", accessToken);

        String folderId = getFolderId(configMap);
        configMap.put("folderId", folderId);

        Path testDataPath = CommonUtils.currentPath().resolve("src/test/resources/tempFiles");
        if (!testDataPath.toFile().exists()) {
            LOG.trace("p: {}", testDataPath);
            throw new RuntimeException("Temp resource directory not found!");
        }

        Set<Path> uploadFiles = new TreeSet<>();
        Files.walkFileTree(testDataPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                uploadFiles.add(file);
                return FileVisitResult.CONTINUE;
            }
        });

        for (Path file : uploadFiles) {
            LOG.trace("{}", file);
            byte[] content = Files.readAllBytes(file);
            Worker worker = createUploadWorker(configMap, "file", file.getFileName().toString(), content);
            worker.run();
        }
    }

    private String authorizeAndGetAccessToken() {
        ExecutionContext authorizeContext = Worker.newBuilder()
                .specification(post("authorize")
                        .url("${ENV.'moveIt.server.url'}/api/v1/token")
                        .data(bodyPublisher()
                                .plainText("grant_type=password&username=${ENV.'moveIt.server.username'}&password=${ENV.'moveIt.server.password'}")
                        )
                        .validate(status().success(200))
                )
                .configuration(configuration.asMap())
                .build()
                .run();

        JqPath jqPathAccessToken = Builders.jqpath(".access_token").build();
        QueryFeature queryAccessToken = Queries.from(jqPathAccessToken);
        return queryAccessToken.evaluateStringLiteral(authorizeContext.state(Response.class).body());
    }

    private String getFolderId(Map<String, String> configMap) {
        ExecutionContext rootContext = Worker.newBuilder()
                .specification(get("find-root-folder")
                        .header("Authorization", "Bearer ${ENV.accessToken}")
                        .url("${ENV.'moveIt.server.url'}/api/v1/folders")
                        .validate(status().success(200))
                )
                .configuration(configMap)
                .build()
                .run();

        JqPath jqPathItems = Builders.jqpath(".items[] | select(.path == \"/Home/moveitapi\") | .id").build();
        QueryFeature queryItems = Queries.from(jqPathItems);
        return queryItems.evaluateStringLiteral(rootContext.state(Response.class).body());
    }

    Worker createUploadWorker(Map<String, String> configMap, String name, String filename, byte[] data) {
        return Worker.newBuilder()
                .specification(post("upload-file")
                        .header("Authorization", "Bearer ${ENV.accessToken}")
                        .url("${ENV.'moveIt.server.url'}/api/v1/folders/${ENV.folderId}/files")
                        .data(bodyPublisher().formPart(name, filename, data))
                        .validate(status().success(201))
                )
                .configuration(configMap)
                .build();

    }
}
