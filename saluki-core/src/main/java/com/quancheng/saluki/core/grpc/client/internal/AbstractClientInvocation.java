/*
 * Copyright (c) 2016, Quancheng-ec.com All right reserved. This software is the confidential and
 * proprietary information of Quancheng-ec.com ("Confidential Information"). You shall not disclose
 * such Confidential Information and shall use it only in accordance with the terms of the license
 * agreement you entered into with Quancheng-ec.com.
 */
package com.quancheng.saluki.core.grpc.client.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.quancheng.saluki.core.common.Constants;
import com.quancheng.saluki.core.common.GrpcURL;
import com.quancheng.saluki.core.common.RpcContext;
import com.quancheng.saluki.core.grpc.client.GrpcRequest;
import com.quancheng.saluki.core.grpc.client.failover.GrpcClientCall;
import com.quancheng.saluki.core.grpc.client.hystrix.GrpcBlockingUnaryCommand;
import com.quancheng.saluki.core.grpc.client.hystrix.GrpcFutureUnaryCommand;
import com.quancheng.saluki.core.grpc.client.hystrix.GrpcHystrixCommand;
import com.quancheng.saluki.core.grpc.exception.RpcValidatorException;
import com.quancheng.saluki.core.grpc.service.ClientServerMonitor;
import com.quancheng.saluki.core.utils.CollectionUtils;
import com.quancheng.saluki.core.utils.ReflectUtils;
import com.quancheng.saluki.serializer.ProtobufValidator;

import io.grpc.Channel;

/**
 * @author shimingliu 2016年12月14日 下午9:38:34
 * @version AbstractClientInvocation.java, v 0.0.1 2016年12月14日 下午9:38:34 shimingliu
 */
public abstract class AbstractClientInvocation implements InvocationHandler {

  private static final Logger log = LoggerFactory.getLogger(AbstractClientInvocation.class);

  private final ConcurrentMap<String, AtomicInteger> concurrents = Maps.newConcurrentMap();

  private final ClientServerMonitor monitor;

  private final RequestValidator requstValidator;

  protected abstract GrpcRequest buildGrpcRequest(Method method, Object[] args);


  public AbstractClientInvocation(GrpcURL refUrl) {
    Long monitorinterval = refUrl.getParameter("monitorinterval", 60L);
    monitor = ClientServerMonitor.newClientServerMonitor(monitorinterval);
    requstValidator = RequestValidator.newRequestValidator();
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (ReflectUtils.isToStringMethod(method)) {
      return AbstractClientInvocation.this.toString();
    }
    GrpcRequest request = this.buildGrpcRequest(method, args);
    requstValidator.doValidate(request);
    String serviceName = request.getServiceName();
    String methodName = request.getMethodRequest().getMethodName();
    Channel channel = request.getChannel();
    GrpcURL refUrl = request.getRefUrl();
    Integer retryOption = this.buildRetryOption(methodName, refUrl);
    Boolean isEnableFallback = this.buildFallbackOption(methodName, refUrl);
    GrpcClientCall clientCall = GrpcClientCall.create(channel, retryOption, refUrl);

    try {
      this.calculateConcurrent(serviceName, methodName).incrementAndGet();
      AtomicInteger concurrent = this.calculateConcurrent(serviceName, methodName);
      GrpcHystrixCommand hystrixCommand = null;
      switch (request.getMethodRequest().getCallType()) {
        case Constants.RPCTYPE_ASYNC:
          hystrixCommand = new GrpcFutureUnaryCommand(serviceName, methodName, isEnableFallback);
          break;
        case Constants.RPCTYPE_BLOCKING:
          hystrixCommand = new GrpcBlockingUnaryCommand(serviceName, methodName, isEnableFallback);
          break;
        default:
          hystrixCommand = new GrpcFutureUnaryCommand(serviceName, methodName, isEnableFallback);
          break;
      }
      hystrixCommand.setClientCall(clientCall);
      hystrixCommand.setRequest(request);
      hystrixCommand.setConcurrent(concurrent);
      hystrixCommand.setClientServerMonitor(monitor);
      return hystrixCommand.execute();
    } finally {
      Object remote = clientCall.getAffinity().get(GrpcClientCall.GRPC_CURRENT_ADDR_KEY);
      log.info(String.format("Service: %s  Method: %s  RemoteAddress: %s", serviceName, methodName,
          String.valueOf(remote)));
      request.returnChannel(channel);
      this.calculateConcurrent(serviceName, methodName).decrementAndGet();
    }
  }


