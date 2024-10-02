package com.superior.datatunnel.plugin.kafka;

import com.superior.datatunnel.api.model.BaseSinkOption;
import com.superior.datatunnel.common.annotation.OptionDesc;
import com.superior.datatunnel.common.enums.OutputMode;
import javax.validation.constraints.NotBlank;

public class DatalakeDatatunnelSinkOption extends BaseSinkOption {

    private String databaseName;

    @OptionDesc("等同 databaseName, databaseName 和 schemaName 只需设置一个")
    private String schemaName;

    @NotBlank(message = "tableName can not blank")
    private String tableName;

    @OptionDesc("写入模式, 仅支持：append 和 complete")
    private OutputMode outputMode = OutputMode.APPEND;

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public @NotBlank(message = "tableName can not blank") String getTableName() {
        return tableName;
    }

    public void setTableName(@NotBlank(message = "tableName can not blank") String tableName) {
        this.tableName = tableName;
    }

    public OutputMode getOutputMode() {
        return outputMode;
    }

    public void setOutputMode(OutputMode outputMode) {
        this.outputMode = outputMode;
    }
}
