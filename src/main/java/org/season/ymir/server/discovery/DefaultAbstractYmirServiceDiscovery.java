package org.season.ymir.server.discovery;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.season.ymir.common.entity.ServiceBean;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 服务发现本地缓存
 *
 * @author KevinClair
 **/
public abstract class DefaultAbstractYmirServiceDiscovery implements YmirServiceDiscovery {

    // 本地缓存
    private static final Cache<String, List<ServiceBean>> SERVER_MAP = Caffeine.newBuilder()
            .initialCapacity(10)
            .maximumSize(50000)
            .build();

    @Override
    public void remove(String serviceName) {
        SERVER_MAP.invalidate(serviceName);
    }

    @Override
    public boolean isEmpty(String serviceName) {
        return CollectionUtils.isEmpty(SERVER_MAP.getIfPresent(serviceName));
    }

    @Override
    public List<ServiceBean> get(String serviceName) {
        return SERVER_MAP.getIfPresent(serviceName);
    }

    @Override
    public void put(String serviceName, List<ServiceBean> serviceList) {
        SERVER_MAP.put(serviceName, serviceList);
    }

    @Override
    public List<ServiceBean> findServiceList(String name) throws Exception {
        return SERVER_MAP.getIfPresent(name);
    }
}
