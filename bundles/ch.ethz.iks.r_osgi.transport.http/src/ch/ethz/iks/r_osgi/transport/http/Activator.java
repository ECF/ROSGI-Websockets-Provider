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

import ch.ethz.iks.r_osgi.channels.NetworkChannelFactory;

public class Activator implements BundleActivator {

	/**
	 * @see org.osgi.framework.BundleActivator#start(org.osgi.framework.BundleContext
	 *      )
	 */
	public void start(final BundleContext context) throws Exception {

		final Dictionary<String, Object> properties = new Hashtable<String, Object>();
		properties.put(NetworkChannelFactory.PROTOCOL_PROPERTY,
				HttpChannelFactory.PROTOCOL_HTTP);
		context.registerService(
				NetworkChannelFactory.class.getName(),
				new HttpChannelFactory(getProperty(context,
						HttpChannelFactory.HTTP_PORT_PROPERTY,
						HttpChannelFactory.DEFAULT_HTTP_PORT), false),
				properties);

		properties.put(NetworkChannelFactory.PROTOCOL_PROPERTY,
				HttpChannelFactory.PROTOCOL_HTTPS);
		context.registerService(
				NetworkChannelFactory.class.getName(),
				new HttpChannelFactory(getProperty(context,
						HttpChannelFactory.HTTPS_PORT_PROPERTY,
						HttpChannelFactory.DEFAULT_HTTPS_PORT), true),
				properties);

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
	public void stop(final BundleContext context) throws Exception {
	}

}
