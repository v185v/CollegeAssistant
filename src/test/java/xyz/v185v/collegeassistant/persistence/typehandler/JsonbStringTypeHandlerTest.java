package xyz.v185v.collegeassistant.persistence.typehandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

class JsonbStringTypeHandlerTest {

    private final JsonbStringTypeHandler handler = new JsonbStringTypeHandler();

    @Test
    void bindsJsonAsPostgresJsonbWithoutChangingItsText() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        String json = "{\"answer\":42,\"nested\":{\"enabled\":true}}";

        handler.setNonNullParameter(statement, 3, json, JdbcType.OTHER);

        verify(statement).setObject(org.mockito.ArgumentMatchers.eq(3), value.capture());
        assertThat(value.getValue()).isInstanceOfSatisfying(PGobject.class, jsonb -> {
            assertThat(jsonb.getType()).isEqualTo("jsonb");
            assertThat(jsonb.getValue()).isEqualTo(json);
        });
    }

    @Test
    void delegatesEveryNullableReadOverloadToGetString() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        CallableStatement callableStatement = mock(CallableStatement.class);
        when(resultSet.getString("payload")).thenReturn("{\"source\":\"name\"}");
        when(resultSet.getString(2)).thenReturn("{\"source\":\"index\"}");
        when(callableStatement.getString(4)).thenReturn("{\"source\":\"callable\"}");

        assertThat(handler.getNullableResult(resultSet, "payload"))
                .isEqualTo("{\"source\":\"name\"}");
        assertThat(handler.getNullableResult(resultSet, 2))
                .isEqualTo("{\"source\":\"index\"}");
        assertThat(handler.getNullableResult(callableStatement, 4))
                .isEqualTo("{\"source\":\"callable\"}");

        verify(resultSet).getString("payload");
        verify(resultSet).getString(2);
        verify(callableStatement).getString(4);
    }
}
