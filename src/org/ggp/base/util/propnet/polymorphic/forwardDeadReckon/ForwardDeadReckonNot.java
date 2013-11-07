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

    public void setKnownChangedState(boolean newState, ForwardDeadReckonComponent source)
    {
		cachedValue = !newState;
		
		if ( queuePropagation )
		{
			queuePropagation();
		}
		else
		{
			propagate();
		}
    }
    
    @Override
    public void reset()
    {
    	super.reset();
    	cachedValue = true;
    }
    
  	public void validate()
  	{
  		if ( cachedValue != !inputsArray[0].getLastPropagatedValue())
  		{
  			System.out.println("Validation failure for " + toString());
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