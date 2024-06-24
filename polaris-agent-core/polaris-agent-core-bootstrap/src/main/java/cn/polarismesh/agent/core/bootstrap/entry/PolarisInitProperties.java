package cn.polarismesh.agent.core.bootstrap.entry;

public class PolarisInitProperties {

    public void initialize() {

        // ban nacos as sct currently does not support double registration
//        System.setProperty("spring.cloud.nacos.config.enabled", "false");
        System.setProperty("spring.cloud.nacos.discovery.enabled", "false");

    }
}
