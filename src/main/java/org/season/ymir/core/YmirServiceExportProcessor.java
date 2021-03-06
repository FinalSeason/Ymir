package org.season.ymir.core;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.zookeeper.CreateMode;
import org.season.ymir.client.proxy.YmirClientProxyFactory;
import org.season.ymir.common.constant.CommonConstant;
import org.season.ymir.common.entity.ServiceBean;
import org.season.ymir.common.register.ServiceRegister;
import org.season.ymir.common.utils.YmirThreadFactory;
import org.season.ymir.common.utils.ZkPathUtils;
import org.season.ymir.core.annotation.YmirReference;
import org.season.ymir.core.annotation.YmirService;
import org.season.ymir.core.property.YmirConfigurationProperty;
import org.season.ymir.core.zookeeper.ZookeeperNodeChangeListener;
import org.season.ymir.server.YmirNettyServer;
import org.season.ymir.server.discovery.ZookeeperYmirServiceDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务导出
 *
 * @author KevinClair
 */
public class YmirServiceExportProcessor implements ApplicationListener<ContextRefreshedEvent> {

    private final AtomicBoolean flag = new AtomicBoolean(false);

    private static final Logger logger = LoggerFactory.getLogger(YmirServiceExportProcessor.class);

    private ExecutorService executorService;
    private ServiceRegister serviceRegister;
    private YmirNettyServer nettyServer;
    private YmirClientProxyFactory proxyFactory;
    private CuratorFramework zkClient;
    private YmirConfigurationProperty property;

    public YmirServiceExportProcessor(ServiceRegister serviceRegister, YmirNettyServer nettyServer, YmirClientProxyFactory proxyFactory, CuratorFramework zkClient, YmirConfigurationProperty property) {
        this.executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new YmirThreadFactory("service-export-"));
        this.serviceRegister = serviceRegister;
        this.nettyServer = nettyServer;
        this.proxyFactory = proxyFactory;
        this.zkClient = zkClient;
        this.property = property;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        if (!flag.compareAndSet(false, true)) {
            return;
        }

        ApplicationContext applicationContext = contextRefreshedEvent.getApplicationContext();
        executorService.execute(() -> handler(applicationContext));
    }

    private void handler(ApplicationContext applicationContext) {
        try {
            // 解析地址信息
            String host = InetAddress.getLocalHost().getHostAddress();
            String address = host + ":" + property.getPort();
            // 注册服务
            registerService(applicationContext, address);
            // 引用服务
            referenceService(applicationContext, address);
        } catch (Exception e) {
            logger.error("Ymir deploy error, error message:{}", ExceptionUtils.getStackTrace(e));
        }
    }

    private void registerService(ApplicationContext context, String address) {
        Map<String, Object> beans = context.getBeansWithAnnotation(YmirService.class);

        if (beans.size() > 0) {
            for (Object obj : beans.values()) {
                try {
                    Class<?> clazz = obj.getClass();
                    ServiceBean serviceBean;
                    YmirService service = clazz.getAnnotation(YmirService.class);
                    // 如果不需要注册，跳过
                    if (!service.register()){
                        continue;
                    }
                    if (StringUtils.isNotBlank(service.value())) {
                        serviceBean = new ServiceBean(service.value(), clazz.getName(), service.protocol(), address, service.weight(), service.group(), service.version());
                    } else {
                        Class<?>[] interfaces = clazz.getInterfaces();
                        if (interfaces.length > 1) {
                            logger.error("Only one interface class can be inherited, class {} is illegal!", obj.getClass().getName());
                            continue;
                        }
                        Class<?> superInterface = interfaces[0];
                        serviceBean = new ServiceBean(superInterface.getName(), clazz.getName(), service.protocol(), address, service.weight(), service.group(), service.version());
                    }
                    // register bean;
                    serviceRegister.registerBean(serviceBean);
                    logger.info("Service {} register success", obj.getClass().getName());
                } catch (Exception e) {
                    logger.error("Service {} register error, error message: {}", obj.getClass().getName(), ExceptionUtils.getStackTrace(e));
                }
            }
            // netty服务端启动
            nettyServer.start();
        }

    }

    private void referenceService(ApplicationContext context, String address) {
        String[] names = context.getBeanDefinitionNames();
        List<String> serviceList = new ArrayList<>();
        for (String name : names) {
            Class<?> clazz = context.getType(name);
            if (Objects.isNull(clazz)) {
                continue;
            }

            Field[] declaredFields = clazz.getDeclaredFields();
            for (Field field : declaredFields) {
                YmirReference reference = field.getAnnotation(YmirReference.class);
                if (Objects.isNull(reference)) {
                    continue;
                }

                Class<?> fieldClass = field.getType();
                Object object = context.getBean(name);
                field.setAccessible(true);
                try {
                    // 设置代理对象
                    field.set(object, proxyFactory.getProxy(fieldClass));
                } catch (IllegalAccessException e) {
                    logger.error("Service reference error, exception:{}", ExceptionUtils.getStackTrace(e));
                }
                serviceList.add(fieldClass.getName());
            }
        }
        // 注册子节点监听
        if (proxyFactory.getServiceDiscovery() instanceof ZookeeperYmirServiceDiscovery) {
            ZookeeperYmirServiceDiscovery serverDiscovery = (ZookeeperYmirServiceDiscovery) proxyFactory.getServiceDiscovery();
            serviceList.forEach(name -> {
                try {
                    // 节点监听
                    String servicePath = CommonConstant.PATH_DELIMITER + name +CommonConstant.PATH_DELIMITER + CommonConstant.ZK_SERVICE_PROVIDER_PATH;
                    final PathChildrenCache childrenCache = new PathChildrenCache(zkClient, servicePath, true);
                    childrenCache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
                    childrenCache.getListenable().addListener(new ZookeeperNodeChangeListener(serverDiscovery));

                    String consumerNode = CommonConstant.PATH_DELIMITER + name +CommonConstant.PATH_DELIMITER + CommonConstant.ZK_SERVICE_PROVIDER_PATH;
                    String registerZNodePath = ZkPathUtils.buildUriPath(consumerNode, address);
                    // 写入consumer节点
                    zkClient.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(registerZNodePath);

                } catch (Exception e) {
                    logger.error("Zookeeper node add  listener error, message:{}", ExceptionUtils.getStackTrace(e));
                }
            });
            logger.info("subscribe service zk node successfully");
        }
    }
}
