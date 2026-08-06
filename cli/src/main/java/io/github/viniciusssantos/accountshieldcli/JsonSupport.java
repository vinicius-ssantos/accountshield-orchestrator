package io.github.viniciusssantos.accountshieldcli;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One shared, pretty-printing Jackson mapper for every command's JSON input/output. */
public final class JsonSupport {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private JsonSupport() {
    }

    public static String toPrettyJson(Object value) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }
}
