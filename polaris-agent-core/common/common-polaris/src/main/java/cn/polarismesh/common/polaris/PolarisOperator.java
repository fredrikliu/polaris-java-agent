/*
 * Tencent is pleased to support the open source community by making Polaris available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package cn.polarismesh.common.polaris;

import cn.polarismesh.agent.common.tools.ClassUtils;
import cn.polarismesh.agent.common.tools.ReflectionUtils;
import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PolarisOperator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolarisOperator.class);

    private static final String[] PARSER_METHOD_NAMES = new String[]{PolarisReflectConst.METHOD_GET_HOST,
            PolarisReflectConst.METHOD_GET_PORT, PolarisReflectConst.METHOD_GET_PROTOCOL,
            PolarisReflectConst.METHOD_GET_METADATA};

    private ContextClassLoaderExecuteTemplate clazzLoaderTemplate;

    private final Map<String, Method> methods = new HashMap<>();

    private final PolarisConfig polarisConfig = new PolarisConfig();

    private final Object lock = new Object();

    private final AtomicBoolean inited = new AtomicBoolean(false);

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

    private final Map<InstanceIdentifier, ScheduledFuture<?>> scheduledFutures = new HashMap<>();

    private boolean initReflectMethods() {
        ClassLoader clazzLoader = Thread.currentThread().getContextClassLoader();
        Class<?> clazz = null;
        try {
            clazz = ClassUtils.forName(PolarisReflectConst.CLAZZ_FACADE, clazzLoader);
        } catch (Exception e) {
            LOGGER.error("fail to resolve clazz {}, classloader {}", PolarisReflectConst.CLAZZ_FACADE,
                    ((URLClassLoader) clazzLoader).getURLs(), e);
            return false;
        }
        Method initMethod = ClassUtils.getMethod(clazz, PolarisReflectConst.METHOD_INIT, String.class);
        methods.put(PolarisReflectConst.METHOD_INIT, initMethod);

        Method registerMethod = ClassUtils.getMethod(clazz, PolarisReflectConst.METHOD_REGISTER, String.class,
                String.class, String.class, int.class, String.class, String.class, Map.class, int.class, String.class);
        methods.put(PolarisReflectConst.METHOD_REGISTER, registerMethod);

        Method deregisterMethod = ClassUtils.getMethod(clazz, PolarisReflectConst.METHOD_DEREGISTER, String.class,
                String.class, String.class, int.class, String.class);
        methods.put(PolarisReflectConst.METHOD_DEREGISTER, deregisterMethod);

        Method heartbeatMethod = ClassUtils.getMethod(clazz, PolarisReflectConst.METHOD_HEARTBEAT, String.class,
                String.class, String.class, int.class, String.class);
        methods.put(PolarisReflectConst.METHOD_HEARTBEAT, heartbeatMethod);

        Method updateMethod = ClassUtils
                .getMethod(clazz, PolarisReflectConst.METHOD_UPDATE_SERVICE_CALL_RESULT, String.class,
                        String.class, String.class, int.class, long.class, boolean.class, int.class);
        methods.put(PolarisReflectConst.METHOD_UPDATE_SERVICE_CALL_RESULT, updateMethod);

        Method getInstancesMethod = ClassUtils
                .getMethod(clazz, PolarisReflectConst.METHOD_GET_INSTANCES, String.class, String.class, Map.class,
                        Map.class);
        methods.put(PolarisReflectConst.METHOD_GET_INSTANCES, getInstancesMethod);

        Method deleteMethod = ClassUtils.getMethod(clazz, PolarisReflectConst.METHOD_DESTROY);
        methods.put(PolarisReflectConst.METHOD_DESTROY, deleteMethod);

        Class<?> parserClazz = null;
        try {
            parserClazz = ClassUtils.forName(PolarisReflectConst.CLAZZ_INSTANCE_PARSER, clazzLoader);
        } catch (Exception e) {
            LOGGER.error("fail to resolve clazz {}", PolarisReflectConst.CLAZZ_INSTANCE_PARSER, e);
            return false;
        }
        for (String parserMethod : PARSER_METHOD_NAMES) {
            Method method = ClassUtils.getMethod(parserClazz, parserMethod, Object.class);
            methods.put(parserMethod, method);
        }
        return true;
    }

    public void init() {
        if (inited.get()) {
            return;
        }
        synchronized (lock) {
            if (inited.get()) {
                return;
            }
            ClassLoader clazzLoader = generatePolarisLoader();
            clazzLoaderTemplate = new ContextClassLoaderExecuteTemplate(clazzLoader);
            clazzLoaderTemplate.execute("init polaris context", new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    boolean initMethodsResult = initReflectMethods();
                    if (!initMethodsResult) {
                        return null;
                    }
                    String configStr = PolarisReflectConst.CONFIG_TEMPLATE
                            .replace(PolarisReflectConst.PLACE_HOLDER_ADDRESS, polarisConfig.getRegistryAddress());
                    Method initMethod = methods.get(PolarisReflectConst.METHOD_INIT);
                    ReflectionUtils.invokeMethod(initMethod, null, configStr);
                    inited.set(true);
                    return null;
                }
            });
        }
    }

    public void destroy() {
        synchronized (lock) {
            if (!inited.get()) {
                return;
            }
            heartbeatExecutor.shutdown();
            clazzLoaderTemplate.execute("init polaris context", new Callable<Object>() {

                @Override
                public Object call() throws Exception {
                    Method method = methods.get(PolarisReflectConst.METHOD_DESTROY);
                    ReflectionUtils.invokeMethod(method, null);
                    inited.set(false);
                    return null;
                }
            });
        }
    }

    private ClassLoader generatePolarisLoader() {
        String libPath = polarisConfig.getAgentDir() + File.separator + "lib";
        String[] polarisAllFiles = (new File(libPath)).list(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith("polaris-all");
            }
        });
        if (null == polarisAllFiles || polarisAllFiles.length == 0) {
            return null;
        }
        String polarisAllPath =
                polarisConfig.getAgentDir() + File.separator + "lib" + File.separator + polarisAllFiles[0];
        System.out.println("polaris-all path is " + polarisAllPath);
        URL polarisAllUrl = null;
        try {
            polarisAllUrl = new URL("file:" + polarisAllPath);
        } catch (MalformedURLException e) {
            LOGGER.error("fail to convert {} to url", polarisAllPath, e);
            return null;
        }
        return new URLClassLoader(new URL[]{polarisAllUrl}, Object.class.getClassLoader());
    }

    /**
     * 服务注册
     */
    public void register(String service, String host, int port, String protocol, Map<String, String> metadata) {
        init();
        if (!inited.get()) {
            LOGGER.error("fail to register address {}:{} to {}, polaris init failed", host, port, service);
            return;
        }
        clazzLoaderTemplate.execute("register", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                String namespace = polarisConfig.getNamespace();
                int ttl = polarisConfig.getTtl();
                String token = polarisConfig.getToken();
                String version = polarisConfig.getVersion();
                Method registerMethod = methods.get(PolarisReflectConst.METHOD_REGISTER);
                boolean result = (boolean) ReflectionUtils
                        .invokeMethod(registerMethod, null, namespace,
                                service, host, port, protocol, version, metadata, ttl,
                                token);
                if (result) {
                    // 注册完成后执行心跳上报
                    LOGGER.info("heartbeat task start, ttl is {}", ttl);
                    Runnable heartbeatTask = new Runnable() {
                        @Override
                        public void run() {
                            heartbeat(service, host, port);
                        }
                    };
                    ScheduledFuture<?> future = heartbeatExecutor.scheduleWithFixedDelay(heartbeatTask, ttl, ttl,
                            TimeUnit.SECONDS);
                    scheduledFutures.put(new InstanceIdentifier(service, host, port), future);
                }
                return null;
            }
        });
    }

    public void deregister(String service, String host, int port) {
        init();
        if (!inited.get()) {
            LOGGER.error("fail to deregister address {}:{} to {}, polaris init failed", host, port, service);
            return;
        }
        clazzLoaderTemplate.execute("deregister", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                ScheduledFuture<?> future = scheduledFutures
                        .get(new InstanceIdentifier(service, host, port));
                if (null != future) {
                    future.cancel(true);
                }
                String namespace = polarisConfig.getNamespace();
                String token = polarisConfig.getToken();
                Method deregisterMethod = methods.get(PolarisReflectConst.METHOD_DEREGISTER);
                ReflectionUtils.invokeMethod(deregisterMethod, null, namespace, service, host, port, token);
                return null;
            }
        });
    }

    private void heartbeat(String service, String host, int port) {
        clazzLoaderTemplate.execute("deregister", new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                String namespace = polarisConfig.getNamespace();
                String token = polarisConfig.getToken();
                Method heartbeatMethod = methods.get(PolarisReflectConst.METHOD_HEARTBEAT);
                ReflectionUtils.invokeMethod(heartbeatMethod, null, namespace, service, host, port, token);
                return null;
            }
        });
    }

    /**
     * 调用CONSUMER_API获取实例信息
     *
     * @param service 服务的service
     * @return Polaris选择的Instance对象
     */
    public List<?> getAvailableInstances(String service, Map<String, String> srcLabels) {
        init();
        if (!inited.get()) {
            LOGGER.error("fail to getInstances {}, polaris init failed", service);
            return null;
        }
        return (List<?>) clazzLoaderTemplate.execute("getInstances", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                // get available instances
                String namespace = polarisConfig.getNamespace();
                Method getInstancesMethod = methods.get(PolarisReflectConst.METHOD_GET_INSTANCES);
                return ReflectionUtils.invokeMethod(
                        getInstancesMethod, null, namespace, service, srcLabels, null);
            }
        });
    }

    /**
     * 调用CONSUMER_API上报服务请求结果
     *
     * @param delay 本次服务调用延迟，单位ms
     */
    public void reportInvokeResult(String service, String host, int port, long delay, boolean success,
            int code) {
        init();
        if (!inited.get()) {
            LOGGER.error("fail to getInstances {}, polaris init failed", service);
            return;
        }
        clazzLoaderTemplate.execute("reportInvokeResult", new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                String namespace = polarisConfig.getNamespace();
                Method updateMethod = methods.get(PolarisReflectConst.METHOD_UPDATE_SERVICE_CALL_RESULT);
                ReflectionUtils.invokeMethod(
                        updateMethod, null, namespace, service, host, port, delay, success, code);
                return null;
            }
        });
    }

    public String getHost(Object instance) {
        return (String) clazzLoaderTemplate.execute("getHost", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Method method = methods.get(PolarisReflectConst.METHOD_GET_HOST);
                return ReflectionUtils.invokeMethod(
                        method, null, instance);
            }
        });
    }

    public int getPort(Object instance) {
        return (int) clazzLoaderTemplate.execute("getPort", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Method method = methods.get(PolarisReflectConst.METHOD_GET_PORT);
                return ReflectionUtils.invokeMethod(
                        method, null, instance);
            }
        });
    }

    public String getProtocol(Object instance) {
        return (String) clazzLoaderTemplate.execute("getProtocol", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Method method = methods.get(PolarisReflectConst.METHOD_GET_PROTOCOL);
                return ReflectionUtils.invokeMethod(
                        method, null, instance);
            }
        });
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getMetadata(Object instance) {
        return (Map<String, String>) clazzLoaderTemplate.execute("getMetadata", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Method method = methods.get(PolarisReflectConst.METHOD_GET_METADATA);
                return ReflectionUtils.invokeMethod(
                        method, null, instance);
            }
        });
    }

    private static class InstanceIdentifier {

        private final String service;

        private final String host;

        private final int port;

        public InstanceIdentifier(String service, String host, int port) {
            this.service = service;
            this.host = host;
            this.port = port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof InstanceIdentifier)) {
                return false;
            }
            InstanceIdentifier that = (InstanceIdentifier) o;
            return port == that.port &&
                    Objects.equals(service, that.service) &&
                    Objects.equals(host, that.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(service, host, port);
        }
    }

}