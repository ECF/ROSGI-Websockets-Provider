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
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.SSLContext;

import org.java_websocket.WebSocket;
import org.java_websocket.client.DefaultSSLWebSocketClientFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.FramedataImpl1;
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

	public static final String CONNECT_TIMEOUT_PROPERTY = "ch.ethz.iks.r_osgi.transport.https.connectTimeout";

	public static final int PING_TIMEOUT_PROPERTY = Integer
			.valueOf(System.getProperty("ch.ethz.iks.r_osgi.transport.http.keepAlive", "15000")).intValue();

	// XXX currently not used...see ROSGiWebSocketClient constructor
	public static final int CONNECT_TIMEOUT = Integer.valueOf(System.getProperty(CONNECT_TIMEOUT_PROPERTY, "10000"))
			.intValue();

	public static final boolean USE_BYTE_BUFFER = new Boolean(
			System.getProperty("ch.ethz.iks.r_osgi.transport.http.useByteBuffer", "false")).booleanValue();

	static final String PROTOCOL_HTTP = "http"; //$NON-NLS-1$
	static final String PROTOCOL_HTTPS = "https"; //$NON-NLS-1$

	private Remoting remoting;

	private final boolean listen;
	private final int port;
	private final boolean secure;

	private WebSocketListener server;

	final static class Ping extends FramedataImpl1 {
		public Ping() {
			super(Framedata.Opcode.PING);
			setFin(true);
		}
	}

	final static Ping ping = new Ping();

	HttpChannelFactory(final int port, final boolean secure) {
		this(true, port, secure);
	}

	HttpChannelFactory(boolean listen, final int port, final boolean secure) {
		this.listen = listen;
		this.port = port;
		this.secure = secure;
	}

	private LogService getLogService() {
		Activator activator = Activator.getDefault();
		return (activator == null) ? null : activator.getLogService();
	}

	synchronized void logError(String message, Throwable exception) {
		LogService logService = getLogService();
		if (logService != null)
			logService.log(LogService.LOG_ERROR, message, exception);
	}

	synchronized void logWarning(String message, Throwable exception) {
		LogService logService = getLogService();
		if (logService != null)
			logService.log(LogService.LOG_WARNING, message, exception);
	}

	private long startTime;

	public static final String TRACE_TIME_PROP = System
			.getProperty("ch.ethz.iks.r_osgi.transport.http.traceMarshallingTime");

	private static boolean TRACE_TIME = false;
	private static boolean USE_LOG_SERVICE = true;

	static {
		if (TRACE_TIME_PROP != null) {
			if (TRACE_TIME_PROP.equalsIgnoreCase("logservice") || TRACE_TIME_PROP.equalsIgnoreCase("true")) {
				TRACE_TIME = true;
			} else if (TRACE_TIME_PROP.equalsIgnoreCase("systemout")) {
				TRACE_TIME = true;
				USE_LOG_SERVICE = false;
			}
		}
	}

	private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

	void startTiming(String message) {
		if (TRACE_TIME) {
			startTime = System.currentTimeMillis();
			StringBuffer buf = new StringBuffer("TIMING.START;");
			buf.append(sdf.format(new Date(startTime))).append(";");
			buf.append((message == null ? "" : message));
			LogService logService = getLogService();
			if (logService != null && USE_LOG_SERVICE)
				logService.log(LogService.LOG_INFO, buf.toString());
			else
				System.out.println(buf.toString());
		}
	}

	void stopTiming(String message) {
		if (TRACE_TIME) {
			StringBuffer buf = new StringBuffer("TIMING.END;");
			buf.append(sdf.format(new Date(startTime))).append(";");
			buf.append((message == null ? "" : message));
			buf.append(";duration(ms)=").append((System.currentTimeMillis() - startTime));
			LogService logService = getLogService();
			if (logService != null && USE_LOG_SERVICE)
				logService.log(LogService.LOG_INFO, buf.toString());
			else
				System.out.println(buf.toString());
			startTime = 0;
		}
	}

	public NetworkChannel getConnection(final ChannelEndpoint endpoint, final URI endpointURI) throws IOException {
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
			this.remoteAddress = uriFromSocketAddress(protocol, socket.getRemoteSocketAddress());
			this.localAddress = uriFromSocketAddress(protocol, socket.getLocalSocketAddress());
		}

		class ROSGiWebSocketClient extends WebSocketClient {

			private Exception error;

			// XXX note that eventually (after updating to new version
			// of java_websocket.WebSocketClient, that this super constructor
			// can be used to allow client configuration of connect timeout
			//
			// super(java.net.URI.create(serverUri), new Draft_10(), null,
			// CONNECT_TIMEOUT);
			//

			public ROSGiWebSocketClient(String serverUri) {
				super(java.net.URI.create(serverUri));
			}

			@Override
			public void onClose(int arg0, String arg1, boolean arg2) {
				closeSocket();
				HttpChannel.this.endpoint.dispose();
			}

			@Override
			public void onError(Exception error) {
				logWarning("WebSocketClient(" + HttpChannel.this.remoteAddress + ").onError", error);
				this.error = error;
				HttpChannel.this.endpoint.dispose();
			}

			@Override
			public void onMessage(String message) {
				processMessage(message);
			}

			@Override
			public void onMessage(ByteBuffer bytes) {
				processMessage(bytes);
			}

			@Override
			public void onWebsocketPong(WebSocket conn, Framedata f) {
				processPong(f);
			}

			@Override
			public void onOpen(ServerHandshake server) {

			}

			public void doConnect() throws Exception {
				super.connectBlocking();
				if (this.error != null)
					throw this.error;
			}
		}

		public HttpChannel(final ChannelEndpoint endpoint, final URI endpointURI) throws IOException {
			this.endpoint = endpoint;
			this.remoteAddress = endpointURI;
			final ROSGiWebSocketClient client = new ROSGiWebSocketClient(endpointURI.toString());

			try {
				if (secure) {
					client.setWebSocketFactory(new DefaultSSLWebSocketClientFactory(SSLContext.getDefault()));
				}
			} catch (final Exception e) {
				String errMsg = "Exception setting SSL web socket factory";
				logError(errMsg, e);
				throw new IOException(errMsg, e);
			}

			try {
				// connect
				client.doConnect();

			} catch (final Exception e) {
				String errMsg = "Could not connect to target=" + this.remoteAddress;
				logError(errMsg, e);
				throw new IOException(errMsg, e);
			}
			// connect succeeded
			this.socket = client.getConnection();
			this.localAddress = URI.create(client.getURI().toString());
			if (PING_TIMEOUT_PROPERTY > 0) 
				new PingThread("HttpChannel Ping for="+localAddress).start();
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

		synchronized boolean isConnected() {
			return (socket != null && socket.isOpen());
		}

		synchronized void closeSocket() {
			if (isConnected())
				socket.close(CloseFrame.NORMAL);
		}

		private boolean pongReceived;

		void processPong(Framedata f) {
			synchronized (this) {
				pongReceived = true;
				notify();
			}
		}

		class PingThread extends Thread {
			
			public PingThread(String name) {
				super();
				setName(name);
				setDaemon(true);
			}
			
			@Override
			public void run() {
				final int frequency = PING_TIMEOUT_PROPERTY / 2;
				try {
					Thread.sleep(new Random().nextInt(frequency));
				} catch (InterruptedException e) {
					return;
				}
				try {
					while (isConnected()) {
						Thread.sleep(frequency);
						if (Thread.interrupted() || !isConnected())
							return;
						synchronized (HttpChannel.this) {
							HttpChannel.this.wait(frequency);
							if (Thread.interrupted() || !isConnected())
								return;
							pongReceived = false;
							socket.sendFrame(ping);
							int count = 0;
							while (!pongReceived && count < 10) {
								HttpChannel.this.wait(frequency / 10);
								count++;
							}
							if (!pongReceived)
								throw new IOException("Pong not received in " + frequency + "ms");
						}
					}
				} catch (Exception e) {
					System.out.println("Exception in ping thread");
					e.printStackTrace();
					HttpChannel.this.endpoint.dispose();
				}
				System.out.println("PingThread exiting");
			}
		}

		public synchronized void sendMessage(final RemoteOSGiMessage message) throws IOException {
			if (isConnected()) {
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				final ObjectOutputStream out = new ObjectOutputStream(bytes);
				startTiming("serialization funcId=" + message.getFuncID() + ";xid=" + message.getXID());
				message.send(out);
				out.close();
				stopTiming("serialization  funcId=" + message.getFuncID() + ";xid=" + message.getXID());
				if (!USE_BYTE_BUFFER) {
					startTiming("base64encoding byteslength=" + bytes.size());
					String base64String = Base64.encodeBytes(bytes.toByteArray(), Base64.GZIP);
					stopTiming("base64encoding stringLength=" + base64String.length());
					startTiming("socket send");
					socket.send(base64String);
					stopTiming("socket send");
				} else {
					startTiming("socket send");
					socket.send(ByteBuffer.wrap(bytes.toByteArray()));
					stopTiming("socket send");
				}
			}
		}

		private URI uriFromSocketAddress(final String protocol, final InetSocketAddress addr) {
			return URI.create(protocol + "://" + addr.getHostName() + ":" + addr.getPort());
		}

		public synchronized void processMessage(final String message) {
			if (isConnected()) {
				try {
					startTiming("base64decode message length=" + message.length());
					byte[] base64decoded = Base64.decode(message);
					stopTiming("base64decode bytesDecoded=" + base64decoded.length);
					final ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(base64decoded));
					startTiming("RemoteOSGiMessage.parse");
					final RemoteOSGiMessage msg = RemoteOSGiMessage.parse(in);
					in.close();
					stopTiming("RemoteOSGiMessage.parse funcId=" + msg.getFuncID() + ";xid=" + msg.getXID());
					endpoint.receivedMessage(msg);
				} catch (Exception e) {
					logError("HttpChannel.processMessage", e);
				}
			}
		}

		public synchronized void processMessage(final ByteBuffer bytes) {
			if (isConnected()) {
				try {
					if (bytes.hasArray()) {
						final ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.array()));
						startTiming("RemoteOSGiMessage.parse");
						final RemoteOSGiMessage msg = RemoteOSGiMessage.parse(in);
						in.close();
						stopTiming("RemoteOSGiMessage.parse funcId=" + msg.getFuncID() + ";xid=" + msg.getXID());
						endpoint.receivedMessage(msg);
					} else
						throw new IllegalArgumentException("processMessage bytes argument does not contain an array");
				} catch (Exception e) {
					logError("HttpChannel.processMessage message", e);
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
					this.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(SSLContext.getDefault()));
				} catch (final NoSuchAlgorithmException e) {
					logError("WebSocketListener<init> exception in setWebSocketFactory", e);
					throw new IOException("Could not create SSL context", e);
				}
			}
		}

		public void onClose(final WebSocket socket, final int code, final String reason, final boolean remote) {
			channels.remove(socket);
		}

		public void onError(WebSocket socket, Exception error) {
			logWarning("WebSocketListener.onError socket=" + socket, error);
			channels.remove(socket);
		}

		public void onMessage(WebSocket socket, String message) {
			final HttpChannel channel = channels.get(socket);
			if (channel != null)
				channel.processMessage(message);
		}

		public void onMessage(WebSocket socket, ByteBuffer bytes) {
			final HttpChannel channel = channels.get(socket);
			if (channel != null)
				channel.processMessage(bytes);
		}

		@Override
		public void onWebsocketPing(WebSocket conn, Framedata f) {
			super.onWebsocketPing(conn, f);
		}

		public void onOpen(WebSocket socket, ClientHandshake handshake) {
			final HttpChannel channel = new HttpChannel(socket);
			remoting.createEndpoint(channel);
			channels.put(socket, channel);
		}
	}

}
