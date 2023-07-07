package com.superior.datatunnel.plugin.kafka;

import com.superior.datatunnel.api.ParamKey;
import com.superior.datatunnel.api.model.BaseSinkOption;
import com.superior.datatunnel.common.annotation.OptionDesc;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class KafkaDataTunnelSinkOption extends BaseSinkOption {

    @NotBlank(message = "topic can not blank")
    private String topic;

    @ParamKey("kafka.bootstrap.servers")
    @NotBlank(message = "kafka.bootstrap.servers can not blank")
    private String servers;

    @NotBlank(message = "checkpointLocation can not blank")
    @OptionDesc("checkpoint 存储位置")
    private String checkpointLocation;

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getServers() {
        return servers;
    }

    public void setServers(String servers) {
        this.servers = servers;
    }

    public String getCheckpointLocation() {
        return checkpointLocation;
    }

    public void setCheckpointLocation(String checkpointLocation) {
        this.checkpointLocation = checkpointLocation;
    }
}
