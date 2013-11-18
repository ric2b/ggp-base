package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Not class is designed to represent logical NOT gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonNot extends ForwardDeadReckonComponent implements PolymorphicNot
{
	public ForwardDeadReckonNot(int numOutput) {
		super(1, numOutput);
	}

    public void setKnownChangedState(boolean newState, int instanceId, ForwardDeadReckonComponent source)
    {
		cachedValue[instanceId] = !newState;
		
		if ( queuePropagation )
		{
			queuePropagation(instanceId);
		}
		else
		{
			propagate(instanceId);
		}
    }
    
    @Override
    public void reset(int instanceId)
    {
    	super.reset(instanceId);
    	cachedValue[instanceId] = true;
    }
    
  	public void validate()
  	{
  		for(int instanceId = 0; instanceId < cachedValue.length; instanceId++)
  		{
	  		if ( cachedValue[instanceId] != !inputsArray[0].getLastPropagatedValue(instanceId))
	  		{
	  			System.out.println("Validation failure for " + toString());
	  		}
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