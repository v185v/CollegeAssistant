package xyz.v185v.collegeassistant.document;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 元数据的类型校验与递归不可变复制工具。 */
final class JsonMetadataSupport {

    private JsonMetadataSupport() {
    }

    /**
     * 校验元数据仅包含可序列化的 JSON 值，并返回递归不可变副本。
     *
     * @param metadata 原始元数据
     * @return 不可变元数据
     */
    static Map<String, Object> immutableCopy(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("Metadata keys must not be null");
            }
            copy.put(key, immutableValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Float number) {
            requireFinite(number.doubleValue());
            return number;
        }
        if (value instanceof Double number) {
            requireFinite(number);
            return number;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("Nested metadata keys must be strings");
                }
                nested.put(stringKey, immutableValue(nestedValue));
            });
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> nested = new ArrayList<>(collection.size());
            collection.forEach(element -> nested.add(immutableValue(element)));
            return Collections.unmodifiableList(nested);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> nested = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                nested.add(immutableValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(nested);
        }
        throw new IllegalArgumentException(
                "Unsupported metadata value type: " + value.getClass().getName());
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Metadata numbers must be finite");
        }
    }
}
