/*******************************************************************************
 * Copyright (c) 2015 IBM, Inc. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: Jan S. Rellermeyer, IBM Research - initial API and implementation
 ******************************************************************************/
package ch.ethz.iks.r_osgi.transport.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.java_websocket.WebSocket;
import org.java_websocket.client.DefaultSSLWebSocketClientFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.util.Base64;
import org.osgi.service.log.LogService;

import ch.ethz.iks.r_osgi.Remoting;
import ch.ethz.iks.r_osgi.URI;
import ch.ethz.iks.r_osgi.channels.ChannelEndpoint;
import ch.ethz.iks.r_osgi.channels.NetworkChannel;
import ch.ethz.iks.r_osgi.channels.NetworkChannelFactory;
import ch.ethz.iks.r_osgi.messages.RemoteOSGiMessage;

public class HttpChannelFactory implements NetworkChannelFactory {

	public static final String HTTP_PORT_PROPERTY = "ch.ethz.iks.r_osgi.transport.http.port";

	public static final int DEFAULT_HTTP_PORT = 80;

	public static final String HTTPS_PORT_PROPERTY = "ch.ethz.iks.r_osgi.transport.https.port";

	public static final int DEFAULT_HTTPS_PORT = 443;

	static final String PROTOCOL_HTTP = "http"; //$NON-NLS-1$
	static final String PROTOCOL_HTTPS = "https"; //$NON-NLS-1$

	private Remoting remoting;

	private final boolean listen;
	private final int port;
	private final boolean secure;

	private WebSocketListener server;

	HttpChannelFactory(final int port, final boolean secure) {
		this(true, port, secure);
	}

	HttpChannelFactory(boolean listen, final int port, final boolean secure) {
		this.listen = listen;
		this.port = port;
		this.secure = secure;
	}
	
	synchronized void logError(String message, Throwable exception) {
		Activator activator = Activator.getDefault();
		if (activator != null) {
			LogService logService = activator.getLogService();
			if (logService != null) 
				logService.log(LogService.LOG_ERROR, message, exception);
		}
	}
	
	public NetworkChannel getConnection(final ChannelEndpoint endpoint,
			final URI endpointURI) throws IOException {
		return new HttpChannel(endpoint, endpointURI);
	}

	public void activate(final Remoting remoting) throws IOException {
		this.remoting = remoting;
		if (listen) {
			this.server = new WebSocketListener(port);
			this.server.start();
		}
	}

	public void deactivate(final Remoting remoting) throws IOException {
		this.remoting = null;
		if (listen) {
			try {
				this.server.stop();
			} catch (InterruptedException e) {
				logError("Error in HttpChannelFactory.deactivate", e);
			}
			this.server = null;
		}
	}

	public int getListeningPort(final String protocol) {
		return port;
	}

	private class HttpChannel implements NetworkChannel {

		final WebSocket socket;
		final URI remoteAddress;
		final URI localAddress;
		ChannelEndpoint endpoint;

		public HttpChannel(final WebSocket socket) {
			this.socket = socket;
			final String protocol = secure ? PROTOCOL_HTTPS : PROTOCOL_HTTP;
			this.remoteAddress = uriFromSocketAddress(protocol,
					socket.getRemoteSocketAddress());
			this.localAddress = uriFromSocketAddress(protocol,
					socket.getLocalSocketAddress());
		}

		public HttpChannel(final ChannelEndpoint endpoint, final URI endpointURI)
				throws IOException {
			this.endpoint = endpoint;
			this.remoteAddress = endpointURI;
			final WebSocketClient client = new WebSocketClient(
					java.net.URI.create(endpointURI.toString())) {

				@Override
				public void onClose(int arg0, String arg1, boolean arg2) {
					closeSocket();
				}

				@Override
				public void onError(Exception error) {
					logError("HttpChannel.WebSocketClient.onError",error);
				}

				@Override
				public void onMessage(String message) {
					processMessage(message);
				}

				@Override
				public void onOpen(ServerHandshake server) {

				}

			};
			try {
				if (secure) {
					client.setWebSocketFactory(new DefaultSSLWebSocketClientFactory(
							SSLContext.getDefault()));
				}
				client.connectBlocking();
			} catch (final Exception e) {
				logError("Exception in setting client web socket factory",e);
				throw new IOException("Could not connect", e);
			}
			this.socket = client.getConnection();
			this.localAddress = URI.create(client.getURI().toString());
		}

		public String getProtocol() {
			return remoteAddress.getScheme();
		}

		public URI getRemoteAddress() {
			return remoteAddress;
		}

		public URI getLocalAddress() {
			return localAddress;
		}

		public void bind(ChannelEndpoint endpoint) {
			this.endpoint = endpoint;
		}

		public synchronized void close() throws IOException {
			closeSocket();
		}

		synchronized void closeSocket() {
			if (socket.isOpen()) 
				socket.close(CloseFrame.NORMAL);
		}
		
		public synchronized void sendMessage(final RemoteOSGiMessage message)
				throws IOException {
			if (socket.isOpen()) {
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				final ObjectOutputStream out = new ObjectOutputStream(bytes);
				message.send(out);
				out.close();
				socket.send(Base64.encodeBytes(bytes.toByteArray(), Base64.GZIP));
			}
		}

		private URI uriFromSocketAddress(final String protocol,
				final InetSocketAddress addr) {
			return URI.create(protocol + "://" + addr.getHostName() + ":"
					+ addr.getPort());
		}

		public synchronized void processMessage(final String message) {
			if (socket.isOpen()) {
				try {
					final ObjectInputStream in = new ObjectInputStream(
							new ByteArrayInputStream(Base64.decode(message)));
					final RemoteOSGiMessage msg = RemoteOSGiMessage.parse(in);
					in.close();
					endpoint.receivedMessage(msg);
				} catch (Exception e) {
					logError("HttpChannel.processMessage message="+message,e);
				}
			}
		}
	}

	private class WebSocketListener extends WebSocketServer {

		private Map<WebSocket, HttpChannel> channels = new HashMap<WebSocket, HttpChannel>();;

		protected WebSocketListener(final int port) throws IOException {
			super(new InetSocketAddress(port));

			if (secure) {
				try {
					this.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(
							SSLContext.getDefault()));
				} catch (final NoSuchAlgorithmException e) {
					logError("WebSocketListener<init> exception in setWebSocketFactory",e);
					throw new IOException("Could not create SSL context", e);
				}
			}
		}

		public void onClose(final WebSocket socket, final int code,
				final String reason, final boolean remote) {
			channels.remove(socket);
		}

		public void onError(WebSocket socket, Exception error) {
			logError("HttpChannel.onError socket="+socket,error);
		}

		public void onMessage(WebSocket socket, String message) {
			final HttpChannel channel = channels.get(socket);
			if (channel != null) {
				channel.processMessage(message);
			}
		}

		public void onOpen(WebSocket socket, ClientHandshake handshake) {
			final HttpChannel channel = new HttpChannel(socket);
			remoting.createEndpoint(channel);
			channels.put(socket, channel);
		}

	}

}
