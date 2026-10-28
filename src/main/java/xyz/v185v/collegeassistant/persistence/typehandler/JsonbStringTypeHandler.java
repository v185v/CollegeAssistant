package xyz.v185v.collegeassistant.persistence.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

public final class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            String value,
            JdbcType jdbcType
    ) throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(value);
        statement.setObject(index, jsonb);
    }

    @Override
    public String getNullableResult(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getString(column);
    }

    @Override
    public String getNullableResult(ResultSet resultSet, int column) throws SQLException {
        return resultSet.getString(column);
    }

    @Override
    public String getNullableResult(CallableStatement statement, int column) throws SQLException {
        return statement.getString(column);
    }
}
