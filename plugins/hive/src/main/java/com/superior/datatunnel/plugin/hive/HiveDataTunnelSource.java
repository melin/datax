package com.superior.datatunnel.plugin.hive;

import com.superior.datatunnel.api.DataTunnelContext;
import com.superior.datatunnel.api.DataTunnelSource;
import com.superior.datatunnel.api.model.DataTunnelSourceOption;
import org.apache.commons.lang3.StringUtils;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * @author melin 2021/7/27 11:06 上午
 */
public class HiveDataTunnelSource implements DataTunnelSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(HiveDataTunnelSource.class);

    @Override
    public Dataset<Row> read(DataTunnelContext context) throws IOException {
        HiveDataTunnelSourceOption sourceOption = (HiveDataTunnelSourceOption) context.getSourceOption();

        String databaseName = sourceOption.getDatabaseName();
        String tableName = sourceOption.getTableName();
        String[] columns = sourceOption.getColumns();
        String condition = sourceOption.getCondition();

        String table = tableName;
        if (StringUtils.isNotBlank(databaseName)) {
            table = databaseName + "." + tableName;
        }

        StringBuilder sqlBuilder = new StringBuilder("select ");
        sqlBuilder.append(StringUtils.join(columns, ",")).append(" from ").append(table);

        if (StringUtils.isNotBlank(condition)) {
            sqlBuilder.append(" where ").append(condition);
        }

        String sql = sqlBuilder.toString();
        LOGGER.info("exec sql: {}", sql);

        return context.getSparkSession().sql(sql);
    }

    @Override
    public Class<? extends DataTunnelSourceOption> getOptionClass() {
        return HiveDataTunnelSourceOption.class;
    }

    @Override
    public boolean supportCte() {
        return true;
    }
}
