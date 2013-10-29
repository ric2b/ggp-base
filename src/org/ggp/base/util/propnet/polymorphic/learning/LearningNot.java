package org.ggp.base.util.propnet.polymorphic.learning;

import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;

/**
 * The Not class is designed to represent logical NOT gates.
 */
@SuppressWarnings("serial")
public final class LearningNot extends LearningComponent implements PolymorphicNot
{
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

    protected boolean getValueAndCost(EncapsulatedCost aggregatedCost)
    {
		aggregatedCost.incrementCost();
 		
		if ( dirty )
		{
			return !((LearningComponent)getSingleInput()).getValueAndCost(aggregatedCost);
		}
		else
		{
			return cachedValue;
		}
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