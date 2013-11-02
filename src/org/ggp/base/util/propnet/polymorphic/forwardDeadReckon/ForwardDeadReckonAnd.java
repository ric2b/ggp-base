package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;


/**
 * The And class is designed to represent logical AND gates.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonAnd extends ForwardDeadReckonComponent implements PolymorphicAnd
{
	private int falseInputCount = 0;
	
	public ForwardDeadReckonAnd(int numInputs, int numOutput) {
		super(numInputs, numOutput);
	}
    
    public void reset()
    {
    	super.reset();
    	cachedValue = false;
    	falseInputCount = inputsArray.length;
    }
    
    public void setKnownChangedState(boolean newState, ForwardDeadReckonComponent source)
    {
    	if ( newState )
    	{
    		falseInputCount--;
    	}
    	else
    	{
    		falseInputCount++;
    	}
    	//System.out.println("AND " + Integer.toHexString(hashCode()) + " with value " + cachedValue + " received new input " + newState + ", causing false count to become " + falseInputCount);
    	
    	if ( cachedValue != (falseInputCount == 0) )
    	{
    		cachedValue = (falseInputCount == 0);
    		//System.out.println("AND value set to "+ cachedValue);
    		
    		queuePropagation();
    	}
    }
    
  	public void validate()
  	{
  		int falseInputCount = 0;
  		
  		for(ForwardDeadReckonComponent c : inputsArray)
  		{
  			if ( !c.getLastPropagatedValue())
  			{
  				falseInputCount++;
  				break;
  			}
  		}
  		
  		if ( (falseInputCount == 0) != cachedValue )
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
		return toDot("invhouse", "grey", "AND");
	}

}
