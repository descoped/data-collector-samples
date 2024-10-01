package io.descoped.dc.samples.moveit.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.mizosoft.methanol.MultipartBodyPublisher;
import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.Builders;
import io.descoped.dc.api.handler.QueryFeature;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.api.node.JqPath;
import io.descoped.dc.api.util.JsonParser;
import io.descoped.dc.core.handler.Queries;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exploration test
 */
public class SimpleMoveItTest {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleMoveItTest.class);
    private static final Client client = Client.newClientBuilder().followRedirects(Client.Redirect.ALWAYS).version(Client.Version.HTTP_1_1).build();
    private static final JsonParser jsonParser = JsonParser.createJsonParser();
    private static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder().propertiesResource("application-ignore.properties").build();
    private static final byte[] AUTHORIZATION_DATA;

    static {
        AUTHORIZATION_DATA = String.format("grant_type=password&username=%s&password=%s", configuration.evaluateToString("moveIt.server.username"), configuration.evaluateToString("moveIt.server.password")).getBytes();
    }

    static Response GET(String url, String accessToken) {
        Request request = Request.newRequestBuilder()
                .url(configuration.evaluateToString("moveIt.server.url") + url)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        Response response = client.send(request);
        assertEquals(200, response.statusCode());
        return response;
    }

    static Response POST(String url, byte[] payload) {
        Request request = Request.newRequestBuilder()
                .url(configuration.evaluateToString("moveIt.server.url") + url)
                .POST(payload)
                .build();

        Response response = client.send(request);
        assertEquals(200, response.statusCode());
        return response;
    }

    static Response POST(String url, String accessToken, byte[] payload) {
        return POST(url, accessToken, payload, null);
    }

    static Response POST(String url, String accessToken, byte[] payload, String contentType) {
        Request.Builder requestBuilder = Request.newRequestBuilder()
                .url(configuration.evaluateToString("moveIt.server.url") + url)
                .header("Authorization", "Bearer " + accessToken);
        if (contentType != null) {
            requestBuilder.header("Content-Type", contentType);
        }
        Request request = requestBuilder.POST(payload).build();

        Response response = client.send(request);
        if (!(response.statusCode() == 200 || response.statusCode() == 201)) {
            throw new RuntimeException(String.format("Response error: [%s]: %s", response.statusCode(), new String(response.body())));
        }
        return response;
    }

    static Response POST(String url, String accessToken, Flow.Publisher<ByteBuffer> bodyPublisher, String boundary) {
        Request.Builder requestBuilder = Request.newRequestBuilder()
                .url(configuration.evaluateToString("moveIt.server.url") + url)
                .header("Authorization", "Bearer " + accessToken);
        requestBuilder
                .header("Content-Type", "multipart/form-data;boundary=" + boundary);
        requestBuilder
                .POST(bodyPublisher);

        Response response = client.send(requestBuilder.build());
        if (!(response.statusCode() == 200 || response.statusCode() == 201)) {
            throw new RuntimeException(String.format("Response error: [%s]: %s", response.statusCode(), new String(response.body())));
        }
        return response;
    }

    static String jq(byte[] data, String expression) {
        JqPath jqPathAccessToken = Builders.jqpath(expression).build();
        QueryFeature jqAccessToken = Queries.from(jqPathAccessToken);
        return jqAccessToken.evaluateStringLiteral(new String(data));
    }

    static String jq(JsonNode node, String expression) {
        JqPath jqPathAccessToken = Builders.jqpath(expression).build();
        QueryFeature jqAccessToken = Queries.from(jqPathAccessToken);
        return jqAccessToken.evaluateStringLiteral(node);
    }

    static JsonNode fromJson(byte[] data) {
        JsonParser jsonParser = JsonParser.createJsonParser();
        return jsonParser.fromJson(new String(data), JsonNode.class);
    }

    static String toPrettyJSON(byte[] data) {
        return jsonParser.toPrettyJSON(fromJson(data));
    }

    static String toPrettyJSON(JsonNode node) {
        return jsonParser.toPrettyJSON(node);
    }

    @Disabled
    @Test
    void downloadFlow() {
        Response responseToken = POST("/api/v1/token", AUTHORIZATION_DATA);
        String accessToken = jq(responseToken.body(), ".access_token");

        Response responseListFolders = GET("/api/v1/folders", accessToken);
        JsonNode folderNode = fromJson(responseListFolders.body());
        String folderId = jq(folderNode, ".items[] | select(.path == \"/Home/moveitapi\") | .id");
        assertEquals("672079599", folderId);

        Response responseListFiles = GET(String.format("/api/v1/folders/%s/files?sortDirection=asc&sortField=uploadStamp&page=1", folderId), accessToken);
        JsonNode jsonNode = fromJson(responseListFiles.body());
        LOG.trace("{}", toPrettyJSON(jsonNode));

        ArrayNode arrayNodeFileItems = (ArrayNode) jsonNode.get("items");
        for (int i = 0; i < arrayNodeFileItems.size(); i++) {
            JsonNode itemNode = arrayNodeFileItems.get(i);
            String fileId = jq(itemNode, ".id");
            String fileName = jq(itemNode, ".name");
            String fileNamePath = jq(itemNode, ".path");
            String fileUploadStamp = jq(itemNode, ".uploadStamp");
            //LOG.trace("id: {}", fileId);

            Response responseFileInfo = GET("/api/v1/files/" + fileId, accessToken);
            JsonNode fileNode = fromJson(responseFileInfo.body());
            //LOG.trace("{}", toPrettyJSON(fileNode));
            folderId = jq(fileNode, ".folderID");
            //LOG.trace("folderID: {}", folderId);

            Response download = GET(String.format("/api/v1/folders/%s/files/%s/download", folderId, fileId), accessToken);
            LOG.trace("download: {} -> {}", fileNamePath, new String(download.body()));

            Response downloadFileDetail = GET("/api/v1/files/" + fileId, accessToken);
            LOG.trace("download file details: {} -> {}", fileNamePath, new String(downloadFileDetail.body()));
            LOG.trace("");
        }

        Response downloadFolderDetail = GET("/api/v1/folders/" + 672079599, accessToken);
        LOG.trace("download file details: {} -> {}", "672079599", new String(downloadFolderDetail.body()));

        Response fileDetailsInFolder = GET(String.format("/api/v1/folders/%s/files/%s", "672079599", "674932103"), accessToken);  // foo3.txt
        LOG.trace("fileDetailsInFolde {} -> {}", "674932103", new String(fileDetailsInFolder.body()));
    }

    @Disabled
    @Test
    void uploadFlow() {
        Response responseToken = POST("/api/v1/token", AUTHORIZATION_DATA);
        String accessToken = jq(responseToken.body(), ".access_token");

        Response responseListFolders = GET("/api/v1/folders", accessToken);
        JsonNode jsonNode = fromJson(responseListFolders.body());
        String folderId = jq(jsonNode, ".items[] | select(.path == \"/Home/moveitapi\") | .id");
        assertEquals("672079599", folderId);

        MultipartBodyPublisher multipartBody = MultipartBodyPublisher.newBuilder()
                .formPart("file", "file13.txt", HttpRequest.BodyPublishers.ofByteArray("hello again".getBytes()))
                .build();

        Response responseUpload = POST(String.format("/api/v1/folders/%s/files", "672079599"), accessToken, multipartBody, multipartBody.boundary());
        LOG.trace("responseUpload: {}", new String(responseUpload.body()));
    }

}
