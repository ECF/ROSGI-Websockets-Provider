package ch.ethz.iks.r_osgi.transport.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;

import org.eclipse.ecf.core.util.IClassResolver;

public class ClassResolverObjectInputStream extends ObjectInputStream {

	private IClassResolver classResolver;
	
	public ClassResolverObjectInputStream(IClassResolver classResolver, InputStream ins) throws IOException {
		super(ins);
		this.classResolver = classResolver;
		this.enableResolveObject(true);
	}

	@Override
	protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
		return this.classResolver.resolveClass(desc);
	}
	
}
