package org.ggp.base.util.propnet.polymorphic.learning;

import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

/**
 * The Transition class is designed to represent pass-through gates.
 */
@SuppressWarnings("serial")
public final class LearningTransition extends LearningComponent implements PolymorphicTransition
{
	/**
	 * Returns the value of the input to the transition.
	 * 
	 * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
	 */
	@Override
	protected boolean getValueInternal()
	{
		return getSingleInput().getValue();
	}

    protected boolean getValueAndCost(EncapsulatedCost aggregatedCost)
    {
		aggregatedCost.incrementCost();
 		
		if ( dirty )
		{
			return ((LearningComponent)getSingleInput()).getValueAndCost(aggregatedCost);
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
		return toDot("box", "grey", "TRANSITION");
	}
}