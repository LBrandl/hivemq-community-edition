/*
 * Copyright 2019 dc-square GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hivemq.bootstrap.netty.initializer;

import com.hivemq.annotations.NotNull;
import com.hivemq.bootstrap.netty.ChannelDependencies;
import com.hivemq.configuration.service.entity.Tls;
import com.hivemq.configuration.service.entity.TlsWebsocketListener;
import com.hivemq.logging.EventLog;
import com.hivemq.mqtt.handler.connect.NoTlsHandshakeIdleHandler;
import com.hivemq.security.exception.SslException;
import com.hivemq.security.ssl.SslFactory;
import com.hivemq.security.ssl.SslInitializer;
import com.hivemq.security.ssl.SslParameterHandler;
import com.hivemq.websocket.WebSocketInitializer;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

import static com.hivemq.bootstrap.netty.ChannelHandlerNames.NEW_CONNECTION_IDLE_HANDLER;
import static com.hivemq.bootstrap.netty.ChannelHandlerNames.NO_TLS_HANDSHAKE_IDLE_EVENT_HANDLER;

/**
 * @author Christoph Schäbel
 */
public class TlsWebsocketChannelInitializer extends AbstractChannelInitializer {

    @NotNull
    private final TlsWebsocketListener tlsWebsocketListener;

    @NotNull
    private final SslFactory sslFactory;

    @NotNull
    private final EventLog eventLog;

    @NotNull
    private final SslParameterHandler sslParameterHandler;

    public TlsWebsocketChannelInitializer(@NotNull final ChannelDependencies channelDependencies,
                                          @NotNull final TlsWebsocketListener tlsWebsocketListener,
                                          @NotNull final SslFactory sslFactory,
                                          @NotNull final EventLog eventLog) {

        super(channelDependencies, tlsWebsocketListener, eventLog);
        this.tlsWebsocketListener = tlsWebsocketListener;
        this.sslFactory = sslFactory;
        this.eventLog = eventLog;
        this.sslParameterHandler = channelDependencies.getSslParameterHandler();
    }

    @Override
    protected void addNoConnectIdleHandler(@NotNull final Channel ch) {
        // No connect idle handler are added, as soon as the TLS handshake is done.
    }

    protected void addNoConnectIdleHandlerAfterTlsHandshake(@NotNull final Channel ch) {
        super.addNoConnectIdleHandler(ch);
    }

    @Override
    protected void addSpecialHandlers(@NotNull final Channel ch) throws SslException {
        final int handshakeTimeout = tlsWebsocketListener.getTls().getHandshakeTimeout();

        final IdleStateHandler idleStateHandler = new IdleStateHandler(handshakeTimeout, 0, 0, TimeUnit.MILLISECONDS);
        final NoTlsHandshakeIdleHandler noTlsHandshakeIdleHandler = new NoTlsHandshakeIdleHandler(eventLog);
        if (handshakeTimeout > 0) {
            ch.pipeline().addLast(NEW_CONNECTION_IDLE_HANDLER, idleStateHandler);
            ch.pipeline().addLast(NO_TLS_HANDSHAKE_IDLE_EVENT_HANDLER, noTlsHandshakeIdleHandler);
        }

        final Tls tls = tlsWebsocketListener.getTls();
        final SslHandler sslHandler = sslFactory.getSslHandler(ch, tls);
        sslHandler.handshakeFuture().addListener(future -> {
            if (handshakeTimeout > 0) {
                ch.pipeline().remove(idleStateHandler);
                ch.pipeline().remove(noTlsHandshakeIdleHandler);
            }
            addNoConnectIdleHandlerAfterTlsHandshake(ch);
        });

        new SslInitializer(sslHandler, tls, eventLog, sslParameterHandler).addHandlers(ch);

        new WebSocketInitializer(tlsWebsocketListener).addHandlers(ch);
    }
}