  private Boolean buildFallbackOption(String methodName, GrpcURL refUrl) {
    Boolean isEnableFallback = refUrl.getParameter(Constants.GRPC_FALLBACK_KEY, Boolean.FALSE);
    String[] methodNames =
        StringUtils.split(refUrl.getParameter(Constants.FALLBACK_METHODS_KEY), ",");
    if (methodNames != null && methodNames.length > 0) {
      return isEnableFallback && Arrays.asList(methodNames).contains(methodName);
    } else {
      return isEnableFallback;
    }
  }

  private Integer buildRetryOption(String methodName, GrpcURL refUrl) {
    Integer retries = refUrl.getParameter((Constants.METHOD_RETRY_KEY), 0);
    String[] methodNames = StringUtils.split(refUrl.getParameter(Constants.RETRY_METHODS_KEY), ",");
    if (methodNames != null && methodNames.length > 0) {
      if (Arrays.asList(methodNames).contains(methodName)) {
        return retries;
      } else {
        return Integer.valueOf(0);
      }
    } else {
      return Integer.valueOf(0);
    }
  }

  private AtomicInteger calculateConcurrent(String servcieName, String methodName) {
    String key = servcieName + ":" + methodName;
    AtomicInteger concurrent = concurrents.get(key);
    if (concurrent == null) {
      concurrents.putIfAbsent(key, new AtomicInteger());
      concurrent = concurrents.get(key);
    }
    return concurrent;
  }

  @SuppressWarnings("rawtypes")
  private final static class RequestValidator {

    private Validator validator;

    private static final Object LOCK = new Object();

    private static RequestValidator requestValidator;

    private RequestValidator() {
      validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static RequestValidator newRequestValidator() {
      synchronized (LOCK) {
        if (requestValidator != null) {
          return requestValidator;
        } else {
          return new RequestValidator();
        }
      }
    }

    public void doValidate(final GrpcRequest request) throws ClassNotFoundException {
      if (!request.getMethodRequest().getArg().getClass()
          .isAnnotationPresent(ProtobufValidator.class)) {
        return;
      }
      Set<Class> validatorGroups = new HashSet<>();
      String validatorGroupStr = request.getRefUrl().getParameter(Constants.VALIDATOR_GROUPS);
      if (StringUtils.isNotEmpty(validatorGroupStr)) {
        String[] splitGroups = validatorGroupStr.split(";");
        for (String splitGroup : splitGroups) {
          validatorGroups.add(Class.forName(splitGroup));
        }
      }
      Optional<Set<Class>> optional = RpcContext.getContext().getHoldenGroups();
      if (optional.isPresent()) {
        validatorGroups = optional.get();
      }
      Set<ConstraintViolation<Object>> violations = validator.validate(
          request.getMethodRequest().getArg(), (Class[]) validatorGroups.toArray(new Class[0]));
      if (CollectionUtils.isNotEmpty(violations)) {
        StringBuffer validateMsg = new StringBuffer();
        for (ConstraintViolation<Object> constraintViolation : violations) {
          validateMsg.append(String.format("parameter[%s] message[%s] ",
              constraintViolation.getPropertyPath(), constraintViolation.getMessage()));
        }

        if (validateMsg.length() > 0) {
          throw new RpcValidatorException(validateMsg.toString());
        }
      }
    }
  }
}
