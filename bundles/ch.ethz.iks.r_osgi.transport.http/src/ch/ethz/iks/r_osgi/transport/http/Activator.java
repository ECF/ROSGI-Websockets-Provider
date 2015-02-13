/*******************************************************************************
 * Copyright (c) 2015 IBM, Inc. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: Jan S. Rellermeyer, IBM Research - initial API and implementation
 ******************************************************************************/
package ch.ethz.iks.r_osgi.transport.http;

import java.util.Dictionary;
import java.util.Hashtable;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;

import ch.ethz.iks.r_osgi.channels.NetworkChannelFactory;

public class Activator implements BundleActivator {

	private static boolean listen = new Boolean(System.getProperty(
			"ch.ethz.iks.r_osgi.transport.http.listen", "true"));

	private static boolean registerHttp = new Boolean(System.getProperty(
			"ch.ethz.iks.r_osgi.transport.registerHttp", "true"))
			.booleanValue();
	private static boolean registerHttps = new Boolean(System.getProperty(
			"ch.ethz.iks.r_osgi.transport.registerHttps", "true"))
			.booleanValue();

	private static Activator activator;
	private static BundleContext context;
	private ServiceTracker<LogService, LogService> logTracker;

	public static Activator getDefault() {
		return activator;
	}

	/**
	 * @see org.osgi.framework.BundleActivator#start(org.osgi.framework.BundleContext
	 *      )
	 */
	public void start(final BundleContext ctxt) throws Exception {
		activator = this;
		context = ctxt;
		final Dictionary<String, Object> properties = new Hashtable<String, Object>();
		properties.put(NetworkChannelFactory.PROTOCOL_PROPERTY,
				HttpChannelFactory.PROTOCOL_HTTP);
		if (registerHttp)
			context.registerService(
					NetworkChannelFactory.class.getName(),
					new HttpChannelFactory(listen, getProperty(context,
							HttpChannelFactory.HTTP_PORT_PROPERTY,
							HttpChannelFactory.DEFAULT_HTTP_PORT), false),
					properties);

		properties.put(NetworkChannelFactory.PROTOCOL_PROPERTY,
				HttpChannelFactory.PROTOCOL_HTTPS);
		if (registerHttps)
			context.registerService(
					NetworkChannelFactory.class.getName(),
					new HttpChannelFactory(listen, getProperty(context,
							HttpChannelFactory.HTTPS_PORT_PROPERTY,
							HttpChannelFactory.DEFAULT_HTTPS_PORT), true),
					properties);

	}

	public synchronized LogService getLogService() {
		if (context == null)
			return null;
		if (logTracker == null) {
			logTracker = new ServiceTracker<LogService, LogService>(context,
					LogService.class, null);
			logTracker.open();
		}
		return logTracker.getService();
	}

	private int getProperty(final BundleContext context,
			final String propertyName, int defaultValue) {
		final String prop = context.getProperty(propertyName);
		if (prop != null) {
			try {
				return Integer.parseInt(prop);
			} catch (final NumberFormatException _) {
				// fall through
			}
		}
		return defaultValue;
	}

	/**
	 * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
	 */
	public void stop(final BundleContext ctxt) throws Exception {
		if (logTracker != null) {
			logTracker.close();
			logTracker = null;
		}
		context = null;
		activator = null;
	}

}
