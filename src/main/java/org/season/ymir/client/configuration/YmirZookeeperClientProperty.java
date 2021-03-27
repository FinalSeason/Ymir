package org.season.ymir.client.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TODO
 *
 * @author KevinClair
 */
@ConfigurationProperties(prefix = "ymir.zookeeper")
public class YmirZookeeperClientProperty {

    private String url;

    private Integer sessionTimeout = 6000;

    private Integer connectionTimeout = 6000;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Integer getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(Integer sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public Integer getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Integer connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }
}