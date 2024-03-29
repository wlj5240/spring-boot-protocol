package com.github.netty.springboot.client;

import com.github.netty.annotation.Protocol;
import com.github.netty.core.util.AnnotationMethodToParameterNamesFunction;
import com.github.netty.core.util.ReflectUtil;
import com.github.netty.core.util.StringUtil;
import com.github.netty.protocol.nrpc.RpcClient;
import com.github.netty.protocol.nrpc.RpcServerChannelHandler;
import com.github.netty.protocol.nrpc.exception.RpcConnectException;
import com.github.netty.protocol.nrpc.exception.RpcException;
import com.github.netty.springboot.NettyProperties;
import io.netty.util.concurrent.FastThreadLocal;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RPC client (thread safe)
 * @author wangzihao
 */
public class NettyRpcClientProxy implements InvocationHandler {
    private static final Map<InetSocketAddress,RpcClient> CLIENT_MAP = new HashMap<>(5);

    private String serviceId;
    private String serviceName;
    private Class<?> interfaceClass;
    private NettyProperties properties;
    private NettyRpcLoadBalanced loadBalanced;
    private FastThreadLocal<DefaultNettyRpcRequest> requestThreadLocal = new FastThreadLocal<DefaultNettyRpcRequest>(){
        @Override
        protected DefaultNettyRpcRequest initialValue() throws Exception {
            return new DefaultNettyRpcRequest();
        }
    };

    NettyRpcClientProxy(String serviceId, String serviceName, Class interfaceClass, NettyProperties properties, NettyRpcLoadBalanced loadBalanced) {
        this.serviceId = serviceId;
        this.interfaceClass = interfaceClass;
        this.properties = properties;
        this.loadBalanced = loadBalanced;
        this.serviceName = StringUtil.isEmpty(serviceName)? getServiceName(interfaceClass) : serviceName;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }
        if ("toString".equals(methodName) && parameterTypes.length == 0) {
            return this.toString();
        }
        if ("hashCode".equals(methodName) && parameterTypes.length == 0) {
            return this.hashCode();
        }
        if ("equals".equals(methodName) && parameterTypes.length == 1) {
            return this.equals(args[0]);
        }

        DefaultNettyRpcRequest request = requestThreadLocal.get();
        request.setMethod(method);
        request.setArgs(args);

        InetSocketAddress address = chooseAddress(request);
        RpcClient rpcClient = getClient(address);
        InvocationHandler handler = rpcClient.getRpcInstance(serviceName);
        if(handler == null){
            List<Class<?extends Annotation>> parameterAnnotationClasses = getParameterAnnotationClasses();
            handler = rpcClient.newRpcInstance(interfaceClass, properties.getNrpc().getClientTimeout(),
                    serviceName, new AnnotationMethodToParameterNamesFunction(parameterAnnotationClasses));
        }
        return handler.invoke(proxy,method,args);
    }

    protected List<Class<?extends Annotation>> getParameterAnnotationClasses(){
        return Arrays.asList(
                Protocol.RpcParam.class,RequestParam.class,RequestBody.class, RequestHeader.class,
                PathVariable.class,CookieValue.class, RequestPart.class);
    }

    protected String getServiceName(Class objectType){
        String serviceName = RpcServerChannelHandler.getServiceName(objectType);
        if(StringUtil.isNotEmpty(serviceName)) {
            return serviceName;
        }

        RequestMapping requestMapping = ReflectUtil.findAnnotation(objectType,RequestMapping.class);
        if(requestMapping != null) {
            //获取服务名
            serviceName = requestMapping.name();
            String[] values = requestMapping.value();
            String[] paths = requestMapping.path();
            if(StringUtil.isEmpty(serviceName) && values.length > 0){
                serviceName = values[0];
            }
            if(StringUtil.isEmpty(serviceName) && paths.length > 0){
                serviceName = paths[0];
            }
            if(StringUtil.isNotEmpty(serviceName)) {
                return serviceName;
            }
        }

        serviceName = RpcServerChannelHandler.generateServiceName(objectType);
        return serviceName;
    }

    /**
     * Get the RPC client (from the current thread, if not, create it automatically)
     * @return
     */
    private RpcClient getClient(InetSocketAddress address){
        RpcClient rpcClient = CLIENT_MAP.get(address);
        if(rpcClient == null) {
            synchronized (CLIENT_MAP){
                rpcClient = CLIENT_MAP.get(address);
                if(rpcClient == null) {
                    NettyProperties.Nrpc nrpc = properties.getNrpc();
                    rpcClient = new RpcClient(address);
                    rpcClient.setIoThreadCount(nrpc.getClientIoThreads());
                    rpcClient.setIoRatio(nrpc.getClientIoRatio());
                    rpcClient.run();
                    rpcClient.connect().syncUninterruptibly();
                    if (nrpc.isClientAutoReconnect()) {
                        rpcClient.enableAutoReconnect(nrpc.getClientHeartInterval(), TimeUnit.SECONDS,
                                null, nrpc.isClientEnableHeartLog());
                    }
                    CLIENT_MAP.put(address, rpcClient);
                }
            }
        }
        return rpcClient;
    }

    /**
     * Ping creates a new client and destroys it
     * @throws RpcException RpcException
     */
    public void pingOnceAfterDestroy() throws RpcException {
        InetSocketAddress address = chooseAddress(requestThreadLocal.get());
        RpcClient rpcClient = new RpcClient("Ping-",address);
        rpcClient.setIoThreadCount(1);
        rpcClient.run();
        rpcClient.connect().syncUninterruptibly();
        byte[] response = rpcClient.getRpcCommandService().ping();
        rpcClient.stop();
        requestThreadLocal.remove();
    }

    private InetSocketAddress chooseAddress(DefaultNettyRpcRequest request){
        InetSocketAddress address;
        try {
            address = loadBalanced.chooseAddress(request);
            request.args = null;
            request.method = null;
        }catch (Exception e){
            throw new RpcConnectException("Failed to select client address",e);
        }
        if (address == null) {
            throw new NullPointerException("Failed to select the client address and get null");
        }
        return address;
    }

    /**
     * Default nett request (parameter [args array] can be modified)
     */
    private class DefaultNettyRpcRequest implements NettyRpcRequest {
        private Method method;
        private Object[] args;

        void setMethod(Method method) {
            this.method = method;
        }

        void setArgs(Object[] args) {
            this.args = args;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object[] getArgs() {
            return args;
        }

        @Override
        public String getServiceId() {
            return serviceId;
        }

        @Override
        public String getServiceName() {
            return serviceName;
        }

        @Override
        public NettyProperties getNettyProperties() {
            return properties;
        }

        @Override
        public Class getInterfaceClass() {
            return interfaceClass;
        }
    }
}
