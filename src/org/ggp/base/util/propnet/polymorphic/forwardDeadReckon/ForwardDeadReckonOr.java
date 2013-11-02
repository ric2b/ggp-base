package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;

/**
 * The Or class is designed to represent logical OR gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonOr extends ForwardDeadReckonComponent implements PolymorphicOr
{
	int trueInputCount = 0;
	
	public ForwardDeadReckonOr(int numInputs, int numOutputs) {
		super(numInputs, numOutputs);
	}
    
    public void reset()
    {
    	super.reset();
    	cachedValue = false;
    	trueInputCount = 0;
    }
    
    public void setKnownChangedState(boolean newState, ForwardDeadReckonComponent source)
    {
    	//validate();
    	
    	if ( newState )
    	{
    		trueInputCount++;
    	}
    	else
    	{
    		trueInputCount--;
    	}
    	
    	if ( cachedValue != (trueInputCount != 0) )
    	{
    		cachedValue = (trueInputCount != 0);
    		
    		queuePropagation();
    	}
    }
    
  	public void validate()
  	{
  		int trueInputCount = 0;
  		
  		for(ForwardDeadReckonComponent c : inputsArray)
  		{
  			if ( c.getLastPropagatedValue())
  			{
  				trueInputCount++;
  				break;
  			}
  		}
  		
  		if ( (trueInputCount != 0) != cachedValue )
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
		return toDot("ellipse", "grey", "OR");
	}
}