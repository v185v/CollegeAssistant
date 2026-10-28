package xyz.v185v.collegeassistant.admin.model;

import java.util.Arrays;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 将一个 JSON 字符串直接绑定为可擦除字符数组。 */
public final class SecretCharsDeserializer extends ValueDeserializer<char[]> {

    @Override
    public char[] deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws JacksonException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (char[]) context.handleUnexpectedToken(char[].class, parser);
        }
        char[] source = parser.getStringCharacters();
        int offset = parser.getStringOffset();
        return Arrays.copyOfRange(source, offset, offset + parser.getStringLength());
    }
}
