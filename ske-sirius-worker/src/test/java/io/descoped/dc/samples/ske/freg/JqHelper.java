package io.descoped.dc.samples.ske.freg;

import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.dc.api.Builders;
import io.descoped.dc.core.handler.Queries;

import java.util.List;

public class JqHelper {

    public static List<JsonNode> queryList(Object data, String expression) {
        return (List<JsonNode>) Queries.from(Builders.jqpath(expression).build()).evaluateList(data);
    }

    public static Object queryObject(Object data, String expression) {
        return Queries.from(Builders.jqpath(expression).build()).evaluateObject(data);
    }

    public static String queryStringLiteral(Object data, String expression) {
        return Queries.from(Builders.jqpath(expression).build()).evaluateStringLiteral(data);
    }

}
