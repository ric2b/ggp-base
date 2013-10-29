package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;

/**
 * The Not class is designed to represent logical NOT gates.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedNot extends RuntimeOptimizedComponent implements PolymorphicNot
{
	public RuntimeOptimizedNot(int numOutput) {
		super(1, numOutput);
	}

	/**
	 * Returns the inverse of the input to the not.
	 * 
	 * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
	 */
	@Override
	protected boolean getValueInternal()
	{
		return !getSingleInput().getValue();
	}

	/**
	 * @see org.ggp.base.util.propnet.architecture.Component#toString()
	 */
	@Override
	public String toString()
	{
		return toDot("invtriangle", "grey", "NOT");
	}
}