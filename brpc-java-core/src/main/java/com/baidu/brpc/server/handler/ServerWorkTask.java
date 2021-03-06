/*
 * Copyright (c) 2018 Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.baidu.brpc.server.handler;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.lang.reflect.InvocationTargetException;

import com.baidu.brpc.Controller;
import com.baidu.brpc.exceptions.RpcException;
import com.baidu.brpc.interceptor.Interceptor;
import com.baidu.brpc.interceptor.JoinPoint;
import com.baidu.brpc.protocol.Protocol;
import com.baidu.brpc.protocol.Request;
import com.baidu.brpc.protocol.Response;
import com.baidu.brpc.protocol.http.BrpcHttpResponseEncoder;
import com.baidu.brpc.protocol.http.HttpRpcProtocol;
import com.baidu.brpc.server.RpcServer;
import com.baidu.brpc.server.ServerJoinPoint;
import com.baidu.brpc.server.ServerStatus;
import com.baidu.brpc.utils.CollectionUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Setter
@Getter
@AllArgsConstructor
public class ServerWorkTask implements Runnable {
    private RpcServer rpcServer;
    private Object packet;
    private Protocol protocol;
    private ChannelHandlerContext ctx;

    @Override
    public void run() {
        if (protocol instanceof HttpRpcProtocol) {
            FullHttpRequest fullHttpRequest = (FullHttpRequest) packet;
            try {
                if (fullHttpRequest.uri().equals("/favicon.ico")) {
                    FullHttpResponse fullHttpResponse =
                            new DefaultFullHttpResponse(HTTP_1_1, OK);
                    fullHttpResponse.headers().set(CONTENT_LENGTH, 0);
                    if (HttpUtil.isKeepAlive(fullHttpRequest)) {
                        fullHttpResponse.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    }
                    BrpcHttpResponseEncoder encoder = new BrpcHttpResponseEncoder();
                    ByteBuf responseByteBuf = encoder.encode(fullHttpResponse);
                    ChannelFuture f = ctx.channel().writeAndFlush(responseByteBuf);
                    if (!HttpUtil.isKeepAlive(fullHttpRequest)) {
                        f.addListener(ChannelFutureListener.CLOSE);
                    }
                    return;
                } else if (fullHttpRequest.uri().equals("/") || fullHttpRequest.uri().equals("/status")) {
                    ServerStatus serverStatus = rpcServer.getServerStatus();

                    byte[] statusBytes = serverStatus.toString().getBytes("UTF-8");
                    FullHttpResponse fullHttpResponse =
                            new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(statusBytes));
                    fullHttpResponse.headers().set(CONTENT_TYPE, "text/html");
                    fullHttpResponse.headers().set(CONTENT_LENGTH, fullHttpResponse.content().readableBytes());
                    if (HttpUtil.isKeepAlive(fullHttpRequest)) {
                        fullHttpResponse.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
                    }
                    BrpcHttpResponseEncoder encoder = new BrpcHttpResponseEncoder();
                    ByteBuf responseByteBuf = encoder.encode(fullHttpResponse);
                    ChannelFuture f = ctx.channel().writeAndFlush(responseByteBuf);
                    if (!HttpUtil.isKeepAlive(fullHttpRequest)) {
                        f.addListener(ChannelFutureListener.CLOSE);
                    }
                    return;
                }
            } catch (Exception ex) {
                log.warn("send status info response failed:", ex);
            }
        }

        Request request = null;
        Controller controller = null;
        Response response = protocol.getResponse();
        try {
            request = protocol.decodeRequest(packet);
        } catch (Exception ex) {
            // throw request
            log.warn("decode request failed:", ex);
            response.setException(new RpcException(ex));
        }

        if (request != null) {
            request.setChannel(ctx.channel());
            if (request.getRpcMethodInfo().isIncludeController()
                    || request.getBinaryAttachment() != null
                    || request.getKvAttachment() != null) {
                controller = new Controller();
                if (request.getBinaryAttachment() != null) {
                    controller.setRequestBinaryAttachment(request.getBinaryAttachment());
                }
                if (request.getKvAttachment() != null) {
                    controller.setRequestKvAttachment(request.getKvAttachment());
                }
                controller.setRemoteAddress(ctx.channel().remoteAddress());
            }

            response.setLogId(request.getLogId());
            response.setCompressType(request.getCompressType());
            response.setException(request.getException());
            response.setRpcMethodInfo(request.getRpcMethodInfo());

            // 处理请求前拦截
            if (response.getException() == null
                    && CollectionUtils.isNotEmpty(rpcServer.getInterceptors())) {
                for (Interceptor interceptor : rpcServer.getInterceptors()) {
                    if (!interceptor.handleRequest(request)) {
                        response.setException(new RpcException(
                                RpcException.FORBIDDEN_EXCEPTION, "intercepted"));
                        break;
                    }
                }
            }
        }

        if (response.getException() == null) {
            try {
                JoinPoint joinPoint = new ServerJoinPoint(controller, request, rpcServer);
                Object result = joinPoint.proceed();
                response.setResult(result);
                if (controller != null && controller.getResponseBinaryAttachment() != null
                        && controller.getResponseBinaryAttachment().isReadable()) {
                    response.setBinaryAttachment(controller.getResponseBinaryAttachment());
                }
            } catch (InvocationTargetException ex) {
                Throwable targetException = ex.getTargetException();
                if (targetException == null) {
                    targetException = ex;
                }
                String errorMsg = String.format("invoke method failed, msg=%s", targetException.getMessage());
                log.warn(errorMsg, targetException);
                response.setException(targetException);
            } catch (Exception ex) {
                String errorMsg = String.format("invoke method failed, msg=%s", ex.getMessage());
                log.warn(errorMsg, ex);
                response.setException(ex);
            }
        }

        // 处理响应后拦截
        if (CollectionUtils.isNotEmpty(rpcServer.getInterceptors())) {
            int length = rpcServer.getInterceptors().size();
            for (int i = length - 1; i >= 0; i--) {
                rpcServer.getInterceptors().get(i).handleResponse(response);
            }
        }

        try {
            ByteBuf byteBuf = protocol.encodeResponse(request, response);
            ChannelFuture channelFuture = ctx.channel().writeAndFlush(byteBuf);
            protocol.afterResponseSent(request, response, channelFuture);
        } catch (Exception ex) {
            log.warn("send response failed:", ex);
        }
    }
}
